package dev.schemarray.editor.sync;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.schemarray.editor.EditorSession;
import dev.schemarray.editor.core.Mirror;
import dev.schemarray.editor.core.Node;
import dev.schemarray.editor.core.ObjectNode;
import dev.schemarray.editor.core.ProjectNode;
import dev.schemarray.editor.core.Rot;
import dev.schemarray.editor.core.Scene;
import dev.schemarray.editor.io.ProjectIo;
import dev.schemarray.editor.io.ProjectLayout;
import dev.schemarray.editor.mc.LitematicaAdapter;
import dev.schemarray.editor.mc.PlacementState;
import dev.schemarray.editor.mc.ProjectManager;
import dev.schemarray.editor.mc.ReconcileCore;
import dev.schemarray.editor.sync.SyncPlan.Cell;
import dev.schemarray.editor.sync.SyncPlan.Pushed;
import dev.schemarray.editor.ui.MenuModel;
import dev.schemarray.editor.ui.ToastQueue;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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

    private record Desired(List<Cell> cells, JsonObject project, String manifestSig) {}

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
        for (ReconcileCore.CellRef r : core.cellRefs()) {
            if (r.project() != p) continue;
            String id = r.placementId();
            if (id == null) continue;
            PlacementState st = ad.read(id);
            if (st == null || st.file() == null) continue;
            long m = mt.computeIfAbsent(st.file(), SyncService::mtime);
            String sig = st.file().toAbsolutePath().normalize() + "|" + m + "|" + canon(st);
            String name = r.object().name() + (ObjectNode.BASE_CELL.equals(r.cell()) ? "" : " #" + r.cell());
            cells.add(new Cell(r.slot(), name, sig, id));
        }
        JsonObject project = JsonParser.parseString(ProjectIo.toJson(p)).getAsJsonObject();
        project.remove(SyncPlan.KEY);
        List<String> keys = new ArrayList<>();
        for (Cell c : cells) keys.add(c.key());
        keys.sort(null);
        return new Desired(cells, project, sha(project + "|" + keys));
    }

    // ------------------------------------------------------------------------------------------ tick

    /** Once per client tick, after the reconciler: counts what is pending, advances a push or a receive. */
    public void tick(Scene scene, ReconcileCore core, ProjectManager pm, String world, String dim, int tick) {
        if (!SyncmaticaBridge.installed()) return;
        if (tick - lastStats >= STATS_EVERY && job == null) {
            lastStats = tick;
            int total = 0, shared = 0;
            pendingBy.clear();
            for (ProjectNode p : scene.projects()) {
                if (p.sync() != ProjectNode.Sync.SHARED || !p.world().equals(world) || !p.dim().equals(dim)) continue;
                shared++;
                try {
                    Desired d = desired(p, core);
                    int n = SyncPlan.pending(SyncPlan.diff(d.cells(), SyncPlan.recordedCells(p.extra())), d.manifestSig(), SyncPlan.recordedManifest(p.extra()));
                    pendingBy.put(p.id(), n);
                    total += n;
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
            System.out.println("[SchematicArray] cannot remove " + id + " from Syncmatica: " + e);
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
            removeQuietly(old.id());
            if (!old.placement().isEmpty() && !old.placement().equals(j.manifestPlacement)) LitematicaAdapter.shared().remove(old.placement());
        }
        j.done++;
        SyncPlan.write(j.project.extra(), j.result, new SyncPlan.Manifest(j.manifest.handle().id().toString(), j.manifestSigNow, j.manifestPlacement),
                System.currentTimeMillis());
        int shared = j.result.size();
        job = null;
        lastStats = -STATS_EVERY;
        status = new Status(queue.isEmpty() ? State.IDLE : State.PUSHING, 0, 0, null);
        toast(ToastQueue.Level.INFO, "Pushed \"" + j.project.name() + "\" to Syncmatica (" + shared + " placement" + (shared == 1 ? "" : "s") + " and the manifest)");
    }

    private void startManifest(Job j, LitematicaAdapter ad, ReconcileCore core) {
        Map<String, String> ids = new LinkedHashMap<>();
        for (var e : j.result.entrySet()) ids.put(e.getKey(), e.getValue().id());
        String description = SyncManifest.encode(SyncManifest.payloadJson(j.want.project(), ids));
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

    // ------------------------------------------------------------------------------------------ receive

    /** The manifests the server lists, for the "Shared projects" part of the Open project picker. */
    public List<SyncmaticaBridge.Remote> sharedProjectList() {
        if (!SyncmaticaBridge.available()) return List.of();
        return SyncmaticaBridge.manifests();
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
                    if (waited++ % 60 == 0) SyncmaticaBridge.startDownload(manifest);
                    if (waited > 1200) finish("Downloading the manifest took too long", ToastQueue.Level.ERROR);
                    return;
                }
                try {
                    String desc = ManifestFile.readDescription(f);
                    payload = SyncManifest.parse(SyncManifest.decode(desc));
                    tree = ProjectIo.fromJson(payload.project().toString());
                } catch (IllegalArgumentException | dev.schemarray.editor.io.FormatException e) {
                    finish("The shared manifest cannot be read: " + e.getMessage(), ToastQueue.Level.ERROR);
                    return;
                }
                for (Node n : tree.subtree()) {
                    if (!(n instanceof ObjectNode o)) continue;
                    String id = payload.cells().get(o.id().toString());
                    SyncmaticaBridge.Remote r = null;
                    if (id != null) {
                        try {
                            r = SyncmaticaBridge.find(UUID.fromString(id));
                        } catch (IllegalArgumentException ignored) {
                            // a bad id: counted as missing
                        }
                    }
                    if (r == null) missing++;
                    else {
                        need.add(r);
                        byObject.put(o.id().toString(), r);
                    }
                }
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
                            SyncmaticaBridge.startDownload(r);
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
                String stem = ProjectLayout.safeName(stripExt(src.getFileName().toString()));
                String name = stem + ProjectLayout.SCHEMATIC_EXT;
                for (int i = 2; Files.exists(files.resolve(name)); i++) name = stem + "_" + i + ProjectLayout.SCHEMATIC_EXT;
                Files.copy(src, files.resolve(name), StandardCopyOption.COPY_ATTRIBUTES);
                o.setFile(ProjectLayout.FILES_DIR + "/" + name);
                o.setPlacementIds(Map.of());
            }
            tree.setPath(dir.toString());
            tree.setWorld("");                       // bound to this world when it is opened
            tree.setSync(ProjectNode.Sync.LOCAL);
            tree.extra().remove(SyncPlan.KEY);
            ProjectIo.write(tree, ProjectLayout.projectFile(dir));
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
        Thread t = new Thread(r, "schemarray-syncmatica");
        t.setDaemon(true);
        return t;
    });
}
