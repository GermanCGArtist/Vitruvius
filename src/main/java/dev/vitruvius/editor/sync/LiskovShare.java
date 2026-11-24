package dev.vitruvius.editor.sync;

import com.google.gson.JsonObject;
import dev.liskov.core.DriveMount;
import dev.liskov.core.FileTypeRegistry;
import dev.liskov.core.LiskovFs;
import dev.liskov.core.sync.SyncWire;
import dev.liskov.core.vcs.VcsCommit;
import dev.liskov.core.vcs.VcsHead;
import dev.liskov.core.vcs.VcsStore;
import dev.liskov.net.LiskovReloadBus;
import dev.liskov.net.LiskovSyncClient;
import dev.vitruvius.VitruviusPaths;
import dev.vitruvius.editor.EditorSession;
import dev.vitruvius.editor.core.ProjectNode;
import dev.vitruvius.editor.core.Scene;
import dev.vitruvius.editor.io.FormatException;
import dev.vitruvius.editor.io.ProjectIo;
import dev.vitruvius.editor.io.ProjectLayout;
import dev.vitruvius.editor.mc.ProjectManager;
import dev.vitruvius.editor.ui.MenuModel;
import dev.vitruvius.editor.ui.ToastQueue;
import net.minecraft.client.Minecraft;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Sharing projects on the server's Liskov drive (replaces the Syncmatica sharing of earlier 2.0 alphas).
 *
 * <ul>
 *   <li><b>Share</b> copies the project to {@code Projects/<name>/} on the drive of the server the player is on, pushes it,
 *       and from then on the editor works on that copy (the local folder stays as it was).
 *   <li><b>Push</b> records the folder ({@code project.vproj} + {@code files/**}) as a Liskov version and sends what the
 *       server lacks. With <b>Auto</b> (the default) that happens two seconds after the last edit; Ctrl S pushes a named version.
 *   <li><b>Other players' versions</b> arrive through Liskov (it downloads them into the folder for projects that are
 *       open) and are merged three ways into the open project, against the version they were made from, so nobody loses
 *       work when two push at once. Nodes are changed in place: the undo stack stays valid; merged changes are no undo step.
 *   <li><b>Open</b>: the "Shared projects" list shows the projects on the server; one is downloaded and opened from there.
 *   <li>Who may push is Liskov's access list (the creator is admin, others view until given EDIT); its Explorer (F8) shows
 *       the server history and restores old versions, which arrive here like any other version.
 * </ul>
 * Client thread, except reading and hashing the files of a push (one worker).
 */
public final class LiskovShare implements MenuModel.SyncHook {
    private static final LiskovShare INSTANCE = new LiskovShare();

    public static LiskovShare get() {
        return INSTANCE;
    }

    /** Ticks without a change before an automatic push, and at least between two pushes of one project. */
    static final int QUIET_TICKS = 40;
    private static final int PENDING_EVERY = 10;

    public enum State { IDLE, PUSHING, FAILED }

    public record Status(State state, int done, int total, String message) {}

    /** A project on the server, for the "Shared projects" list. */
    public record Entry(String name, String relativePath, String author, int version, SyncWire.RepoEntry repo) {}

    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "Vitruvius share");
        t.setDaemon(true);
        return t;
    });
    private VcsStore vcs;
    private boolean listening;
    private int tick;

    private final Map<UUID, Integer> pending = new HashMap<>();
    private final Map<UUID, Integer> changedAt = new HashMap<>();
    private final Map<UUID, Integer> pushedAt = new HashMap<>();
    private final Set<UUID> inFlight = new HashSet<>();
    /** A push handed to Liskov, counted as done once the server's index shows it as the head. */
    private record Awaiting(String repoId, String commitId, JsonObject scene, Path dir, int since) {}
    private final Map<UUID, Awaiting> awaiting = new HashMap<>();
    private final Map<UUID, Integer> failedAt = new HashMap<>();
    /** Ticks to wait for the server to take a push, and before a refused one is tried again. */
    static final int ACK_TICKS = 200;
    private final Set<UUID> checked = new HashSet<>();
    private String checkedServer;
    private final Map<String, Entry> opening = new HashMap<>();
    private String receiveText;
    private String lastError;

    private final Properties manual = new Properties();
    private boolean manualLoaded;

    private LiskovShare() {}

    // ------------------------------------------------------------------------------------------ connection

    private static LiskovSyncClient client() {
        return LiskovSyncClient.get();
    }

    /** The drive of the server the player is on, or null (single player, or a server without Liskov). */
    public DriveMount serverDrive() {
        String id = client().activeServerId();
        if (id == null || !LiskovFs.isInitialized()) return null;
        DriveMount m = LiskovFs.get().drives().get(id);
        return m == null || m.isOffline() ? null : m;
    }

    @Override
    public boolean installed() {
        return true;   // Liskov is a dependency; whether a server takes part is connected()
    }

    @Override
    public boolean usable() {
        return true;
    }

    @Override
    public boolean connected() {
        return serverDrive() != null;
    }

    @Override
    public String reason() {
        return connected() ? "" : "Not connected to a server with Liskov";
    }

    /** The server drive a project folder is on (online or not), or null for a local project. */
    public DriveMount driveOf(Path projectDir) {
        if (projectDir == null || !LiskovFs.isInitialized()) return null;
        Path d = projectDir.toAbsolutePath().normalize();
        for (DriveMount m : LiskovFs.get().drives().serverDrives()) {
            if (d.startsWith(m.rootDir().toAbsolutePath().normalize())) return m;
        }
        return null;
    }

    private static ProjectManager pm() {
        return dev.vitruvius.editor.mc.Reconciler.projects();
    }

    private Path dirOf(ProjectNode p) {
        return pm().projectDir(p);
    }

    /** Liskov's path of an open shared project on its drive, or null. */
    private String repoPath(ProjectNode p) {
        Path dir = dirOf(p);
        DriveMount m = driveOf(dir);
        return m == null ? null : ShareCore.repoPathOf(m.rootDir(), dir);
    }

    private synchronized VcsStore vcs() throws IOException {
        if (vcs == null) vcs = new VcsStore(LiskovFs.get().vcsRootDir());
        return vcs;
    }

    // ------------------------------------------------------------------------------------------ what the UI shows

    public int sharedProjects() {
        EditorSession s = EditorSession.get();
        if (s == null) return 0;
        int n = 0;
        for (ProjectNode p : s.scene().projects()) if (p.sync() == ProjectNode.Sync.SHARED) n++;
        return n;
    }

    /** Changes not on the server yet, over all shared projects. */
    public int pending() {
        int n = 0;
        for (int v : pending.values()) n += v;
        return n;
    }

    public int pending(ProjectNode p) {
        return pending.getOrDefault(p.id(), 0);
    }

    public boolean busy() {
        return !inFlight.isEmpty() || !awaiting.isEmpty();
    }

    public Status status() {
        if (busy()) return new Status(State.PUSHING, 0, inFlight.size() + awaiting.size(), null);
        if (lastError != null) return new Status(State.FAILED, 0, 0, lastError);
        return new Status(State.IDLE, 0, 0, null);
    }

    public boolean autoPush(ProjectNode p) {
        loadManual();
        String key = repoPath(p);
        return key == null || !manual.containsKey(key);
    }

    public void setAutoPush(ProjectNode p, boolean auto) {
        loadManual();
        String key = repoPath(p);
        if (key == null) return;
        if (auto) manual.remove(key);
        else manual.setProperty(key, "manual");
        saveManual();
    }

    private Path manualFile() {
        return VitruviusPaths.configDir().resolve("share.properties");
    }

    private void loadManual() {
        if (manualLoaded) return;
        manualLoaded = true;
        Path f = manualFile();
        if (!Files.isRegularFile(f)) return;
        try (InputStream in = Files.newInputStream(f)) {
            manual.load(in);
        } catch (IOException e) {
            System.out.println("[Vitruvius] cannot read " + f + ": " + e.getMessage());
        }
    }

    private void saveManual() {
        Path f = manualFile();
        try {
            Files.createDirectories(f.getParent());
            try (OutputStream out = Files.newOutputStream(f)) {
                manual.store(out, "Vitruvius: shared projects that push only when asked");
            }
        } catch (IOException e) {
            System.out.println("[Vitruvius] cannot write " + f + ": " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------------------------------ tick

    /** Every client tick while the editor's reconciler runs. */
    public void tick(Scene scene) {
        listen();
        tick++;
        String server = client().activeServerId();
        if (!java.util.Objects.equals(server, checkedServer)) {
            checkedServer = server;
            checked.clear();
        }
        checkAcks();
        boolean count = tick % PENDING_EVERY == 0;
        for (ProjectNode p : List.copyOf(scene.projects())) {   // an arriving version may be merged during the loop
            Path dir = dirOf(p);
            DriveMount drive = driveOf(dir);
            ProjectNode.Sync want = drive == null ? ProjectNode.Sync.LOCAL : ProjectNode.Sync.SHARED;
            if (p.sync() != want) p.setSync(want);
            if (drive == null) {
                pending.remove(p.id());
                continue;
            }
            String rel = ShareCore.repoPathOf(drive.rootDir(), dir);
            if (rel == null) continue;
            String repoId = VcsStore.repoIdFor(rel);
            client().setOpen(repoId, true);
            DriveMount online = serverDrive();
            boolean here = online != null && online.driveId().equals(drive.driveId());
            if (here && checked.add(p.id())) catchUp(p, rel, repoId);
            if (count) {
                int before = pending.getOrDefault(p.id(), 0);
                int now = ShareCore.pending(ShareCore.readBase(dir), ShareCore.json(p));
                pending.put(p.id(), now);
                if (now != before) changedAt.put(p.id(), tick);
            }
            int n = pending.getOrDefault(p.id(), 0);
            if (here && n > 0 && autoPush(p) && !inFlight.contains(p.id()) && !awaiting.containsKey(p.id())
                    && tick - failedAt.getOrDefault(p.id(), -ACK_TICKS) >= ACK_TICKS
                    && tick - changedAt.getOrDefault(p.id(), 0) >= QUIET_TICKS
                    && tick - pushedAt.getOrDefault(p.id(), -QUIET_TICKS) >= QUIET_TICKS) {
                push(p, null, false);
            }
        }
        pending.keySet().removeIf(id -> scene.projects().stream().noneMatch(p -> p.id().equals(id)));
        if (ShareSelfTest.ROLE != null) ShareSelfTest.tick(this, pm());
    }

    /** For {@link ShareSelfTest}: the repo id of an open shared project, or null. */
    String repoIdOf(ProjectNode p) {
        String rel = repoPath(p);
        return rel == null ? null : VcsStore.repoIdFor(rel);
    }

    /** Pushes the server took (it lists them as head) become the agreed base; ones it did not take in time failed. */
    private void checkAcks() {
        for (var it = awaiting.entrySet().iterator(); it.hasNext(); ) {
            var e = it.next();
            Awaiting a = e.getValue();
            SyncWire.RepoEntry entry = serverEntry(a.repoId());
            if (entry != null && a.commitId().equals(entry.headCommitId)) {
                ShareCore.writeBase(a.dir(), a.scene());
                failedAt.remove(e.getKey());
                if (failedAt.isEmpty()) lastError = null;
                it.remove();   // pending is counted again against the new base on the next count
            } else if (tick - a.since() > ACK_TICKS) {
                boolean first = !failedAt.containsKey(e.getKey());
                failedAt.put(e.getKey(), tick);
                lastError = "The server did not take the push (EDIT access in Liskov? F8, Sharing). Trying again.";
                if (first) toast(ToastQueue.Level.WARN, lastError);
                it.remove();
            }
        }
    }

    /**
     * After joining: when the server holds a newer version of an open project than this computer last agreed on
     * (pushed by someone while this player was away), fetch it; it is merged when it arrives.
     */
    private void catchUp(ProjectNode p, String rel, String repoId) {
        SyncWire.RepoEntry e = serverEntry(repoId);
        if (e == null || e.headCommitId == null) return;
        try {
            VcsHead head = vcs().loadHead(serverDrive().driveId(), repoId);
            if (e.headCommitId.equals(head.headCommitId)) return;
        } catch (IOException ex) {
            return;
        }
        client().requestDownload(e);
    }

    private SyncWire.RepoEntry serverEntry(String repoId) {
        String server = client().activeServerId();
        if (server == null) return null;
        for (SyncWire.FolderSummary f : client().foldersOf(server)) {
            if (f.entries == null) continue;
            for (SyncWire.RepoEntry e : f.entries) if (repoId.equals(e.repoId)) return e;
        }
        return null;
    }

    // ------------------------------------------------------------------------------------------ push

    /** Pushes every shared project that has changes (the toolbar's Push). */
    public void pushAll(Scene scene) {
        for (ProjectNode p : scene.projects()) if (p.sync() == ProjectNode.Sync.SHARED && pending(p) > 0) push(p, null, true);
    }

    /** Pushes one shared project now (its menu's Push). */
    public void push(ProjectNode p) {
        push(p, null, true);
    }

    /** Ctrl S on a shared project: a named version on the server. */
    public void checkpoint(ProjectNode p) {
        if (p.sync() == ProjectNode.Sync.SHARED) push(p, "Save " + p.name(), true);
    }

    /**
     * Saves the project, then (on the worker) records the folder as a version on the server drive's history and hands
     * it to Liskov, which uploads only the files the server lacks. {@code explicit} makes it a named version.
     */
    private void push(ProjectNode p, String message, boolean explicit) {
        DriveMount drive = serverDrive();
        Path dir = dirOf(p);
        if (drive == null || driveOf(dir) == null || !driveOf(dir).driveId().equals(drive.driveId())) {
            if (explicit) toast(ToastQueue.Level.WARN, "\"" + p.name() + "\" can only be pushed on the server it is shared on");
            return;
        }
        String rel = ShareCore.repoPathOf(drive.rootDir(), dir);
        if (rel == null || inFlight.contains(p.id()) || awaiting.containsKey(p.id())) return;
        SyncWire.RepoEntry onServer = serverEntry(VcsStore.repoIdFor(rel));
        String serverHead = onServer == null ? null : onServer.headCommitId;
        pm().save(p.id());
        JsonObject scene = ShareCore.json(p);
        UUID id = p.id();
        Minecraft mc = Minecraft.getInstance();
        String uuid = mc.player != null ? mc.player.getUUID().toString() : "";
        String name = mc.player != null ? mc.player.getGameProfile().name() : "Player";
        String msg = message != null ? message : explicit ? "Push " + p.name() : "Autosave";
        String driveId = drive.driveId();
        inFlight.add(id);
        pushedAt.put(id, tick);
        worker.execute(() -> {
            try {
                Map<String, byte[]> files = ShareCore.bundle(dir);
                VcsStore store = vcs();
                String repoId = VcsStore.repoIdFor(rel);
                if (sameTree(store, driveId, repoId, serverHead, files)) {
                    mc.execute(() -> {   // the server has exactly this already: no new version
                        inFlight.remove(id);
                        ShareCore.writeBase(dir, scene);
                        pending.put(id, 0);
                    });
                    return;
                }
                // an autosave identical to this computer's last version returns that version: it is sent again
                // (a refused push is retried this way)
                VcsCommit c = store.snapshot(driveId, repoId, uuid, name, msg, false, !explicit, files);
                mc.execute(() -> {
                    inFlight.remove(id);
                    client().pushCommit(null, rel, FileTypeRegistry.VPROJ.id(), c, files);
                    awaiting.put(id, new Awaiting(repoId, c.commitId, scene, dir, tick));
                });
            } catch (IOException | RuntimeException e) {
                mc.execute(() -> {
                    inFlight.remove(id);
                    lastError = "Push failed: " + e.getMessage();
                    toast(ToastQueue.Level.ERROR, "Cannot push \"" + msg + "\": " + e.getMessage());
                });
            }
        });
    }

    private static boolean sameTree(VcsStore store, String driveId, String repoId, String commitId, Map<String, byte[]> files) {
        if (commitId == null || commitId.isEmpty()) return false;
        Map<String, String> hashes = new java.util.LinkedHashMap<>();
        for (var e : files.entrySet()) hashes.put(e.getKey(), dev.liskov.core.LiskovPaths.sha256Hex(e.getValue()));
        for (VcsCommit c : store.loadCommits(driveId, repoId)) if (commitId.equals(c.commitId)) return hashes.equals(c.tree);
        return false;
    }

    // ------------------------------------------------------------------------------------------ share / stop

    /** Copies a local project to the server drive, pushes it, and switches the editor to the server copy. */
    public void share(ProjectNode p) {
        DriveMount drive = serverDrive();
        if (drive == null) {
            toast(ToastQueue.Level.WARN, reason());
            return;
        }
        ProjectManager pm = pm();
        Path local = dirOf(p);
        Path projects = drive.rootDir().resolve(ProjectLayout.PROJECTS_DIR);
        Set<String> taken = new HashSet<>();
        for (SyncWire.FolderSummary f : client().foldersOf(client().activeServerId())) {
            if (f.entries == null) continue;
            for (SyncWire.RepoEntry e : f.entries) {
                String n = ShareCore.folderOf(e.relativePath);
                if (n != null) taken.add(n);
            }
        }
        String folder = ProjectLayout.uniqueName(local.getFileName().toString(), n -> taken.contains(n) || Files.exists(projects.resolve(n)));
        Path target = projects.resolve(folder);
        boolean wasActive = pm.scene().activeProject() == p;
        try {
            pm.save(p.id());
            ShareCore.writeBundle(target, ShareCore.bundle(local));
        } catch (IOException | RuntimeException e) {
            toast(ToastQueue.Level.ERROR, "Cannot copy \"" + p.name() + "\" to the server drive: " + e.getMessage());
            return;
        }
        pm.close(p.id());
        ProjectNode shared = openHere(pm, target, wasActive);
        if (shared == null) return;
        push(shared, "Share " + shared.name(), true);
        toast(ToastQueue.Level.INFO, "\"" + shared.name() + "\" is shared on " + drive.displayName()
                + ". Others need EDIT access in Liskov (F8, Sharing) to change it; your local copy is unchanged.");
    }

    /** Makes a local copy of a shared project and switches the editor to it; the server copy stays for the others. */
    public void stopSharing(ProjectNode p) {
        ProjectManager pm = pm();
        Path dir = dirOf(p);
        Path root = ProjectLayout.projectsRoot(VitruviusPaths.schematicsDir());
        String folder = ProjectLayout.uniqueName(dir.getFileName().toString(), n -> Files.exists(root.resolve(n)));
        Path target = root.resolve(folder);
        boolean wasActive = pm.scene().activeProject() == p;
        try {
            pm.save(p.id());
            ShareCore.writeBundle(target, ShareCore.bundle(dir));
        } catch (IOException | RuntimeException e) {
            toast(ToastQueue.Level.ERROR, "Cannot copy \"" + p.name() + "\" to the local drive: " + e.getMessage());
            return;
        }
        String rel = repoPath(p);
        if (rel != null) client().setOpen(VcsStore.repoIdFor(rel), false);
        pm.close(p.id());
        ProjectNode copy = openHere(pm, target, wasActive);
        if (copy != null) toast(ToastQueue.Level.INFO, "Now editing a local copy of \"" + copy.name() + "\" (" + target + "). The shared one stays on the server.");
    }

    /** Opens a project folder, bound to the world and dimension the player is in (a shared file names the pusher's). */
    private ProjectNode openHere(ProjectManager pm, Path dir, boolean active) {
        try {
            ProjectNode p = pm.open(dir);
            if (!pm.projectDir(p).equals(dir.toAbsolutePath().normalize())) {
                toast(ToastQueue.Level.WARN, "A copy of \"" + p.name() + "\" is open already (" + pm.projectDir(p)
                        + "): close it first to open " + dir);
                return null;
            }
            p.setWorld(pm.world());
            p.setDim(pm.dim());
            p.setSync(driveOf(dir) == null ? ProjectNode.Sync.LOCAL : ProjectNode.Sync.SHARED);
            if (active) pm.setActive(p.id());
            EditorSession s = EditorSession.get();
            if (s != null) s.invalidateBoxes();
            return p;
        } catch (IOException | RuntimeException e) {
            toast(ToastQueue.Level.ERROR, "Cannot open " + dir + ": " + e.getMessage());
            return null;
        }
    }

    // ------------------------------------------------------------------------------------------ open a shared project

    /** The projects on the server's drive that are not open here. */
    public List<Entry> sharedProjectList() {
        String server = client().activeServerId();
        if (server == null || serverDrive() == null) return List.of();
        Set<String> open = new HashSet<>();
        EditorSession s = EditorSession.get();
        if (s != null) for (ProjectNode p : s.scene().projects()) {
            String r = repoPath(p);
            if (r != null) open.add(r);
        }
        List<Entry> out = new ArrayList<>();
        for (SyncWire.FolderSummary f : client().foldersOf(server)) {
            if (f.entries == null) continue;
            for (SyncWire.RepoEntry e : f.entries) {
                String name = ShareCore.folderOf(e.relativePath);
                if (name != null && !open.contains(e.relativePath)) out.add(new Entry(name, e.relativePath, e.authorName, e.versionNum, e));
            }
        }
        out.sort(java.util.Comparator.comparing(e -> e.name().toLowerCase(java.util.Locale.ROOT)));
        return out;
    }

    public boolean receiving() {
        return !opening.isEmpty();
    }

    public String receiveStatus() {
        return receiveText;
    }

    /** Downloads a shared project (only the files this computer lacks) and opens it when it is there. */
    public void receive(Entry e) {
        DriveMount drive = serverDrive();
        if (drive == null) return;
        listen();
        opening.put(e.relativePath(), e);
        receiveText = "Downloading \"" + e.name() + "\"…";
        client().setOpen(e.repo().repoId, true);
        client().requestDownload(e.repo());   // materialises at once when every file is here already
    }

    // ------------------------------------------------------------------------------------------ incoming versions

    private synchronized void listen() {
        if (listening) return;
        listening = true;
        LiskovReloadBus.subscribe(notice -> {
            Minecraft mc = Minecraft.getInstance();
            if (mc.isSameThread()) arrived(notice);   // Liskov writes the files on the client thread: read them before a save does
            else mc.execute(() -> arrived(notice));
        });
    }

    private void arrived(LiskovReloadBus.ReloadNotice n) {
        DriveMount drive = serverDrive();
        if (drive == null || !drive.driveId().equals(n.driveId()) || n.relativePath() == null) return;
        String name = ShareCore.folderOf(n.relativePath());
        if (name == null) return;
        Path dir = drive.rootDir().resolve(ProjectLayout.PROJECTS_DIR).resolve(name);
        ProjectManager pm = pm();
        if (!pm.inWorld()) return;
        ProjectNode open = null;
        for (ProjectNode p : pm.projects()) if (n.relativePath().equals(repoPath(p))) open = p;
        String repoId = VcsStore.repoIdFor(n.relativePath());
        VcsCommit commit = incomingCommit(repoId, n.versionNum());
        if (open == null) {
            Entry e = opening.remove(n.relativePath());
            if (e == null) return;
            receiveText = null;
            JsonObject theirs = ShareCore.read(dir.resolve(ProjectLayout.PROJECT_FILE));
            accept(drive, repoId, commit, e.repo());
            if (theirs != null) ShareCore.writeBase(dir, theirs);
            ProjectNode p = openHere(pm, dir, true);
            if (p != null) toast(ToastQueue.Level.INFO, "Opened the shared project \"" + p.name() + "\" (v" + e.version() + " by " + e.author() + ")");
            return;
        }
        merge(open, dir, drive, repoId, commit, n.authorName());
    }

    /** The commit of a version that just arrived, from what Liskov has seen of the repo's history (null when unknown). */
    private VcsCommit incomingCommit(String repoId, int version) {
        for (VcsCommit c : client().cachedHistory(repoId)) if (c.versionNum == version) return c;
        return null;
    }

    /** Remembers an arrived version as this computer's head of the repo, so the next push names it as its parent. */
    private void accept(DriveMount drive, String repoId, VcsCommit commit, SyncWire.RepoEntry entry) {
        try {
            VcsCommit c = commit;
            if (c == null && entry != null && entry.headCommitId != null) {
                c = new VcsCommit();
                c.commitId = entry.headCommitId;
                c.versionNum = entry.versionNum;
                c.authorName = entry.authorName;
                c.timestampMs = entry.updatedAtMs;
                c.tree = new java.util.LinkedHashMap<>(entry.tree);
            }
            if (c != null) vcs().acceptRemoteCommit(drive.driveId(), repoId, c, true);
        } catch (IOException e) {
            System.out.println("[Vitruvius] cannot record the arrived version: " + e.getMessage());
        }
    }

    /** The scene of a version this computer knows (its project.vproj from Liskov's blob store), or null. */
    private JsonObject sceneOf(DriveMount drive, String repoId, String commitId) {
        if (commitId == null) return null;
        try {
            for (VcsCommit c : vcs().loadCommits(drive.driveId(), repoId)) {
                if (!commitId.equals(c.commitId) || c.tree == null) continue;
                String sha = c.tree.get(ProjectLayout.PROJECT_FILE);
                return sha == null ? null : ShareCore.parse(vcs().getBlob(sha));
            }
        } catch (IOException ignored) {
            // unknown: the stored base is used
        }
        return null;
    }

    /**
     * Another player's version of an open project is in the folder now: merge it with the open state, against the
     * version it was made from (its parent; else the last agreed one), apply the result in place, save it, and push it
     * back when it holds edits of ours the server lacks.
     */
    private void merge(ProjectNode p, Path dir, DriveMount drive, String repoId, VcsCommit commit, String author) {
        JsonObject theirs = ShareCore.read(dir.resolve(ProjectLayout.PROJECT_FILE));
        if (theirs == null) return;
        JsonObject base = commit != null && !commit.parentIds.isEmpty() ? sceneOf(drive, repoId, commit.parentIds.get(0)) : null;
        if (base == null) base = ShareCore.readBase(dir);
        ShareCore.Merge m = ShareCore.incoming(base, ShareCore.json(p), theirs);
        ProjectNode fresh;
        try {
            fresh = ProjectIo.fromJson(m.project().toString());
        } catch (FormatException | RuntimeException e) {
            toast(ToastQueue.Level.ERROR, "The version of " + who(author) + " cannot be read: " + e.getMessage());
            return;
        }
        accept(drive, repoId, commit, serverEntry(repoId));
        awaiting.remove(p.id());   // a newer head than our push: what we had is in the merge, pushed again if the server lacks it
        ShareCore.writeBase(dir, theirs);
        Set<UUID> affected = ProjectApply.apply(p, fresh);
        pm().save(p.id());   // the merged state over the downloaded file
        EditorSession s = EditorSession.get();
        if (s != null) s.invalidateBoxes();
        pending.put(p.id(), m.pushBack() ? Math.max(1, pending(p)) : 0);
        changedAt.put(p.id(), tick - QUIET_TICKS);   // push the merge back without waiting
        if (!affected.isEmpty()) {
            int k = affected.size();
            toast(m.conflicts() > 0 ? ToastQueue.Level.WARN : ToastQueue.Level.INFO,
                    "Merged changes of " + who(author) + " into \"" + p.name() + "\" (" + k + " node" + (k == 1 ? "" : "s")
                            + (m.conflicts() > 0 ? ", " + m.conflicts() + " edited by both" : "") + ")");
        }
    }

    private static String who(String author) {
        return author == null || author.isBlank() ? "another player" : author;
    }

    private static void toast(ToastQueue.Level level, String text) {
        EditorSession s = EditorSession.get();
        if (s != null) s.toast(level, text);
        else System.out.println("[Vitruvius] " + text);
    }
}
