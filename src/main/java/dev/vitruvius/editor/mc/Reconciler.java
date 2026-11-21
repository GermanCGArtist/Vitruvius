package dev.vitruvius.editor.mc;

import dev.vitruvius.Compat;
import dev.vitruvius.editor.core.Node;
import dev.vitruvius.editor.core.ObjectNode;
import dev.vitruvius.editor.core.Vec3i;
import dev.vitruvius.editor.io.Migration1x;
import fi.dy.masa.litematica.data.DataManager;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Runs the reconciler every client tick (EDITOR-2.0 section 4) and owns the editor's session: the
 * {@link ProjectManager}, the {@link ReconcileCore} and the world-join sequence.
 *
 * <p>On a join it waits {@link #JOIN_GRACE} ticks (Litematica loads the placements of the world about then), then
 * enters the world: migrates the 1.x configs the first time, opens the projects this world had open, and schedules a
 * chunk rebuild of everything it manages a little later (copies of one schematic sometimes come up with chunks
 * missing, as the 1.x arrays found). After that every tick runs one pass. A failure anywhere is logged once and
 * switches the editor off until the next join, so the 1.x behaviour is never in the way.
 *
 * <p>The static queries at the bottom are for the placement list: which placements the model owns.
 */
public final class Reconciler {
    private Reconciler() {}

    /** Ticks after a join before the placements are looked at. */
    static final int JOIN_GRACE = 40;

    private static ProjectManager pm;
    private static ReconcileCore core;
    private static Object lastLevel;
    private static int tick;
    private static int sinceJoin;
    private static boolean ready;
    private static boolean failed;

    private static Map<String, Map<String, String>> lastRuntime = Map.of();
    private static int runtimeDirtyAt = -1;

    private static Path runtimeFile(String world) {
        return RuntimeStore.file(FabricLoader.getInstance().getConfigDir().resolve("vitruvius"), world);
    }

    /** Writes the runtime placements when they changed: at most every 5 s, or at once with {@code force}. */
    private static void saveRuntime(boolean force) {
        String w = pm.world();
        if (core == null || w == null) return;
        Map<String, Map<String, String>> now = core.runtimeIds();
        if (now.equals(lastRuntime)) {
            runtimeDirtyAt = -1;
            return;
        }
        if (runtimeDirtyAt < 0) runtimeDirtyAt = tick;
        if (!force && tick - runtimeDirtyAt < 100) return;
        RuntimeStore.write(runtimeFile(w), now);
        lastRuntime = now;
        runtimeDirtyAt = -1;
    }
    private static String reportedFailure;

    private static volatile Map<String, ObjectNode> baseOwners = Map.of();
    private static volatile Map<String, String> copyParents = Map.of();

    private static void init() {
        if (pm != null) return;
        pm = new ProjectManager(new ProjectManager.Env() {
            @Override
            public Path configDir() {
                return FabricLoader.getInstance().getConfigDir();
            }

            @Override
            public Path schematicsDir() {
                return DataManager.getSchematicsBaseDirectory().toAbsolutePath().normalize();
            }

            @Override
            public Vec3i playerPos() {
                BlockPos p = Compat.playerBlockPos();
                return p == null ? Vec3i.ZERO : new Vec3i(p.getX(), p.getY(), p.getZ());
            }
        });
        LitematicaAdapter adapter = LitematicaAdapter.shared();
        core = new ReconcileCore(adapter, SchematicInfo.shared(), new LasmCache(), DataManager.getSchematicsBaseDirectory().toAbsolutePath().normalize());
        pm.setCloseHook(core::release);
        core.setDerived(DerivedFiles.shared());
        core.setLooseCache(FabricLoader.getInstance().getConfigDir().resolve("vitruvius").resolve("editor-loose"));
        DerivedFiles.shared().bind(core);
        dev.vitruvius.api.ObjectTypes.setSceneSource(() -> pm == null ? null : pm.scene());
        pm.setSaveHook(DerivedFiles.shared()::cleanup);
    }

    /** The open projects and Loose of the world the player is in (empty before the world is entered). */
    public static ProjectManager projects() {
        init();
        return pm;
    }

    /** The editor's scene while the reconciler runs, else null. Does not start anything (unlike {@link #projects()}). */
    public static dev.vitruvius.editor.core.Scene sceneIfActive() {
        return active() && pm != null ? pm.scene() : null;
    }

    /** Whom to tell about placements moved outside the editor (the undo stack). */
    public static void setDriftListener(DriftListener l) {
        init();
        core.setListener(l);
    }

    /** Local view: only these owners stay placed (null = all). Runtime only; see {@link ReconcileCore#setLocalView}. */
    public static void setLocalView(java.util.Set<java.util.UUID> owners) {
        init();
        core.setLocalView(owners);
    }

    /** Reload: the next pass reads every linked {@code .lasm} again. */
    public static void reloadLinks() {
        init();
        core.reloadLinks();
    }

    /** The planning half of the reconciler (null before the first tick): the cells of the last pass, for Syncmatica. */
    public static ReconcileCore core() {
        return core;
    }

    /** Whether the reconciler runs (a world is entered and nothing failed). */
    public static boolean active() {
        return ready && !failed;
    }

    public static void tick(Minecraft mc) {
        if (mc.level == null || mc.player == null) return;
        init();
        tick++;
        String world = worldKey(mc), dim = dimKey(mc);
        if (mc.level != lastLevel) {
            lastLevel = mc.level;
            if (ready && world.equals(pm.world())) {
                // another dimension of the same world: Litematica keeps its placements, but their chunks need a rebuild
                core.scheduleFullRebuild(tick + 40, tick + 200);
            } else {
                ready = false;
                failed = false;
                sinceJoin = 0;
            }
        }
        if (failed) return;
        if (!ready) {
            if (++sinceJoin < JOIN_GRACE) return;
            enter(world, dim);
            return;
        }
        pm.setDimension(dim);
        try {
            DerivedFiles.shared().tick(pm.scene(), world, dim, tick);
            core.reconcile(pm.scene(), world, dim, tick);
            dev.vitruvius.editor.sync.SyncService.get().tick(pm.scene(), core, pm, world, dim, tick);
            pm.tick();
            publishOwners();
            saveRuntime(false);
        } catch (Throwable t) {
            fail("reconcile", t);
        }
    }

    /** Leaving the world: saves the projects. Placements stay (Litematica saves them with the world). */
    public static void onDisconnect() {
        if (pm == null) return;
        try {
            if (ready && !failed) saveRuntime(true);
            pm.leaveWorld();
        } catch (Throwable t) {
            System.out.println("[Vitruvius] cannot save the projects: " + t);
        }
        if (core != null) core.reset();
        DerivedFiles.shared().reset();
        ready = false;
        failed = false;
        lastLevel = null;
        sinceJoin = 0;
        baseOwners = Map.of();
        copyParents = Map.of();
    }

    private static void enter(String world, String dim) {
        try {
            LitematicaAdapter adapter = LitematicaAdapter.shared();
            Set<String> ids = adapter.allHashIds();
            pm.enterWorld(world, dim, ids, id -> infoOf(adapter.read(id)));
            core.reset();
            lastRuntime = RuntimeStore.read(runtimeFile(world));
            core.restoreRuntime(lastRuntime);
            core.scheduleFullRebuild(tick + 40, tick + 200);
            ready = true;
        } catch (Throwable t) {
            fail("entering the world", t);
        }
    }

    private static Migration1x.PlacementInfo infoOf(PlacementState s) {
        if (s == null) return null;
        String file = s.file() == null ? "" : s.file().toString().replace('\\', '/');
        return new Migration1x.PlacementInfo(s.name(), file, s.origin(), s.rot(), s.mirror());
    }

    private static void fail(String what, Throwable t) {
        failed = true;
        ready = false;
        baseOwners = Map.of();
        copyParents = Map.of();
        String msg = what + ": " + t;
        if (!msg.equals(reportedFailure)) {
            reportedFailure = msg;
            System.out.println("[Vitruvius] editor reconciler stopped (" + msg + "); the 1.x behaviour continues");
            t.printStackTrace();
        }
    }

    // ------------------------------------------------------------------------------------------ world identity

    /** A key for the world: the save's name in single player, the address on a server. */
    static String worldKey(Minecraft mc) {
        var sp = mc.getSingleplayerServer();
        if (sp != null) return "sp:" + sp.getWorldData().getLevelName();
        var server = mc.getCurrentServer();
        if (server != null) return "mp:" + server.ip;
        return "other";
    }

    static String dimKey(Minecraft mc) {
        return mc.level.dimension().identifier().toString();
    }

    // ------------------------------------------------------------------------------------------ ownership

    private static void publishOwners() {
        Map<String, ObjectNode> bases = new HashMap<>();
        Map<String, String> copies = new HashMap<>();
        for (Node n : pm.scene().allNodes()) {
            if (!(n instanceof ObjectNode o)) continue;
            String base = o.placementIds().get(ObjectNode.BASE_CELL);
            if (base == null) continue;
            bases.put(base, o);
            for (var e : o.placementIds().entrySet()) if (!e.getKey().equals(ObjectNode.BASE_CELL)) copies.put(e.getValue(), base);
        }
        baseOwners = bases;
        copyParents = copies;
    }

    /** The object whose own placement this is, or null (not managed, or the editor is not running). */
    public static ObjectNode ownerOfBase(String hashId) {
        return baseOwners.get(hashId);
    }

    /** The hash id of the base placement this array copy belongs to, or null. */
    public static String baseOfCopy(String hashId) {
        return copyParents.get(hashId);
    }
}
