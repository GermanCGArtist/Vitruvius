package dev.vitruvius.shape;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

/**
 * Turns settings into voxels: solid mask from Shapes.inside, outline = solid cells within spec.thickness of a
 * non-solid cell (6-neighbour distance; cells outside the box count as non-solid), fill = the other solid cells.
 * Outline cells take their block from the outline slot, fill cells from the fill slot (Patterns.pick).
 */
public final class ShapeGenerator {
    /** Distance of a solid cell before the distance passes; larger than any real distance in a 256 box. */
    private static final short FAR = 30000;

    private ShapeGenerator() {}

    /**
     * Pure and thread safe; runs on the preview worker thread. Checks cancelled() at least once per Y layer and
     * returns null when it says true.
     */
    public static VoxelResult generate(ShapeSpec spec, BlockSlot outline, BlockSlot fill, BooleanSupplier cancelled) {
        ShapeSpec s = spec.clamped();
        int sx = s.sizeX(), sy = s.sizeY(), sz = s.sizeZ();
        short[] dist = new short[sx * sy * sz];
        boolean frame = s.type() == ShapeType.FRAME;

        int i = 0;
        for (int y = 0; y < sy; y++) {
            if (cancelled.getAsBoolean()) return null;
            for (int z = 0; z < sz; z++) {
                for (int x = 0; x < sx; x++) {
                    dist[i++] = Shapes.inside(s, x, y, z) ? FAR : 0;
                }
            }
        }
        if (!frame && !distance(dist, sx, sy, sz, cancelled)) return null;

        Palette palette = new Palette();
        int[] outlineIds = new int[outline.entries().size()];
        int[] fillIds = new int[fill.entries().size()];
        Arrays.fill(outlineIds, -1);
        Arrays.fill(fillIds, -1);
        short[] cells = new short[dist.length];
        int count = 0;
        int thickness = s.thickness();
        i = 0;
        for (int y = 0; y < sy; y++) {
            if (cancelled.getAsBoolean()) return null;
            for (int z = 0; z < sz; z++) {
                for (int x = 0; x < sx; x++, i++) {
                    int d = dist[i];
                    if (d == 0) continue;
                    boolean isOutline = frame || d <= thickness;
                    BlockSlot slot = isOutline ? outline : fill;
                    int pick = Patterns.pick(slot, x, y, z, sx, sy, sz);
                    if (pick < 0) continue;
                    int[] ids = isOutline ? outlineIds : fillIds;
                    int id = ids[pick];
                    if (id < 0) id = ids[pick] = palette.idOf(slot.entries().get(pick).blockState());
                    if (id == 0) continue;
                    cells[i] = (short) id;
                    count++;
                }
            }
        }
        return new VoxelResult(sx, sy, sz, cells, List.copyOf(palette.states), count);
    }

    public static VoxelResult generate(GeneratorSettings s, BooleanSupplier cancelled) {
        return generate(s.spec().clamped(), s.outline(), s.fill(), cancelled);
    }

    /**
     * Replaces the FAR values of solid cells by their 6-neighbour distance to the nearest non-solid cell (outside
     * the box counts as non-solid) with a forward and a backward chamfer pass, which is exact for this metric.
     * Returns false when cancelled.
     */
    private static boolean distance(short[] d, int sx, int sy, int sz, BooleanSupplier cancelled) {
        int strideY = sx * sz;
        for (int y = 0; y < sy; y++) {
            if (cancelled.getAsBoolean()) return false;
            for (int z = 0; z < sz; z++) {
                int i = y * strideY + z * sx;
                for (int x = 0; x < sx; x++, i++) {
                    int m = d[i];
                    if (m == 0) continue;
                    m = Math.min(m, x == 0 ? 1 : d[i - 1] + 1);
                    m = Math.min(m, z == 0 ? 1 : d[i - sx] + 1);
                    m = Math.min(m, y == 0 ? 1 : d[i - strideY] + 1);
                    d[i] = (short) m;
                }
            }
        }
        for (int y = sy - 1; y >= 0; y--) {
            if (cancelled.getAsBoolean()) return false;
            for (int z = sz - 1; z >= 0; z--) {
                int i = y * strideY + z * sx + sx - 1;
                for (int x = sx - 1; x >= 0; x--, i--) {
                    int m = d[i];
                    if (m == 0) continue;
                    m = Math.min(m, x == sx - 1 ? 1 : d[i + 1] + 1);
                    m = Math.min(m, z == sz - 1 ? 1 : d[i + sx] + 1);
                    m = Math.min(m, y == sy - 1 ? 1 : d[i + strideY] + 1);
                    d[i] = (short) m;
                }
            }
        }
        return true;
    }

    /** Distinct block states in first-use order; a blank state is air (id 0). */
    private static final class Palette {
        final List<String> states = new ArrayList<>();
        private final Map<String, Integer> ids = new HashMap<>();

        int idOf(String state) {
            if (state.isEmpty()) return 0;
            Integer id = ids.get(state);
            if (id == null) {
                states.add(state);
                id = states.size();
                ids.put(state, id);
            }
            return id;
        }
    }
}
