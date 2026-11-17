package dev.schemarray.editor.mc;

import dev.schemarray.editor.core.AssemblyBounds;
import dev.schemarray.editor.core.AssemblyNode;
import dev.schemarray.editor.core.Box;
import dev.schemarray.editor.core.CellMasks;
import dev.schemarray.editor.core.MaskPlan;
import dev.schemarray.editor.core.ObjectNode;
import dev.schemarray.editor.core.ProjectNode;
import dev.schemarray.editor.core.Scene;
import dev.schemarray.editor.core.Vec3i;
import dev.schemarray.editor.io.ProjectLayout;
import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import fi.dy.masa.litematica.schematic.container.LitematicaBlockStateContainer;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

import java.io.IOException;
import java.lang.ref.SoftReference;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * Derived files (EDITOR-2.0 section 5): for every placed copy of an object whose {@link MaskPlan} is not empty, a
 * private copy of its schematic with the masked blocks set to air, written to
 * {@code .cache/derived/<objectId>-<maskHash>.litematic}.
 *
 * <p>Every copy has its own mask (its world position differs): the base cell and the array cells of project objects,
 * Loose objects (their cache lives in {@link ReconcileCore#looseCache()}) and the parts of linked assemblies. Copies
 * whose mask hash is the same share one file; one object gets at most {@link CellMasks#MAX_FILES} files per pass, the
 * copies beyond that keep the original file and {@link #capWarning()} says so.
 *
 * <p>Threads: {@link #tick} plans on the client thread (cheap: sizes, boxes, modification times and hashes); the
 * files are loaded, masked and written on one daemon worker. A plan must stay unchanged for {@link #DEBOUNCE}
 * ticks and no gizmo drag or undo transaction may run ({@link #setBusy}) before a job starts; a newer plan of the
 * same copy replaces a queued one. A cache hit (the file exists) starts nothing but a count-only pass for the
 * numbers shown in the UI. When a job ends, {@link #fileFor} returns the new file and the reconciler swaps the cell
 * on its next tick through its wrong-file path. Nothing is ever deleted: {@link #cleanup} moves unreferenced
 * files to {@code .trash}.
 */
public final class DerivedFiles implements DerivedPort {
    /** Ticks a plan has to stay the same before its job starts (planning runs every {@link #PLAN_EVERY} ticks). */
    public static final int DEBOUNCE = 10;
    public static final int PLAN_EVERY = 5;

    public enum Status { BUILDING, READY, FAILED }

    /** What the UI shows about one object (its base cell). Counts are -1 until known. */
    public record Info(Status status, String error, long removed, long booleanRemoved, Map<UUID, Long> trimBy) {}

    /** The outcome of an on-demand build (Apply, export). */
    public record Build(Path file, long removed, String error) {
        public boolean ok() {
            return error == null;
        }
    }

    private static final DerivedFiles SHARED = new DerivedFiles();

    public static DerivedFiles shared() {
        return SHARED;
    }

    /** One derived file (shared by every copy with the same object and mask hash). */
    private static final class FileEntry {
        final UUID objectId;
        MaskPlan.Plan plan;
        int since;
        boolean launched;
        boolean wantCount;       // the base cell of a model object: the UI wants the numbers
        Path projectDir;
        Path source;
        Path file;               // set once the file exists
        Status status = Status.BUILDING;
        String error;
        long removed = -1, booleanRemoved = -1;
        Map<UUID, Long> trimBy = Map.of();

        FileEntry(UUID objectId) {
            this.objectId = objectId;
        }
    }

    /** What one placed copy wants and shows (it keeps the older file while a newer one builds). */
    private static final class SlotState {
        String wantedKey;
        Path shown;
    }

    private final Map<String, FileEntry> files = new HashMap<>();
    private final Map<UUID, SlotState> slots = new HashMap<>();
    private final Set<UUID> plannedProjects = new HashSet<>();
    private Map<UUID, Integer> capped = Map.of();
    private BooleanSupplier busy = () -> false;
    private ReconcileCore core;
    private int lastPlan = Integer.MIN_VALUE;

    private DerivedFiles() {}

    /** The reconciler to take sizes and file rules from. */
    public void bind(ReconcileCore core) {
        this.core = core;
    }

    /** True while an edit is in flight (a gizmo drag, an open undo transaction): no job starts. */
    public void setBusy(BooleanSupplier busy) {
        this.busy = busy == null ? () -> false : busy;
    }

    /** Forgets everything (new world). Files stay in the cache. */
    public void reset() {
        files.clear();
        slots.clear();
        plannedProjects.clear();
        capped = Map.of();
        synchronized (lock) {
            queue.clear();
        }
    }

    // ------------------------------------------------------------------------------------------ client thread

    @Override
    public Path fileFor(UUID slot) {
        SlotState st = slots.get(slot);
        return st == null ? null : st.shown;
    }

    /** The ready derived file of an object's base cell (used by Bake), or null. */
    public Path readyFile(UUID objectId) {
        FileEntry e = entryOf(objectId);
        return e != null && e.status == Status.READY ? e.file : null;
    }

    private FileEntry entryOf(UUID baseSlot) {
        SlotState st = slots.get(baseSlot);
        return st == null || st.wantedKey == null ? null : files.get(st.wantedKey);
    }

    public Info infoOf(UUID objectId) {
        FileEntry e = entryOf(objectId);
        return e == null ? null : new Info(e.status, e.error, e.removed, e.booleanRemoved, e.trimBy);
    }

    /** Derived files that are being built or wait for their debounce. */
    public int working() {
        int n = 0;
        for (FileEntry e : files.values()) if (e.status == Status.BUILDING) n++;
        return n;
    }

    public int failed() {
        int n = 0;
        for (FileEntry e : files.values()) if (e.status == Status.FAILED) n++;
        return n;
    }

    public String firstError() {
        for (FileEntry e : files.values()) if (e.status == Status.FAILED) return e.error;
        return null;
    }

    /** Objects that hit the cap of {@link CellMasks#MAX_FILES} files in the last planning pass. */
    public int cappedObjects() {
        return capped.size();
    }

    /** A warning for the status bar when some copies were left on the original file because of the cap, else null. */
    public String capWarning() {
        if (capped.isEmpty()) return null;
        int cells = 0;
        for (int n : capped.values()) cells += n;
        return capped.size() + (capped.size() == 1 ? " object has" : " objects have") + " more than " + CellMasks.MAX_FILES
                + " trim variants: " + cells + " cop" + (cells == 1 ? "y keeps" : "ies keep") + " the original file";
    }

    /** Starts the failed jobs again. */
    public void retryFailed() {
        for (FileEntry e : files.values()) {
            if (e.status == Status.FAILED) {
                e.status = Status.BUILDING;
                e.error = null;
                e.launched = false;
                e.since = Integer.MIN_VALUE / 2;
            }
        }
    }

    /** Plans every placed copy of the projects of this world (and Loose) and starts the jobs that are due. Call once per client tick. */
    public void tick(Scene scene, String world, String dim, int tick) {
        if (core == null || tick - lastPlan < PLAN_EVERY) return;
        lastPlan = tick;
        if (busy.getAsBoolean()) {
            for (FileEntry e : files.values()) e.since = Math.max(e.since, tick);   // wait for the release
            return;
        }
        lastScene = scene;
        PlanEnv env = new PlanEnv(scene, core);
        var owners = scene.frontOwnersInWorld(world);
        List<CellMasks.Cell> cells = new ArrayList<>();
        Map<UUID, ReconcileCore.CellRef> refs = new HashMap<>();
        Map<UUID, Path> dirs = new HashMap<>();
        for (ReconcileCore.CellRef ref : core.cellRefs()) {
            ProjectNode p = ref.project();
            Path dir;
            if (p != null) {
                if (!p.world().equals(world) || !p.dim().equals(dim)) continue;
                dir = core.projectDir(p);
                if (dir != null) plannedProjects.add(p.id());
            } else {
                dir = core.looseCache();
            }
            if (dir == null) continue;
            cells.add(new CellMasks.Cell(ref.slot(), new MaskPlan.Subject(ref.object(), ref.base(), ref.world(), ref.owner(), p == null)));
            refs.put(ref.slot(), ref);
            dirs.put(ref.slot(), dir);
        }
        CellMasks.Result res = CellMasks.plan(scene, cells, owners, env, MaskPlan.Filter.ALL);
        capped = res.capped();
        Set<String> referenced = new HashSet<>();
        for (var e : res.plans().entrySet()) {
            UUID slot = e.getKey();
            MaskPlan.Plan plan = e.getValue();
            ReconcileCore.CellRef ref = refs.get(slot);
            String key = ref.object().id() + "-" + plan.hash();
            referenced.add(key);
            FileEntry fe = files.get(key);
            if (fe == null) {
                fe = new FileEntry(ref.object().id());
                fe.plan = plan;
                fe.projectDir = dirs.get(slot);
                fe.source = core.fileOf(scene, ref.object(), ref.base());
                fe.since = tick;
                Path target = ProjectLayout.derivedFile(fe.projectDir, fe.objectId, plan.hash());
                if (Files.isRegularFile(target)) {      // a cache hit needs no debounce
                    fe.file = target;
                    fe.status = Status.READY;
                }
                files.put(key, fe);
            }
            if (slot.equals(ref.object().id())) fe.wantCount = true;
            launch(key, fe, tick);
            SlotState st = slots.computeIfAbsent(slot, k -> new SlotState());
            st.wantedKey = key;
            if (fe.status == Status.READY && fe.file != null) st.shown = fe.file;
        }
        slots.keySet().retainAll(res.plans().keySet());
        files.keySet().retainAll(referenced);
    }

    /** Starts the job of a file when it is due: a missing file after the debounce, a cache hit only for the numbers. */
    private void launch(String key, FileEntry e, int tick) {
        if (e.launched || tick - e.since < DEBOUNCE) return;
        boolean exists = e.file != null;
        if (exists && (!e.wantCount || e.removed >= 0)) return;
        e.launched = true;
        Path target = ProjectLayout.derivedFile(e.projectDir, e.objectId, e.plan.hash());
        if (!exists) e.status = Status.BUILDING;
        submit(key, new Job(e.plan, e.source, target, !exists, r -> done(key, target, r)));
    }

    private Scene lastScene;

    private Scene scene() {
        return lastScene;
    }

    /** Call before {@link #tick} so file lookups use the current scene. */
    public void setScene(Scene s) {
        lastScene = s;
    }

    private void done(String key, Path target, Result r) {
        FileEntry e = files.get(key);
        if (e == null) return;     // superseded: the file stays in the cache
        if (r.error != null) {
            e.status = Status.FAILED;
            e.error = r.error;
            return;
        }
        e.file = target;
        e.status = Status.READY;
        e.error = null;
        e.removed = r.removed;
        e.booleanRemoved = r.booleanRemoved;
        e.trimBy = r.trimBy;
    }

    /**
     * Builds the derived file of a plan now (Apply, exports): a cache hit answers at once, otherwise a job runs on
     * the worker. {@code done} is called on the client thread.
     */
    public void build(MaskPlan.Plan plan, Path source, Path projectDir, Consumer<Build> done) {
        if (plan.isEmpty()) {
            done.accept(new Build(source, 0, null));
            return;
        }
        Path target = ProjectLayout.derivedFile(projectDir, plan.objectId(), plan.hash());
        if (Files.isRegularFile(target)) {
            done.accept(new Build(target, -1, null));
            return;
        }
        submit(new Object(), new Job(plan, source, target, true, r -> done.accept(new Build(r.error == null ? target : null, r.removed, r.error))));
    }

    /** The plan of one object as it is now (used by Apply and the exports); empty without a reconciler. */
    public MaskPlan.Plan planOf(Scene scene, ObjectNode o, MaskPlan.Filter f) {
        ProjectNode p = scene.projectOf(o);
        if (core == null || p == null) return MaskPlan.emptyPlan(o.id());
        return MaskPlan.plan(scene, o, scene.frontOwnersInWorld(p.world()), new PlanEnv(scene, core), f);
    }

    /** Moves derived files of a project that no copy uses any more to {@code .trash/derived}. Never deletes. */
    public void cleanup(ProjectNode p) {
        if (core == null || !plannedProjects.contains(p.id()) || working() > 0 || busyJobs() > 0) return;
        Path dir = core.projectDir(p);
        if (dir == null) return;
        Path derived = ProjectLayout.derivedDir(dir);
        if (!Files.isDirectory(derived)) return;
        Set<String> keep = new HashSet<>();
        for (FileEntry e : files.values()) {
            if (e.file != null) keep.add(e.file.getFileName().toString());
            keep.add(e.objectId + "-" + e.plan.hash() + ProjectLayout.SCHEMATIC_EXT);
        }
        for (SlotState st : slots.values()) if (st.shown != null) keep.add(st.shown.getFileName().toString());
        List<Path> stale = new ArrayList<>();
        try (Stream<Path> s = Files.list(derived)) {
            s.filter(Files::isRegularFile).filter(f -> !keep.contains(f.getFileName().toString())).forEach(stale::add);
        } catch (IOException e) {
            return;
        }
        if (stale.isEmpty()) return;
        Path trash = ProjectLayout.trashDir(dir).resolve("derived");
        try {
            Files.createDirectories(trash);
            for (Path f : stale) {
                Path to = trash.resolve(f.getFileName().toString());
                for (int i = 2; Files.exists(to); i++) to = trash.resolve(i + "_" + f.getFileName());
                Files.move(f, to);
            }
        } catch (IOException e) {
            System.out.println("[SchematicArray] cannot move derived files to .trash: " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------------------------------ the plan environment

    private static final class PlanEnv implements MaskPlan.Env {
        private final Scene scene;
        private final ReconcileCore core;
        private final AssemblyBounds.Env bounds;

        PlanEnv(Scene scene, ReconcileCore core) {
            this.scene = scene;
            this.core = core;
            this.bounds = core.boundsEnv(scene);
        }

        @Override
        public Path file(ObjectNode o, Path base) {
            return core.fileOf(scene, o, base);
        }

        private final Map<Path, Long> mtimes = new HashMap<>();

        @Override
        public long mtime(Path file) {
            return mtimes.computeIfAbsent(file, f -> {
                try {
                    return Files.getLastModifiedTime(f).toMillis();
                } catch (IOException e) {
                    return 0L;
                }
            });
        }

        @Override
        public MaskPlan.Occupancy occupancy(List<MaskPlan.Placed> placed) {
            return new LazyGrid(placed);
        }

        @Override
        public Vec3i size(ObjectNode o, Path base) {
            return bounds.size(o, base);
        }

        @Override
        public AssemblyBounds.Linked linked(AssemblyNode a, Path base) {
            return bounds.linked(a, base);
        }
    }

    // ------------------------------------------------------------------------------------------ occupancy of loaded schematics

    /** A loaded schematic as a local non-air test (region offsets included). */
    private static final class Cells implements PlacedGrid.Local {
        private record Reg(int x, int y, int z, int sx, int sy, int sz, LitematicaBlockStateContainer c) {}

        private final List<Reg> regs = new ArrayList<>();

        Cells() {}

        Cells(LitematicaSchematic s) {
            for (SchematicInfo.Region r : SchematicInfo.regionsOf(s)) {
                LitematicaBlockStateContainer c = s.getSubRegionContainer(r.name());
                if (c == null) continue;
                Vec3i sz = r.box().size();
                regs.add(new Reg(r.box().min().x(), r.box().min().y(), r.box().min().z(), sz.x(), sz.y(), sz.z(), c));
            }
        }

        @Override
        public boolean solid(int x, int y, int z) {
            for (Reg r : regs) {
                int dx = x - r.x, dy = y - r.y, dz = z - r.z;
                if (dx < 0 || dy < 0 || dz < 0 || dx >= r.sx || dy >= r.sy || dz >= r.sz) continue;
                BlockState b = r.c.get(dx, dy, dz);
                if (b != null && !b.isAir()) return true;
            }
            return false;
        }
    }

    private static final Map<String, SoftReference<Cells>> CELLS = new HashMap<>();

    private static Cells cellsOf(MaskPlan.Placed p) {
        String key = p.file() + "@" + p.mtime();
        synchronized (CELLS) {
            SoftReference<Cells> ref = CELLS.get(key);
            Cells c = ref == null ? null : ref.get();
            if (c != null) return c;
            LitematicaSchematic s = load(p.file());
            c = s == null ? new Cells() : new Cells(s);
            CELLS.put(key, new SoftReference<>(c));
            return c;
        }
    }

    /** Loads the union of placed copies on first use (on the worker thread). */
    private static final class LazyGrid implements MaskPlan.Occupancy {
        private final List<MaskPlan.Placed> placed;
        private PlacedGrid grid;

        LazyGrid(List<MaskPlan.Placed> placed) {
            this.placed = placed;
        }

        @Override
        public boolean solid(int x, int y, int z) {
            PlacedGrid g = grid;
            if (g == null) {
                synchronized (this) {
                    if (grid == null) {
                        List<PlacedGrid.Copy> copies = new ArrayList<>();
                        for (MaskPlan.Placed p : placed) copies.add(new PlacedGrid.Copy(cellsOf(p), p.world()));
                        grid = new PlacedGrid(copies);
                    }
                    g = grid;
                }
            }
            return g.solid(x, y, z);
        }
    }

    private static LitematicaSchematic load(Path f) {
        Path dir = f.getParent();
        return dir == null ? null : LitematicaSchematic.createFromFile(dir, f.getFileName().toString());
    }

    // ------------------------------------------------------------------------------------------ the worker

    private record Result(long removed, long booleanRemoved, Map<UUID, Long> trimBy, String error) {}

    private record Job(MaskPlan.Plan plan, Path source, Path target, boolean write, Consumer<Result> done) {}

    private final Object lock = new Object();
    private final LinkedHashMap<Object, Job> queue = new LinkedHashMap<>();
    private volatile int running;
    private final ExecutorService pool = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "schemarray-derived");
        t.setDaemon(true);
        return t;
    });

    private int busyJobs() {
        synchronized (lock) {
            return queue.size() + running;
        }
    }

    private void submit(Object key, Job job) {
        synchronized (lock) {
            queue.remove(key);
            queue.put(key, job);
        }
        pool.execute(this::drain);
    }

    private void drain() {
        Job j;
        synchronized (lock) {
            var it = queue.entrySet().iterator();
            if (!it.hasNext()) return;
            j = it.next().getValue();
            it.remove();
            running++;
        }
        Result r;
        try {
            r = run(j);
        } catch (Throwable t) {
            r = new Result(0, 0, Map.of(), "Building the derived file failed: " + t);
        } finally {
            synchronized (lock) {
                running--;
            }
        }
        Result res = r;
        Minecraft.getInstance().execute(() -> j.done().accept(res));
    }

    private static Result run(Job j) throws IOException {
        MaskPlan.Plan plan = j.plan();
        LitematicaSchematic src = load(j.source());
        if (src == null) return new Result(0, 0, Map.of(), "Cannot read " + j.source().getFileName());
        long removed = 0, bool = 0;
        Map<MaskPlan.Contributor, long[]> by = new IdentityHashMap<>();
        BlockState air = LitematicaBlockStateContainer.AIR_BLOCK_STATE;
        for (SchematicInfo.Region r : SchematicInfo.regionsOf(src)) {
            LitematicaBlockStateContainer c = src.getSubRegionContainer(r.name());
            if (c == null) continue;
            Map<BlockPos, ?> tiles = null;
            try {
                tiles = src.getBlockEntityMapForRegion(r.name());
            } catch (RuntimeException ignored) {
                // no block entities for this region
            }
            Box box = r.box();
            net.minecraft.core.Vec3i sz = c.getSize();
            for (int y = 0; y < Math.abs(sz.getY()); y++) {
                for (int z = 0; z < Math.abs(sz.getZ()); z++) {
                    for (int x = 0; x < Math.abs(sz.getX()); x++) {
                        BlockState st = c.get(x, y, z);
                        if (st == null || st.isAir()) continue;
                        Vec3i w = plan.world().apply(new Vec3i(box.min().x() + x, box.min().y() + y, box.min().z() + z));
                        MaskPlan.Contributor who = plan.remover(w.x(), w.y(), w.z());
                        if (who == null) continue;
                        c.set(x, y, z, air);
                        removed++;
                        if (who.kind() != MaskPlan.Kind.TRIM) bool++;
                        by.computeIfAbsent(who, k -> new long[1])[0]++;
                        if (tiles != null) {
                            try {
                                tiles.remove(new BlockPos(x, y, z));
                            } catch (UnsupportedOperationException ignored) {
                                // immutable: the block entity stays
                            }
                        }
                    }
                }
            }
        }
        Map<UUID, Long> trimBy = new HashMap<>();
        for (var e : by.entrySet()) if (e.getKey().kind() == MaskPlan.Kind.TRIM) trimBy.merge(e.getKey().id(), e.getValue()[0], Long::sum);
        if (j.write()) {
            Path dir = j.target().getParent();
            Files.createDirectories(dir);
            String stem = j.target().getFileName().toString();
            stem = stem.substring(0, stem.length() - ProjectLayout.SCHEMATIC_EXT.length());
            String tmpStem = stem + "_part";
            Path tmp = dir.resolve(tmpStem + ProjectLayout.SCHEMATIC_EXT);
            var meta = src.getMetadata();
            meta.setTotalBlocks((int) Math.max(0, meta.getTotalBlocks() - removed));
            meta.setTimeModified(System.currentTimeMillis());
            boolean ok;
            try {
                ok = src.writeToFile(dir, tmpStem, true);
            } catch (RuntimeException e) {
                return new Result(removed, bool, trimBy, "Writing the derived file failed: " + e.getMessage());
            }
            if (!ok || !Files.isRegularFile(tmp)) return new Result(removed, bool, trimBy, "Writing the derived file failed");
            Files.move(tmp, j.target(), StandardCopyOption.REPLACE_EXISTING);
        }
        return new Result(removed, bool, trimBy, null);
    }
}
