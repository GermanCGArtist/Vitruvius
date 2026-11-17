package dev.schemarray;

import dev.schemarray.editor.core.ArrayModifier;
import dev.schemarray.editor.core.ObjectNode;
import dev.schemarray.editor.mc.Reconciler;
import dev.schemarray.shape.mc.ShapePreview;
import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacementManager;
import fi.dy.masa.litematica.schematic.placement.SubRegionPlacement;
import fi.dy.masa.malilib.gui.Message;
import fi.dy.masa.malilib.gui.interfaces.IMessageConsumer;
import fi.dy.masa.malilib.interfaces.IStringConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Keeps the copy placements of every array where they belong. The copies are ordinary Litematica placements of the
 * same schematic (so they render, print and verify like any placement), placed at the original's origin plus the
 * cell offset, turned and mirrored with it. Runs every client tick, so moving, rotating, mirroring, hiding or
 * switching sub-regions of the original shows on the copies at once.
 */
public final class ArraySync {
    private ArraySync() {}

    /** Most copies one array may have. */
    public static final int MAX_COPIES = 1024;

    static final IMessageConsumer QUIET = new IMessageConsumer() {
        @Override
        public void addMessage(Message.MessageType type, String key, Object... args) {}

        @Override
        public void addMessage(Message.MessageType type, int lifeTime, String key, Object... args) {}
    };
    private static final IStringConsumer QUIET_STR = s -> {};

    private static Object lastLevel;
    private static int ticks;
    private static final List<Integer> rebuildAt = new ArrayList<>();

    public static void tick(Minecraft mc) {
        if (mc.level == null) { lastLevel = null; return; }
        ticks++;
        if (mc.level != lastLevel) {
            // just joined or changed dimension: Litematica loads the placements now, and copies of the same schematic
            // sometimes come up with chunks missing. Rebuild their chunks a little later, and once more after that.
            lastLevel = mc.level;
            rebuildAt.clear();
            rebuildAt.add(ticks + 40);
            rebuildAt.add(ticks + 200);
        }
        if (Reconciler.joining()) return;   // the editor looks at the placements first and takes over the arrays it owns
        Map<String, ArrayStore.Entry> arrays = ArrayStore.all();
        if (arrays.isEmpty()) return;
        SchematicPlacementManager mgr = DataManager.getSchematicPlacementManager();
        Map<String, SchematicPlacement> byId = new HashMap<>();
        for (SchematicPlacement p : mgr.getAllSchematicsPlacements()) {
            if (!ShapePreview.isPreview(p)) byId.put(p.getHashId().toString(), p);
        }

        if (rebuildAt.remove((Integer) ticks)) {
            for (var e : arrays.entrySet()) {
                if (Reconciler.ownerOfBase(e.getKey()) != null) continue;   // migrated: the reconciler owns it
                SchematicPlacement parent = byId.get(e.getKey());
                if (parent != null) mgr.markChunksForRebuild(parent);
                for (String c : e.getValue().copies.values()) {
                    SchematicPlacement cp = byId.get(c);
                    if (cp != null) mgr.markChunksForRebuild(cp);
                }
            }
        }

        boolean changed = false;
        for (String parentId : new ArrayList<>(arrays.keySet())) {
            if (Reconciler.ownerOfBase(parentId) != null) continue;   // migrated: the reconciler owns it
            ArrayStore.Entry e = arrays.get(parentId);
            SchematicPlacement parent = byId.get(parentId);
            if (parent == null) {
                // copies here but no original: it was removed, so its copies go too. No copies either: another world.
                boolean any = false;
                for (String c : e.copies.values()) {
                    SchematicPlacement cp = byId.get(c);
                    if (cp != null) { mgr.removeSchematicPlacement(cp, false); any = true; }
                }
                if (any) { ArrayStore.remove(parentId); changed = true; }
                continue;
            }
            changed |= sync(mgr, parent, e, byId);
            if (!e.active() && e.copies.isEmpty() && e.mods.isEmpty()) { ArrayStore.remove(parentId); changed = true; }
        }
        if (changed) ArrayStore.save();
    }

    /** Size of the schematic (its enclosing box). */
    public static Vec3i size(LitematicaSchematic s) {
        Vec3i t = s.getTotalSize();
        if (t != null && t.getX() != 0 && t.getY() != 0 && t.getZ() != 0) return new Vec3i(Math.abs(t.getX()), Math.abs(t.getY()), Math.abs(t.getZ()));
        int[] min = {Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE}, max = {Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE};
        Map<String, BlockPos> sizes = s.getAreaSizes();
        for (var a : s.getAreaPositions().entrySet()) {
            BlockPos p = a.getValue(), sz = sizes.get(a.getKey());
            if (sz == null) continue;
            int[] pp = {p.getX(), p.getY(), p.getZ()}, ss = {sz.getX(), sz.getY(), sz.getZ()};
            for (int i = 0; i < 3; i++) {
                int end = ss[i] >= 0 ? pp[i] + ss[i] - 1 : pp[i] + ss[i] + 1;
                min[i] = Math.min(min[i], Math.min(pp[i], end));
                max[i] = Math.max(max[i], Math.max(pp[i], end));
            }
        }
        if (min[0] == Integer.MAX_VALUE) return new Vec3i(1, 1, 1);
        return new Vec3i(max[0] - min[0] + 1, max[1] - min[1] + 1, max[2] - min[2] + 1);
    }

    /** Copies an array of these modifiers makes: the product of the enabled counts, minus the original. */
    public static long copyCountOf(List<ArrayStore.Mod> mods) {
        long n = 1;
        for (ArrayStore.Mod m : mods) {
            if (!m.enabled) continue;
            n *= Math.max(1, m.count);
            if (n > 1_000_000_000L) return n;
        }
        return n - 1;
    }

    /** Whether this modifier list is allowed (not more than MAX_COPIES copies). */
    public static boolean allowed(List<ArrayStore.Mod> mods) {
        return copyCountOf(mods) <= MAX_COPIES;
    }

    /**
     * The step of every enabled modifier (Blender semantics): relative * the bounding box size so far, plus the
     * constant offset; the box then grows to hold the copies of that modifier.
     */
    static List<int[]> steps(List<ArrayStore.Mod> mods, Vec3i size) {
        long[] min = {0, 0, 0}, max = {size.getX() - 1, size.getY() - 1, size.getZ() - 1};
        List<int[]> out = new ArrayList<>();
        for (ArrayStore.Mod m : mods) {
            if (!m.enabled) continue;
            int[] step = new int[3];
            for (int a = 0; a < 3; a++) {
                if (m.useRelative) step[a] += (int) Math.round(m.relative[a] * (max[a] - min[a] + 1));
                if (m.useConstant) step[a] += m.constant[a];
                long last = (long) (Math.max(1, m.count) - 1) * step[a];
                min[a] = Math.min(min[a], min[a] + last);
                max[a] = Math.max(max[a], max[a] + last);
            }
            out.add(step);
        }
        return out;
    }

    /** Cell key ("i,j,k", one index per enabled modifier) to local offset, for every copy (not the original). */
    static Map<String, int[]> cells(List<ArrayStore.Mod> mods, Vec3i size) {
        Map<String, int[]> out = new java.util.LinkedHashMap<>();
        if (!allowed(mods) || copyCountOf(mods) < 1) return out;
        List<ArrayStore.Mod> on = new ArrayList<>();
        for (ArrayStore.Mod m : mods) if (m.enabled) on.add(m);
        List<int[]> steps = steps(mods, size);
        int k = on.size();
        int[] idx = new int[k];
        while (true) {
            boolean zero = true;
            int[] off = new int[3];
            StringBuilder key = new StringBuilder();
            for (int i = 0; i < k; i++) {
                if (idx[i] != 0) zero = false;
                if (i > 0) key.append(',');
                key.append(idx[i]);
                for (int a = 0; a < 3; a++) off[a] += idx[i] * steps.get(i)[a];
            }
            if (!zero) out.put(key.toString(), off);
            int i = k - 1;
            while (i >= 0 && ++idx[i] >= Math.max(1, on.get(i).count)) idx[i--] = 0;
            if (i < 0) break;
        }
        return out;
    }

    private static boolean sync(SchematicPlacementManager mgr, SchematicPlacement parent, ArrayStore.Entry e, Map<String, SchematicPlacement> byId) {
        LitematicaSchematic schematic = parent.getSchematic();
        if (schematic == null) return false;
        boolean changed = false;

        // which cells there should be (the original itself is the all-zero cell)
        Vec3i size = size(schematic);
        Map<String, int[]> want = cells(e.mods, size);

        // cells that are no longer wanted
        for (var it = e.copies.entrySet().iterator(); it.hasNext(); ) {
            var c = it.next();
            if (want.containsKey(c.getKey())) continue;
            SchematicPlacement cp = byId.get(c.getValue());
            if (cp != null) mgr.removeSchematicPlacement(cp, false);
            it.remove();
            changed = true;
        }

        BlockPos origin = parent.getOrigin();
        Rotation rot = parent.getRotation();
        Mirror mir = parent.getMirror();

        Map<String, Boolean> subStates = new HashMap<>();
        for (SubRegionPlacement s : parent.getAllSubRegionsPlacements()) subStates.put(s.getName(), s.isEnabled());

        for (var cellEntry : want.entrySet()) {
            String key = cellEntry.getKey();
            int[] off = cellEntry.getValue();
            BlockPos at = origin.offset(transform(off[0], off[1], off[2], mir, rot));

            String id = e.copies.get(key);
            SchematicPlacement cp = id == null ? null : byId.get(id);
            if (cp == null || cp.getSchematic() != schematic) {
                if (cp != null) mgr.removeSchematicPlacement(cp, false);
                UUID uuid = UUID.randomUUID();
                cp = SchematicPlacement.createFor(schematic, at, parent.getName() + " #" + key, parent.isEnabled(), parent.isRenderingEnabled(), uuid);
                cp.setRotation(rot, QUIET);
                cp.setMirror(mir, QUIET);
                mgr.addSchematicPlacement(cp, false);
                e.copies.put(key, uuid.toString());
                byId.put(uuid.toString(), cp);
                changed = true;
                if (!rebuildAt.contains(ticks + 20)) rebuildAt.add(ticks + 20);
            }
            if (cp.getRotation() != rot) cp.setRotation(rot, QUIET);
            if (cp.getMirror() != mir) cp.setMirror(mir, QUIET);
            if (!cp.getOrigin().equals(at)) cp.setOrigin(at, QUIET_STR);
            if (cp.isEnabled() != parent.isEnabled()) cp.setEnabled(parent.isEnabled());
            if (cp.isRenderingEnabled() != parent.isRenderingEnabled()) cp.setRenderSchematic(parent.isRenderingEnabled());
            for (SubRegionPlacement s : cp.getAllSubRegionsPlacements()) {
                Boolean on = subStates.get(s.getName());
                if (on != null && on != s.isEnabled()) cp.toggleSubRegionEnabled(s.getName(), QUIET);
            }
        }
        if (changed) ArrayStore.reindex();
        return changed;
    }

    /** Schematic coordinates to world offset: mirror first, then rotation (as Litematica does). */
    static BlockPos transform(int x, int y, int z, Mirror m, Rotation r) {
        if (m == Mirror.LEFT_RIGHT) z = -z;
        else if (m == Mirror.FRONT_BACK) x = -x;
        return switch (r) {
            case CLOCKWISE_90 -> new BlockPos(-z, y, x);
            case COUNTERCLOCKWISE_90 -> new BlockPos(z, y, -x);
            case CLOCKWISE_180 -> new BlockPos(-x, y, -z);
            default -> new BlockPos(x, y, z);
        };
    }

    /** The placement whose array the config screen edits: the original, also when a copy is open. */
    public static SchematicPlacement original(SchematicPlacement p) {
        String parent = ArrayStore.parentOf(p.getHashId());
        if (parent == null) parent = Reconciler.baseOfCopy(p.getHashId().toString());   // a copy the editor made
        if (parent == null) return p;
        for (SchematicPlacement o : DataManager.getSchematicPlacementManager().getAllSchematicsPlacements()) {
            if (o.getHashId().toString().equals(parent)) return o;
        }
        return p;
    }

    public static boolean isCopy(SchematicPlacement p) {
        return ArrayStore.parentOf(p.getHashId()) != null || Reconciler.baseOfCopy(p.getHashId().toString()) != null;
    }

    /** The modifiers of this placement's array (deep copies; empty = no array). */
    public static List<ArrayStore.Mod> mods(SchematicPlacement original) {
        ObjectNode node = Reconciler.ownerOfBase(original.getHashId().toString());
        List<ArrayStore.Mod> out = new ArrayList<>();
        if (node != null) {   // migrated: the array lives in the editor's model now
            for (var m : node.mods()) {
                if (!(m instanceof ArrayModifier a)) continue;
                ArrayStore.Mod c = new ArrayStore.Mod();
                c.enabled = a.enabled;
                c.count = a.count;
                c.useRelative = a.useRelative;
                c.relative = a.relative.clone();
                c.useConstant = a.useConstant;
                c.constant = a.constant.clone();
                out.add(c);
            }
            return out;
        }
        ArrayStore.Entry e = ArrayStore.get(original.getHashId());
        if (e != null) for (ArrayStore.Mod m : e.mods) out.add(m.copy());
        return out;
    }

    /** Replaces the modifiers (a copy of the list is kept); the copies follow at once. */
    public static void setMods(SchematicPlacement original, List<ArrayStore.Mod> mods) {
        ObjectNode node = Reconciler.ownerOfBase(original.getHashId().toString());
        if (node != null) {   // migrated: edit the model, the reconciler makes the copies; other modifier kinds stay
            List<dev.schemarray.editor.core.Modifier> next = new ArrayList<>();
            for (ArrayStore.Mod m : mods) {
                ArrayModifier a = new ArrayModifier();
                a.enabled = m.enabled;
                a.count = m.count;
                a.useRelative = m.useRelative;
                a.relative = m.relative.clone();
                a.useConstant = m.useConstant;
                a.constant = m.constant.clone();
                next.add(a);
            }
            for (var m : node.mods()) if (!(m instanceof ArrayModifier)) next.add(m);
            node.setMods(next);
            return;
        }
        ArrayStore.Entry e = ArrayStore.getOrCreate(original.getHashId());
        e.mods = new ArrayList<>();
        for (ArrayStore.Mod m : mods) e.mods.add(m.copy());
        ArrayStore.save();
        tick(Minecraft.getInstance());
    }

    /** Number of copies this array makes right now. */
    public static int copyCount(SchematicPlacement original) {
        ObjectNode node = Reconciler.ownerOfBase(original.getHashId().toString());
        if (node != null) {
            List<ArrayModifier> arrays = new ArrayList<>();
            for (var m : node.mods()) if (m instanceof ArrayModifier a) arrays.add(a);
            long n = dev.schemarray.editor.core.ArrayCells.copyCountOf(arrays);
            return n >= 1 && n <= MAX_COPIES ? (int) n : 0;
        }
        ArrayStore.Entry e = ArrayStore.get(original.getHashId());
        if (e == null || !e.active()) return 0;
        return (int) copyCountOf(e.mods);
    }

    /** Number of copies a candidate modifier list would make (refuse it above MAX_COPIES). */
    public static long copyCount(List<ArrayStore.Mod> mods) {
        return copyCountOf(mods);
    }

    /** All copies of this array, as one list (for the label). */
    public static List<String> copyIds(SchematicPlacement original) {
        ObjectNode node = Reconciler.ownerOfBase(original.getHashId().toString());
        if (node != null) {
            List<String> out = new ArrayList<>();
            for (var e : node.placementIds().entrySet()) if (!e.getKey().equals(ObjectNode.BASE_CELL)) out.add(e.getValue());
            return out;
        }
        ArrayStore.Entry e = ArrayStore.get(original.getHashId());
        return e == null ? List.of() : new ArrayList<>(e.copies.values());
    }
}
