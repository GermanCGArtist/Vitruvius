package dev.vitruvius.editor.sync;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.vitruvius.editor.EditorSession;
import dev.vitruvius.editor.core.Mirror;
import dev.vitruvius.editor.core.Node;
import dev.vitruvius.editor.core.ObjectNode;
import dev.vitruvius.editor.core.ProjectNode;
import dev.vitruvius.editor.core.Rot;
import dev.vitruvius.editor.core.Scene;
import dev.vitruvius.editor.io.FormatException;
import dev.vitruvius.editor.io.ProjectIo;
import dev.vitruvius.editor.io.ProjectLayout;
import dev.vitruvius.editor.mc.LitematicaAdapter;
import dev.vitruvius.editor.mc.PlacementState;
import dev.vitruvius.editor.mc.ProjectManager;
import dev.vitruvius.editor.mc.ReconcileCore;
import dev.vitruvius.editor.sync.SyncPlan.Cell;
import dev.vitruvius.editor.sync.SyncPlan.Pushed;
import dev.vitruvius.editor.ui.MenuModel;
import dev.vitruvius.editor.ui.ToastQueue;
import net.minecraft.client.Minecraft;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Pushing a project to Syncmatica and receiving a shared one (EDITOR-2.0 section 8). Everything runs on the client
 * thread, one tick at a time, except copying the downloaded files of a received project (a daemon worker). Nothing is
 * uploaded unless the player asks: {@link #push} is the only way, and the number {@link #pending()} only counts.
 *
 * <p>Push (per shared project): every visible cell (base cell and array cells of objects, with the derived file where
 * one applies) becomes a Syncmatica placement. Cells whose file or transform changed since the last push are replaced
 * (the new one is shared, then the old id removed), cells that are gone are removed, then the manifest ({@link SyncManifest}) is
 * shared as a 1x1x1 air placement named {@code [LA2] <project>} and the previous manifest is removed. The ids are
 * recorded in the project under {@code extra.syncmatica} ({@link SyncPlan}). Up to {@link #MAX_ACTIVE} uploads run
 * at once. A failure stops the push and keeps what was recorded so far, so Retry continues where it ended.
 *
 * <p>Receive: the manifest is downloaded, decoded, the base file of every object that the manifest lists a placement
 * for is downloaded, and a new project is made in schematics/Projects with those files copied into {@code files/}. The
 * arrays and modifiers come from the scene graph in the manifest; linked assemblies need their .lasm (not shared).
 */
public final class SyncService implements MenuModel.SyncHook {
    public static final int MAX_ACTIVE = 3;
    private static final int STATS_EVERY = 40;

    private static final SyncService INSTANCE = new SyncService();

    public static SyncService get() {
        return INSTANCE;
    }

    public enum State { IDLE, PUSHING, FAILED }

    /** What the toolbar shows. */
    public record Status(State state, int done, int total, String message) {}

    private Status status = new Status(State.IDLE, 0, 0, null);
    private int pending;
    private final Map<UUID, Integer> pendingBy = new HashMap<>();
    private int sharedProjects;
    private int lastStats = -STATS_EVERY;
    private final ArrayDeque<UUID> queue = new ArrayDeque<>();
    private Job job;
    private Receive receive;

    // live sharing
    public static final int POLL_EVERY = 20;
    /** Clock difference between players that a manifest may have and still be merged. */
    public static final long SLACK_MS = 60_000;
    private static final int FILE_WAIT_POLLS = 45;

    /** A manifest of someone else that is on the server: downloaded and read once, kept until it is gone. */
    private static final class RemoteInfo {
        final SyncmaticaBridge.Remote remote;
        SyncManifest.Payload payload;
        boolean ignore;
        int tries;

        RemoteInfo(SyncmaticaBridge.Remote remote) {
            this.remote = remote;
        }
    }

    private final Map<UUID, RemoteInfo> remotes = new HashMap<>();
    /** Downloads this class started, with the tick they started: Syncmatica shows each one in Litematica, and it is hidden again. */
    private final Map<UUID, Integer> hide = new HashMap<>();
    private int nowTick;
    private final Map<UUID, String> lastState = new HashMap<>();
    private final Map<UUID, Integer> lastAutoPush = new HashMap<>();
    /** Ticks between two automatic pushes of one project (a damper against two clients answering each other). */
    private static final int AUTO_COOLDOWN = 100;
    private final Map<UUID, Integer> fileWait = new HashMap<>();
    private final Set<UUID> scanned = new HashSet<>();
    private int lastPoll = -POLL_EVERY;

    private SyncService() {
        MenuModel.setSyncHook(this);
    }

    // ------------------------------------------------------------------------------------------ queries

    @Override
    public boolean installed() {
        return SyncmaticaBridge.installed();
    }

    @Override
    public boolean usable() {
        return SyncmaticaBridge.available();
    }

    @Override
    public boolean connected() {
        return SyncmaticaBridge.connected();
    }

    @Override
    public String reason() {
        if (!installed()) return "Syncmatica is not installed";
        if (!usable()) return SyncmaticaBridge.failure();
        return connected() ? "" : "Not connected to a server with Syncmatica";
    }

    public Status status() {
        return status;
    }

    /** Changes in shared projects that are not pushed yet (cells and manifests). */
    public int pending() {
        return pending;
    }

    public int pending(ProjectNode p) {
        return pendingBy.getOrDefault(p.id(), 0);
    }

    /** How many open projects of this world are shared. */
    public int sharedProjects() {
        return sharedProjects;
    }

    public boolean busy() {
        return job != null || !queue.isEmpty();
    }

    // ------------------------------------------------------------------------------------------ share / stop

    public void share(ProjectNode p) {
        p.setSync(ProjectNode.Sync.SHARED);
        lastStats = -STATS_EVERY;
    }

    /** Stops tracking the project. Its placements stay on the server (remove them in Syncmatica's list). */
    public void stopSharing(ProjectNode p) {
        p.setSync(ProjectNode.Sync.LOCAL);
        lastStats = -STATS_EVERY;
    }

    // ------------------------------------------------------------------------------------------ what is wanted

    private record Desired(List<Cell> cells, JsonObject project, String manifestSig, String stateSig, String contentSig) {}

    private static String canon(PlacementState st) {
        var t = st.transform();
        return t.pos().x() + "," + t.pos().y() + "," + t.pos().z() + "/" + t.rot().steps() + "/" + t.mirror();
    }

    private static long mtime(Path f) {
        try {
            return Files.getLastModifiedTime(f).toMillis();
        } catch (IOException e) {
            return 0;
        }
    }

    private static String sha(String text) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 12; i++) sb.append(String.format("%02x", d[i]));
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private Desired desired(ProjectNode p, ReconcileCore core) {
        LitematicaAdapter ad = LitematicaAdapter.shared();
        Map<Path, Long> mt = new HashMap<>();
        List<Cell> cells = new ArrayList<>();
        // cells another player already holds on the server are theirs to push: two holders would put every copy there twice
        Set<String> foreign = foreignCells(p);
        Map<String, Pushed> mine = SyncPlan.recordedCells(p.extra());
        for (ReconcileCore.CellRef r : core.cellRefs()) {
            if (r.project() != p) continue;
            String id = r.placementId();
            if (id == null) continue;
            String key = r.slot().toString();
            if (foreign.contains(key) && !mine.containsKey(key)) continue;
            PlacementState st = ad.read(id);
            if (st == null || st.file() == null) continue;
            long m = mt.computeIfAbsent(st.file(), SyncService::mtime);
            String sig = st.file().toAbsolutePath().normalize() + "|" + m + "|" + canon(st);
            String name = r.object().name() + (ObjectNode.BASE_CELL.equals(r.cell()) ? "" : " #" + r.cell());
            cells.add(new Cell(r.slot(), name, sig, id));
        }
        JsonObject project = ProjectMerge.withoutLocal(JsonParser.parseString(ProjectIo.toJson(p)).getAsJsonObject());
        project.remove(SyncPlan.KEY);
        List<String> keys = new ArrayList<>();
        for (Cell c : cells) keys.add(c.key());
        keys.sort(null);
        List<String> sigs = new ArrayList<>();
        for (Cell c : cells) sigs.add(c.key() + "=" + c.sig());
        sigs.sort(null);
        return new Desired(cells, project, sha(project + "|" + keys), sha(project + "|" + sigs), sha(project.toString()));
    }

    // ------------------------------------------------------------------------------------------ tick

    /** Once per client tick, after the reconciler: counts what is pending, advances a push or a receive. */
    public void tick(Scene scene, ReconcileCore core, ProjectManager pm, String world, String dim, int tick) {
        if (!SyncmaticaBridge.installed()) return;
        nowTick = tick;
        if (!hide.isEmpty()) hideDownloaded(tick);
        if (tick - lastStats >= STATS_EVERY && job == null) {
            lastStats = tick;
            int total = 0, shared = 0;
            pendingBy.clear();
            for (ProjectNode p : scene.projects()) {
                if (p.sync() != ProjectNode.Sync.SHARED || !p.world().equals(world) || !p.dim().equals(dim)) continue;
                shared++;
                try {
                    Desired d = desired(p, core);
                    SyncPlan.Manifest rec = SyncPlan.recordedManifest(p.extra());
                    if (rec == null && !SyncPlan.applied(p.extra()).isEmpty()) {
                        // received from someone else and not pushed yet: what it holds now is what the server holds
                        SyncPlan.markManifestSynced(p.extra(), d.manifestSig());
                        rec = SyncPlan.recordedManifest(p.extra());
                        if (SyncPlan.content(p.extra()) == null) SyncPlan.setContent(p.extra(), d.contentSig());
                    }
                    int n = SyncPlan.pending(SyncPlan.diff(d.cells(), SyncPlan.recordedCells(p.extra())), d.manifestSig(), rec);
                    pendingBy.put(p.id(), n);
                    total += n;
                    autoPush(p, n, d);
                } catch (RuntimeException e) {
                    // unreadable right now: counted next time
                }
            }
            pending = total;
            sharedProjects = shared;
        }
        try {
            if (job == null && !queue.isEmpty()) startNext(scene, core);
            if (job != null) advance(scene, core, pm);
            if (receive != null) receive.tick(pm);
            if (tick - lastPoll >= POLL_EVERY && job == null && receive == null && sharedProjects > 0) {
                lastPoll = tick;
                poll(scene, core, world, dim);
            }
        } catch (RuntimeException | LinkageError e) {
            fail("Syncmatica: " + e);
        }
    }

    // ------------------------------------------------------------------------------------------ push

    /** Pushes every shared project of the open scene, one after another. */
    public void pushAll(Scene scene) {
        for (ProjectNode p : scene.projects()) if (p.sync() == ProjectNode.Sync.SHARED) push(p);
    }

    /** Queues a project for pushing. Does nothing when Syncmatica is not connected (the toast says why). */
    public void push(ProjectNode p) {
        if (!SyncmaticaBridge.available()) {
            fail(reason());
            return;
        }
        if (!SyncmaticaBridge.connected()) {
            fail("Not connected to a server with Syncmatica");
            return;
        }
        if (p.sync() != ProjectNode.Sync.SHARED) return;
        readRemotes(p, SyncmaticaBridge.manifests(), false);   // so that cells another player holds are not pushed twice
        if (!queue.contains(p.id()) && (job == null || !job.project.id().equals(p.id()))) queue.add(p.id());
        status = new Status(State.PUSHING, 0, 0, null);
    }

    /** Starts the queued pushes again after a failure. */
    public void retry(Scene scene) {
        if (status.state() == State.FAILED) pushAll(scene);
    }

    private final class Job {
        final ProjectNode project;
        final Path dir;
        final SyncPlan.Plan plan;
        final Desired want;
        final ArrayDeque<Cell> todo = new ArrayDeque<>();
        final List<Active> active = new ArrayList<>();
        final Map<String, Pushed> result = new LinkedHashMap<>();
        int total, done;
        boolean manifestStarted;
        final Map<String, Integer> retries = new HashMap<>();
        Active manifest;
        String manifestPlacement;
        String manifestSigNow;

        Job(ProjectNode p, Path dir, SyncPlan.Plan plan, Desired want) {
            this.project = p;
            this.dir = dir;
            this.plan = plan;
            this.want = want;
            todo.addAll(plan.share());
            result.putAll(plan.keep());
            total = plan.share().size() + 1;
        }
    }

    private record Active(Cell cell, SyncmaticaBridge.Handle handle) {}

    private void startNext(Scene scene, ReconcileCore core) {
        UUID id = queue.poll();
        ProjectNode p = id == null ? null : scene.byId(id) instanceof ProjectNode pn ? pn : null;
        if (p == null) return;
        Path dir = core.projectDir(p);
        if (dir == null) {
            fail("The project \"" + p.name() + "\" has no folder");
            return;
        }
        Desired d = desired(p, core);
        SyncPlan.Plan plan = SyncPlan.diff(d.cells(), SyncPlan.recordedCells(p.extra()));
        // the gone cells are removed up front; the recorded ids of changed cells are removed when their replacement is shared
        for (String gone : plan.gone()) removeQuietly(gone);
        job = new Job(p, dir, plan, d);
        job.manifestSigNow = d.manifestSig();
        status = new Status(State.PUSHING, 0, job.total, null);
    }

    private void removeQuietly(String id) {
        try {
            SyncmaticaBridge.remove(UUID.fromString(id));
        } catch (ReflectiveOperationException | RuntimeException e) {
            System.out.println("[Vitruvius] cannot remove " + id + " from Syncmatica: " + e);
        }
    }

    private static String cause(Throwable t) {
        Throwable c = t instanceof InvocationTargetException ite && ite.getCause() != null ? ite.getCause() : t;
        return c.getClass().getSimpleName() + (c.getMessage() == null ? "" : ": " + c.getMessage());
    }

    private void advance(Scene scene, ReconcileCore core, ProjectManager pm) {
        Job j = job;
        if (!SyncmaticaBridge.connected()) {
            record(j, false);
            fail("The Syncmatica connection ended; pushed " + j.done + " of " + j.total + ". Push again to continue");
            return;
        }
        // finished uploads
        for (var it = j.active.iterator(); it.hasNext(); ) {
            Active a = it.next();
            if (!SyncmaticaBridge.finished(a.handle())) continue;
            it.remove();
            if (a == j.manifest) continue;
            if (!SyncmaticaBridge.successful(a.handle())) {
                record(j, false);
                fail("Syncmatica could not share \"" + a.cell().name() + "\". Push again to retry");
                return;
            }
            String old = j.plan.replaced().get(a.cell().key());
            if (old != null) removeQuietly(old);
            j.result.put(a.cell().key(), new Pushed(a.handle().id().toString(), a.cell().sig()));
            j.done++;
            status = new Status(State.PUSHING, j.done, j.total, null);
        }
        // start the next ones
        LitematicaAdapter ad = LitematicaAdapter.shared();
        int polls = j.todo.size();
        while (polls-- > 0 && j.active.size() < MAX_ACTIVE && !j.todo.isEmpty() && !j.manifestStarted) {
            Cell c = j.todo.poll();
            Object placement = ad.placementObject(c.placementId());
            boolean redo = placement == null || SyncmaticaBridge.isSyncmatic(placement);
            if (redo) {
                // a placement Syncmatica tracks already cannot be shared again: drop it, the reconciler puts a fresh one back
                // (same hash id) within a tick or two, and that one is shared. A placement that never comes back is skipped.
                if (placement != null) ad.remove(c.placementId());
                if (j.retries.merge(c.key(), 1, Integer::sum) < 200) j.todo.addLast(c);
                else j.done++;
                continue;
            }
            try {
                j.active.add(new Active(c, SyncmaticaBridge.share(placement)));
            } catch (ReflectiveOperationException | RuntimeException e) {
                record(j, false);
                fail("Syncmatica could not share \"" + c.name() + "\": " + cause(e));
                return;
            }
        }
        if (!j.todo.isEmpty() || !j.active.isEmpty() && !j.manifestStarted) return;
        // all cells are up: the manifest
        if (!j.manifestStarted) {
            j.manifestStarted = true;
            startManifest(j, ad, core);
            return;
        }
        if (j.manifest == null || !SyncmaticaBridge.finished(j.manifest.handle())) return;
        if (!SyncmaticaBridge.successful(j.manifest.handle())) {
            record(j, false);
            fail("Syncmatica could not share the manifest. Push again to retry");
            return;
        }
        // the old manifest goes, the new one is recorded
        SyncPlan.Manifest old = SyncPlan.recordedManifest(j.project.extra());
        if (old != null) {
            if (!old.id().isEmpty()) removeQuietly(old.id());
            if (!old.placement().isEmpty() && !old.placement().equals(j.manifestPlacement)) LitematicaAdapter.shared().remove(old.placement());
        }
        j.done++;
        SyncPlan.write(j.project.extra(), j.result, new SyncPlan.Manifest(j.manifest.handle().id().toString(), j.manifestSigNow, j.manifestPlacement),
                System.currentTimeMillis());
        writeBase(j.dir, j.want.project());
        SyncPlan.setContent(j.project.extra(), j.want.contentSig());
        int shared = j.result.size();
        job = null;
        lastStats = -STATS_EVERY;
        status = new Status(queue.isEmpty() ? State.IDLE : State.PUSHING, 0, 0, null);
        toast(ToastQueue.Level.INFO, "Pushed \"" + j.project.name() + "\" to Syncmatica (" + shared + " placement" + (shared == 1 ? "" : "s") + " and the manifest)");
    }

    private void startManifest(Job j, LitematicaAdapter ad, ReconcileCore core) {
        Map<String, String> ids = new LinkedHashMap<>();
        for (var e : j.result.entrySet()) ids.put(e.getKey(), e.getValue().id());
        String description = SyncManifest.encode(SyncManifest.payloadJson(j.want.project(), ids, System.currentTimeMillis(), Minecraft.getInstance().getUser().getName()));
        if (!SyncManifest.fits(description)) {
            record(j, false);
            fail("The project is too big for one manifest (" + description.length() + " characters; the limit is " + SyncManifest.MAX_DESCRIPTION
                    + "). The placements are shared, the manifest is not");
            return;
        }
        Path dir = j.dir.resolve(".cache").resolve("sync");
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            record(j, false);
            fail("Cannot write the manifest: " + e.getMessage());
            return;
        }
        String stem = "manifest_" + sha(description).substring(0, 10);
        Path file = ManifestFile.write(dir, stem, j.project.name(), description, Minecraft.getInstance().getUser().getName());
        if (file == null) {
            record(j, false);
            fail("Litematica could not write the manifest file");
            return;
        }
        var origin = j.project.origin();
        String placement = ad.create(file, origin, Rot.NONE, Mirror.NONE, SyncManifest.nameFor(j.project.name()), true, false);
        Object obj = placement == null ? null : ad.placementObject(placement);
        if (obj == null) {
            record(j, false);
            fail("Litematica could not place the manifest");
            return;
        }
        try {
            j.manifest = new Active(null, SyncmaticaBridge.share(obj));
            j.active.add(j.manifest);
            j.manifestPlacement = placement;
        } catch (ReflectiveOperationException | RuntimeException e) {
            record(j, false);
            fail("Syncmatica could not share the manifest: " + cause(e));
        }
    }

    /** Writes what was pushed so far into the project, keeping the old manifest record (so the next push redoes it). */
    private void record(Job j, boolean withManifest) {
        SyncPlan.Manifest m = SyncPlan.recordedManifest(j.project.extra());
        SyncPlan.write(j.project.extra(), j.result, m, System.currentTimeMillis());
    }

    private void fail(String text) {
        job = null;
        queue.clear();
        status = new Status(State.FAILED, 0, 0, text);
        lastStats = -STATS_EVERY;
        toast(ToastQueue.Level.ERROR, text);
    }

    private void toast(ToastQueue.Level level, String text) {
        EditorSession s = EditorSession.get();
        if (s != null) s.toasts.add(level, text);
    }

    // ------------------------------------------------------------------------------------------ live sharing

    /** Whether edits of this project are pushed by themselves. */
    public boolean autoPush(ProjectNode p) {
        return SyncPlan.auto(p.extra());
    }

    public void setAutoPush(ProjectNode p, boolean on) {
        SyncPlan.setAuto(p.extra(), on);
    }

    /** Pushes a project once its state has been the same for two looks (about two seconds) and the changes of the others are in. */
    private void autoPush(ProjectNode p, int pendingCount, Desired d) {
        if (pendingCount <= 0) {
            lastState.remove(p.id());
            return;
        }
        String prev = lastState.put(p.id(), d.stateSig());
        if (!d.stateSig().equals(prev)) return;
        if (!SyncPlan.auto(p.extra()) || status.state() == State.FAILED || busy() || receive != null) return;
        if (!scanned.contains(p.id()) || !unapplied(p).isEmpty()) return;
        // only a real edit starts a push: the scene differs from what this computer last sent or merged, or one of the cells
        // it holds changed. Bookkeeping that differs (file times, which cells exist) must not, or two players keep answering
        // each other's manifests forever.
        int cellChanges = SyncPlan.diff(d.cells(), SyncPlan.recordedCells(p.extra())).changes();
        if (cellChanges == 0 && d.contentSig().equals(SyncPlan.content(p.extra()))) {
            SyncPlan.markManifestSynced(p.extra(), d.manifestSig());
            lastState.remove(p.id());
            return;
        }
        if (nowTick - lastAutoPush.getOrDefault(p.id(), -AUTO_COOLDOWN) < AUTO_COOLDOWN) return;
        lastAutoPush.put(p.id(), nowTick);
        if (!SyncmaticaBridge.available() || !SyncmaticaBridge.connected()) return;
        push(p);
        lastState.remove(p.id());
    }

    private static Path baseFile(Path dir) {
        return dir.resolve(".cache").resolve("sync").resolve("base.json");
    }

    /** The project as it was when this computer last agreed with the server (the common ancestor of a merge). */
    static void writeBase(Path dir, JsonObject project) {
        try {
            Path f = baseFile(dir);
            Files.createDirectories(f.getParent());
            Files.writeString(f, project.toString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            System.out.println("[Vitruvius] cannot write the sync base: " + e.getMessage());
        }
    }

    static JsonObject readBase(Path dir) {
        try {
            Path f = baseFile(dir);
            if (!Files.isRegularFile(f)) return null;
            return JsonParser.parseString(Files.readString(f, StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /** Starts a download whose placement is only read or copied: it is hidden in Litematica when Syncmatica has shown it. */
    private boolean download(SyncmaticaBridge.Remote r) {
        boolean started = SyncmaticaBridge.startDownload(r);
        if (started) hide.put(r.id(), nowTick);
        return started;
    }

    private void hideDownloaded(int tick) {
        for (var it = hide.entrySet().iterator(); it.hasNext(); ) {
            var e = it.next();
            SyncmaticaBridge.Remote r = SyncmaticaBridge.find(e.getKey());
            if (r == null || tick - e.getValue() > 1200) {
                it.remove();
            } else if (SyncmaticaBridge.rendered(r)) {
                SyncmaticaBridge.unrender(r);
                it.remove();
            }
        }
    }

    private static boolean readManifest(RemoteInfo ri) {
        Path f = SyncmaticaBridge.localFile(ri.remote);
        if (f == null) return false;
        try {
            ri.payload = SyncManifest.parse(SyncManifest.decode(ManifestFile.readDescription(f)));
        } catch (IllegalArgumentException | NullPointerException e) {
            ri.ignore = true;
        }
        return true;
    }

    /** Reads the manifests of other players that are named like this project (downloading them when asked). */
    private void readRemotes(ProjectNode p, List<SyncmaticaBridge.Remote> all, boolean download) {
        SyncPlan.Manifest own = SyncPlan.recordedManifest(p.extra());
        String ownId = own == null ? "" : own.id();
        String name = SyncManifest.nameFor(p.name());
        for (SyncmaticaBridge.Remote m : all) {
            if (m.id().toString().equals(ownId) || !name.equals(m.name())) continue;
            RemoteInfo ri = remotes.computeIfAbsent(m.id(), id -> new RemoteInfo(m));
            if (ri.ignore || ri.payload != null) continue;
            if (!readManifest(ri) && download && ri.tries++ % 3 == 0) download(m);
        }
    }

    private List<RemoteInfo> matching(ProjectNode p) {
        String id = p.id().toString();
        List<RemoteInfo> out = new ArrayList<>();
        for (RemoteInfo ri : remotes.values()) if (ri.payload != null && ri.payload.projectId().equals(id)) out.add(ri);
        out.sort(java.util.Comparator.comparingLong((RemoteInfo r) -> r.payload.at()).reversed());
        return out;
    }

    /** The manifests of others that this project has not merged yet and that are not older than what it already holds. */
    private List<RemoteInfo> unapplied(ProjectNode p) {
        Set<String> done = SyncPlan.applied(p.extra());
        long floor = SyncPlan.syncedAt(p.extra()) - SLACK_MS;
        List<RemoteInfo> out = new ArrayList<>();
        for (RemoteInfo ri : matching(p)) {
            if (done.contains(ri.remote.id().toString()) || ri.payload.at() <= floor) continue;
            out.add(ri);
        }
        out.sort(java.util.Comparator.comparingLong((RemoteInfo r) -> r.payload.at()));
        return out;
    }

    /** Cell keys that another player's manifest lists with a placement that is still on the server. */
    private Set<String> foreignCells(ProjectNode p) {
        Set<String> out = new HashSet<>();
        List<RemoteInfo> ms = matching(p);
        if (ms.isEmpty() || !SyncmaticaBridge.available()) return out;
        Set<String> alive = new HashSet<>();
        for (SyncmaticaBridge.Remote r : SyncmaticaBridge.list()) alive.add(r.id().toString());
        for (RemoteInfo ri : ms) {
            for (var e : ri.payload.cells().entrySet()) if (alive.contains(e.getValue())) out.add(e.getKey());
        }
        return out;
    }

    /** A placement on the server that holds the file of this object, from any manifest of the project (newest first). */
    private SyncmaticaBridge.Remote sourceOf(ProjectNode p, String objectId) {
        for (RemoteInfo ri : matching(p)) {
            String id = ri.payload.cells().get(objectId);
            if (id == null) continue;
            try {
                SyncmaticaBridge.Remote r = SyncmaticaBridge.find(UUID.fromString(id));
                if (r != null) return r;
            } catch (IllegalArgumentException ignored) {
                // a bad id: the next manifest may know the object
            }
        }
        return null;
    }

    /** Once per second: reads the manifests of the others for every shared project and merges the next unmerged one. */
    private void poll(Scene scene, ReconcileCore core, String world, String dim) {
        if (!SyncmaticaBridge.connected()) return;
        List<SyncmaticaBridge.Remote> all = SyncmaticaBridge.manifests();
        Set<UUID> alive = new HashSet<>();
        for (SyncmaticaBridge.Remote m : all) alive.add(m.id());
        remotes.keySet().retainAll(alive);
        for (ProjectNode p : scene.projects()) {
            if (p.sync() != ProjectNode.Sync.SHARED || !p.world().equals(world) || !p.dim().equals(dim)) continue;
            Path dir = core.projectDir(p);
            if (dir == null) continue;
            readRemotes(p, all, true);
            scanned.add(p.id());
            List<RemoteInfo> todo = unapplied(p);
            if (!todo.isEmpty()) {
                try {
                    applyRemote(p, dir, todo.get(0), core);
                } catch (RuntimeException e) {
                    SyncPlan.markApplied(p.extra(), todo.get(0).remote.id().toString(), 0);
                    toast(ToastQueue.Level.ERROR, "Could not merge changes of \"" + p.name() + "\": " + e);
                }
            }
        }
    }

    /**
     * Merges one manifest of another player into the open project: files that are new are fetched first (a few polls at
     * most), then the project is merged three ways with the state both sides last agreed on, and the live nodes are
     * updated in place. The reconciler then moves the placements. Not an undo step: undoing someone else's change here
     * would only be pushed back to them.
     */
    private void applyRemote(ProjectNode p, Path dir, RemoteInfo ri, ReconcileCore core) {
        SyncManifest.Payload pl = ri.payload;
        JsonObject ours = JsonParser.parseString(ProjectIo.toJson(p)).getAsJsonObject();   // with this player's visibility
        JsonObject base = readBase(dir);
        if (base == null) {
            base = new JsonObject();
            base.addProperty("id", p.id().toString());
            base.add("nodes", new com.google.gson.JsonArray());
        }
        ProjectMerge.Result r = ProjectMerge.merge(base, ours, pl.project());
        ProjectNode fresh;
        try {
            fresh = ProjectIo.fromJson(r.project().toString());
        } catch (FormatException e) {
            SyncPlan.markApplied(p.extra(), ri.remote.id().toString(), 0);
            toast(ToastQueue.Level.ERROR, "The changes of " + who(pl) + " cannot be read: " + e.getMessage());
            return;
        }
        boolean waiting = false;
        int fetched = 0;
        for (Node n : fresh.subtree()) {
            if (!(n instanceof ObjectNode o) || o.file().isEmpty()) continue;
            Path target;
            try {
                target = ProjectLayout.resolveInside(dir, o.file());
            } catch (IllegalArgumentException e) {
                continue;
            }
            if (Files.exists(target)) continue;
            SyncmaticaBridge.Remote src = sourceOf(p, o.id().toString());
            if (src == null) continue;
            Path local = SyncmaticaBridge.localFile(src);
            if (local == null) {
                download(src);
                waiting = true;
                continue;
            }
            try {
                Files.createDirectories(target.getParent());
                Files.copy(local, target, StandardCopyOption.COPY_ATTRIBUTES);
                fetched++;
            } catch (IOException e) {
                System.out.println("[Vitruvius] cannot copy " + local + ": " + e.getMessage());
            }
        }
        if (waiting && fileWait.merge(p.id(), 1, Integer::sum) < FILE_WAIT_POLLS) return;
        fileWait.remove(p.id());

        Set<UUID> affected = ProjectApply.apply(p, fresh);
        SyncPlan.markApplied(p.extra(), ri.remote.id().toString(), pl.at());
        writeBase(dir, r.project());
        if (ProjectMerge.equivalent(r.project(), pl.project())) {
            // nothing of ours is missing on the server: no push is due
            Desired now = desired(p, core);
            SyncPlan.markManifestSynced(p.extra(), now.manifestSig());
            SyncPlan.setContent(p.extra(), now.contentSig());
        }
        EditorSession s = EditorSession.get();
        if (s != null) s.invalidateBoxes();
        lastStats = -STATS_EVERY;
        if (!affected.isEmpty() || fetched > 0) {
            int n = affected.size();
            toast(r.conflicts() > 0 ? ToastQueue.Level.WARN : ToastQueue.Level.INFO,
                    "Merged changes of " + who(pl) + " into \"" + p.name() + "\" (" + n + " node" + (n == 1 ? "" : "s")
                            + (r.conflicts() > 0 ? ", " + r.conflicts() + " edited by both" : "") + ")");
        }
    }

    private static String who(SyncManifest.Payload pl) {
        return pl.by().isEmpty() ? "another player" : pl.by();
    }

    // ------------------------------------------------------------------------------------------ receive

    /** The manifests the server lists, for the "Shared projects" part of the Open project picker. */
    public List<SyncmaticaBridge.Remote> sharedProjectList() {
        if (!SyncmaticaBridge.available()) return List.of();
        Set<String> open = new HashSet<>();
        EditorSession s = EditorSession.get();
        if (s != null) for (ProjectNode p : s.scene().projects()) if (p.sync() == ProjectNode.Sync.SHARED) open.add(SyncManifest.nameFor(p.name()));
        // every player who pushed has a manifest of the same name: one entry per name (opening takes the newest)
        Map<String, SyncmaticaBridge.Remote> byName = new LinkedHashMap<>();
        for (SyncmaticaBridge.Remote r : SyncmaticaBridge.manifests()) if (!open.contains(r.name())) byName.putIfAbsent(r.name(), r);
        return new ArrayList<>(byName.values());
    }

    public boolean receiving() {
        return receive != null;
    }

    public String receiveStatus() {
        return receive == null ? null : receive.text;
    }

    /** Opens a shared project: downloads its manifest and files, then makes a project from them. */
    public void receive(SyncmaticaBridge.Remote manifest, Path schematicsDir) {
        if (receive != null) {
            toast(ToastQueue.Level.WARN, "A shared project is still being received");
            return;
        }
        receive = new Receive(manifest, schematicsDir);
    }

    private final class Receive {
        final SyncmaticaBridge.Remote manifest;
        final Path schematicsDir;
        String text = "Downloading the manifest…";
        int waited;
        SyncManifest.Payload payload;
        ProjectNode tree;
        final List<SyncmaticaBridge.Remote> need = new ArrayList<>();
        final Map<String, SyncmaticaBridge.Remote> byObject = new HashMap<>();
        boolean building;
        int missing;
        /** The other manifests named like this one: several players may have pushed the same project. */
        List<RemoteInfo> sibs;
        boolean resolved;
        int sibWait;
        final List<SyncManifest.Payload> sources = new ArrayList<>();
        final List<SyncmaticaBridge.Remote> mark = new ArrayList<>();

        Receive(SyncmaticaBridge.Remote manifest, Path schematicsDir) {
            this.manifest = manifest;
            this.schematicsDir = schematicsDir;
        }

        void finish(String message, ToastQueue.Level level) {
            receive = null;
            toast(level, message);
        }

        void tick(ProjectManager pm) {
            if (building) return;
            if (!SyncmaticaBridge.connected()) {
                finish("The Syncmatica connection ended before the project arrived", ToastQueue.Level.ERROR);
                return;
            }
            if (payload == null) {
                Path f = SyncmaticaBridge.localFile(manifest);
                if (f == null) {
                    if (waited++ % 60 == 0) download(manifest);
                    if (waited > 1200) finish("Downloading the manifest took too long", ToastQueue.Level.ERROR);
                    return;
                }
                try {
                    String desc = ManifestFile.readDescription(f);
                    payload = SyncManifest.parse(SyncManifest.decode(desc));
                    tree = ProjectIo.fromJson(payload.project().toString());
                } catch (IllegalArgumentException | dev.vitruvius.editor.io.FormatException e) {
                    finish("The shared manifest cannot be read: " + e.getMessage(), ToastQueue.Level.ERROR);
                    return;
                }
                waited = 0;
            }
            if (!resolved) {
                if (sibs == null) {
                    sibs = new ArrayList<>();
                    for (SyncmaticaBridge.Remote r : SyncmaticaBridge.manifests()) {
                        if (!r.id().equals(manifest.id()) && r.name().equals(manifest.name())) sibs.add(new RemoteInfo(r));
                    }
                }
                boolean ready = true;
                for (RemoteInfo s : sibs) {
                    if (s.payload != null || s.ignore) continue;
                    if (!readManifest(s)) {
                        ready = false;
                        if (sibWait % 30 == 0) download(s.remote);
                    }
                }
                text = "Reading the manifests of the other players…";
                if (!ready && ++sibWait < 600) return;
                // the newest manifest of this project is the one to open; the cells of all of them tell where the files are
                String pid = payload.projectId();
                SyncManifest.Payload newest = payload;
                sources.add(payload);
                mark.add(manifest);
                for (RemoteInfo s : sibs) {
                    if (s.payload == null || !s.payload.projectId().equals(pid)) continue;
                    sources.add(s.payload);
                    mark.add(s.remote);
                    if (s.payload.at() > newest.at()) newest = s.payload;
                }
                if (newest != payload) {
                    try {
                        tree = ProjectIo.fromJson(newest.project().toString());
                        payload = newest;
                    } catch (FormatException e) {
                        // keep the one that was clicked
                    }
                }
                sources.sort(java.util.Comparator.comparingLong((SyncManifest.Payload x) -> x.at()).reversed());
                for (Node n : tree.subtree()) {
                    if (!(n instanceof ObjectNode o)) continue;
                    SyncmaticaBridge.Remote r = null;
                    for (SyncManifest.Payload src : sources) {
                        String id = src.cells().get(o.id().toString());
                        if (id == null) continue;
                        try {
                            r = SyncmaticaBridge.find(UUID.fromString(id));
                        } catch (IllegalArgumentException ignored) {
                            // a bad id: the next manifest may know it
                        }
                        if (r != null) break;
                    }
                    if (r == null) missing++;
                    else {
                        need.add(r);
                        byObject.put(o.id().toString(), r);
                    }
                }
                resolved = true;
                waited = 0;
            }
            int ready = 0, loading = 0;
            for (SyncmaticaBridge.Remote r : need) {
                if (SyncmaticaBridge.localFile(r) != null) ready++;
            }
            text = "Downloading " + ready + "/" + need.size() + " files…";
            if (ready < need.size()) {
                if (waited++ % 30 == 0) {
                    for (SyncmaticaBridge.Remote r : need) {
                        if (loading >= MAX_ACTIVE) break;
                        if (SyncmaticaBridge.localFile(r) == null) {
                            download(r);
                            loading++;
                        }
                    }
                }
                if (waited > 6000) finish("Downloading the shared files took too long", ToastQueue.Level.ERROR);
                return;
            }
            building = true;
            text = "Building the project…";
            ExecutorService ex = WORKER;
            ex.execute(() -> {
                try {
                    Path dir = build();
                    Minecraft.getInstance().execute(() -> opened(pm, dir));
                } catch (IOException | RuntimeException e) {
                    Minecraft.getInstance().execute(() -> finish("Cannot build the shared project: " + e.getMessage(), ToastQueue.Level.ERROR));
                }
            });
        }

        /** Copies the downloaded files into a new project folder and writes its project.json (worker thread). */
        private Path build() throws IOException {
            Path root = ProjectLayout.projectsRoot(schematicsDir);
            String folder = ProjectLayout.uniqueName(tree.name(), n -> Files.exists(root.resolve(n)));
            Path dir = root.resolve(folder).toAbsolutePath().normalize();
            Path files = ProjectLayout.filesDir(dir);
            Files.createDirectories(files);
            for (Node n : tree.subtree()) {
                if (!(n instanceof ObjectNode o)) continue;
                SyncmaticaBridge.Remote r = byObject.get(o.id().toString());
                if (r == null) continue;
                Path src = SyncmaticaBridge.localFile(r);
                if (src == null) continue;
                // the same relative path as in the manifest: the file name is part of the object, and a different one on this
                // computer would look like an edit of the object to everybody else
                Path same = null;
                try {
                    same = o.file().isEmpty() ? null : ProjectLayout.resolveInside(dir, o.file());
                } catch (IllegalArgumentException ignored) {
                    // an unusable path: a name is made up below
                }
                if (same != null && !Files.exists(same)) {
                    Files.createDirectories(same.getParent());
                    Files.copy(src, same, StandardCopyOption.COPY_ATTRIBUTES);
                    o.setPlacementIds(Map.of());
                    continue;
                }
                if (same != null) {
                    o.setPlacementIds(Map.of());
                    continue;
                }
                String stem = ProjectLayout.safeName(stripExt(src.getFileName().toString()));
                String name = stem + ProjectLayout.SCHEMATIC_EXT;
                for (int i = 2; Files.exists(files.resolve(name)); i++) name = stem + "_" + i + ProjectLayout.SCHEMATIC_EXT;
                Files.copy(src, files.resolve(name), StandardCopyOption.COPY_ATTRIBUTES);
                o.setFile(ProjectLayout.FILES_DIR + "/" + name);
                o.setPlacementIds(Map.of());
            }
            tree.setPath(dir.toString());
            tree.setWorld("");                       // bound to this world when it is opened
            tree.setSync(ProjectNode.Sync.SHARED);          // it stays linked: the next changes of the others come in by themselves
            tree.extra().remove(SyncPlan.KEY);
            for (SyncmaticaBridge.Remote m : mark) SyncPlan.markApplied(tree.extra(), m.id().toString(), payload.at());
            ProjectIo.write(tree, ProjectLayout.projectFile(dir));
            writeBase(dir, payload.project());
            return dir;
        }

        void opened(ProjectManager pm, Path dir) {
            try {
                ProjectNode p = pm.open(dir);
                EditorSession s = EditorSession.get();
                if (s != null) s.selection.set(p.id());
                String warn = missing > 0 ? " (" + missing + " object" + (missing == 1 ? "" : "s") + " had no placement on the server)" : "";
                finish("Opened the shared project \"" + p.name() + "\"" + warn, missing > 0 ? ToastQueue.Level.WARN : ToastQueue.Level.INFO);
            } catch (IOException | RuntimeException e) {
                finish("Cannot open the shared project: " + e.getMessage(), ToastQueue.Level.ERROR);
            }
        }
    }

    private static String stripExt(String n) {
        int i = n.lastIndexOf('.');
        return i > 0 ? n.substring(0, i) : n;
    }

    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "vitruvius-syncmatica");
        t.setDaemon(true);
        return t;
    });
}
