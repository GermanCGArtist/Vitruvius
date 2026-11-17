package dev.schemarray.editor.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** A pure port of {@code ArraySync.cells/steps/copyCountOf} (Blender array semantics), on a size and modifiers. */
public final class ArrayCells {
    private ArrayCells() {}

    /** Most copies one array may have. */
    public static final int MAX_COPIES = 1024;

    /** Copies an array of these modifiers makes: the product of the enabled counts, minus the original. */
    public static long copyCountOf(List<ArrayModifier> mods) {
        long n = 1;
        for (ArrayModifier m : mods) {
            if (!m.enabled) continue;
            n *= Math.max(1, m.count);
            if (n > 1_000_000_000L) return n;
        }
        return n - 1;
    }

    /** Whether this modifier list is allowed (not more than MAX_COPIES copies). */
    public static boolean allowed(List<ArrayModifier> mods) {
        return copyCountOf(mods) <= MAX_COPIES;
    }

    /**
     * The step of every enabled modifier: relative * the bounding box size so far, plus the constant offset; the box
     * then grows to hold the copies of that modifier. One entry per enabled modifier, in order.
     */
    public static List<Vec3i> steps(List<ArrayModifier> mods, Vec3i size) {
        long[] min = {0, 0, 0}, max = {size.x() - 1, size.y() - 1, size.z() - 1};
        List<Vec3i> out = new ArrayList<>();
        for (ArrayModifier m : mods) {
            if (!m.enabled) continue;
            int[] step = new int[3];
            for (int a = 0; a < 3; a++) {
                if (m.useRelative) step[a] += (int) Math.round(m.relative[a] * (max[a] - min[a] + 1));
                if (m.useConstant) step[a] += m.constant[a];
                long last = (long) (Math.max(1, m.count) - 1) * step[a];
                min[a] = Math.min(min[a], min[a] + last);
                max[a] = Math.max(max[a], max[a] + last);
            }
            out.add(new Vec3i(step[0], step[1], step[2]));
        }
        return out;
    }

    /**
     * Cell key ("i,j,k": one index per enabled modifier) to the local offset, for every copy (not the original, the
     * all-zero cell). Empty when the list makes no copies or is not {@link #allowed}. Keys are in the order of the
     * old code (the last modifier's index runs fastest).
     */
    public static Map<String, Vec3i> cells(List<ArrayModifier> mods, Vec3i size) {
        Map<String, Vec3i> out = new LinkedHashMap<>();
        if (!allowed(mods) || copyCountOf(mods) < 1) return out;
        List<ArrayModifier> on = new ArrayList<>();
        for (ArrayModifier m : mods) if (m.enabled) on.add(m);
        List<Vec3i> steps = steps(mods, size);
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
                for (int a = 0; a < 3; a++) off[a] += idx[i] * steps.get(i).get(a);
            }
            if (!zero) out.put(key.toString(), new Vec3i(off[0], off[1], off[2]));
            int i = k - 1;
            while (i >= 0 && ++idx[i] >= Math.max(1, on.get(i).count)) idx[i--] = 0;
            if (i < 0) break;
        }
        return out;
    }
}
