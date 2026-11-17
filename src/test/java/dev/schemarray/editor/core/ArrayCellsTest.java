package dev.schemarray.editor.core;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ArrayCellsTest {

    // ---- verbatim copy of ArraySync.copyCountOf/steps/cells (and ArrayStore.Mod), on plain ints ----
    static final class OldMod {
        boolean enabled = true;
        int count = 2;
        boolean useRelative = true;
        double[] relative = {1, 0, 0};
        boolean useConstant = false;
        int[] constant = {0, 0, 0};
    }

    static long oldCopyCountOf(List<OldMod> mods) {
        long n = 1;
        for (OldMod m : mods) {
            if (!m.enabled) continue;
            n *= Math.max(1, m.count);
            if (n > 1_000_000_000L) return n;
        }
        return n - 1;
    }

    static List<int[]> oldSteps(List<OldMod> mods, int[] size) {
        long[] min = {0, 0, 0}, max = {size[0] - 1, size[1] - 1, size[2] - 1};
        List<int[]> out = new ArrayList<>();
        for (OldMod m : mods) {
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

    static Map<String, int[]> oldCells(List<OldMod> mods, int[] size) {
        Map<String, int[]> out = new LinkedHashMap<>();
        if (!(oldCopyCountOf(mods) <= 1024) || oldCopyCountOf(mods) < 1) return out;
        List<OldMod> on = new ArrayList<>();
        for (OldMod m : mods) if (m.enabled) on.add(m);
        List<int[]> steps = oldSteps(mods, size);
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

    // ---- helpers ----
    private static OldMod old(boolean enabled, int count, boolean useRel, double[] rel, boolean useConst, int[] con) {
        OldMod m = new OldMod();
        m.enabled = enabled;
        m.count = count;
        m.useRelative = useRel;
        m.relative = rel;
        m.useConstant = useConst;
        m.constant = con;
        return m;
    }

    private static ArrayModifier neu(OldMod o) {
        ArrayModifier m = new ArrayModifier();
        m.enabled = o.enabled;
        m.count = o.count;
        m.useRelative = o.useRelative;
        m.relative = o.relative.clone();
        m.useConstant = o.useConstant;
        m.constant = o.constant.clone();
        return m;
    }

    private static void assertSame(List<OldMod> olds, int[] size) {
        List<ArrayModifier> mods = new ArrayList<>();
        for (OldMod o : olds) mods.add(neu(o));
        Vec3i sz = new Vec3i(size[0], size[1], size[2]);
        assertEquals(oldCopyCountOf(olds), ArrayCells.copyCountOf(mods));
        assertEquals(oldCopyCountOf(olds) <= 1024, ArrayCells.allowed(mods));
        List<int[]> os = oldSteps(olds, size);
        List<Vec3i> ns = ArrayCells.steps(mods, sz);
        assertEquals(os.size(), ns.size());
        for (int i = 0; i < os.size(); i++) assertArrayEquals(os.get(i), new int[]{ns.get(i).x(), ns.get(i).y(), ns.get(i).z()});
        Map<String, int[]> oc = oldCells(olds, size);
        Map<String, Vec3i> nc = ArrayCells.cells(mods, sz);
        assertEquals(new ArrayList<>(oc.keySet()), new ArrayList<>(nc.keySet()), "same keys in the same order");
        for (var e : oc.entrySet()) {
            Vec3i v = nc.get(e.getKey());
            assertArrayEquals(e.getValue(), new int[]{v.x(), v.y(), v.z()}, e.getKey());
        }
    }

    @Test
    void noModifiersMakeNoCells() {
        assertSame(List.of(), new int[]{5, 5, 5});
        assertTrue(ArrayCells.cells(List.of(), new Vec3i(5, 5, 5)).isEmpty());
        assertEquals(0, ArrayCells.copyCountOf(List.of()));
    }

    @Test
    void simpleRelativeRow() {
        OldMod m = old(true, 4, true, new double[]{1, 0, 0}, false, new int[3]);
        assertSame(List.of(m), new int[]{3, 2, 7});
        Map<String, Vec3i> c = ArrayCells.cells(List.of(neu(m)), new Vec3i(3, 2, 7));
        assertEquals(3, c.size());
        assertEquals(new Vec3i(3, 0, 0), c.get("1"));
        assertEquals(new Vec3i(9, 0, 0), c.get("3"));
    }

    @Test
    void twoDimensionalGridWithGaps() {
        OldMod a = old(true, 3, true, new double[]{1, 0, 0}, true, new int[]{2, 0, 0});
        OldMod b = old(true, 2, true, new double[]{0, 0, 1}, true, new int[]{0, 0, 1});
        assertSame(List.of(a, b), new int[]{4, 4, 4});
        assertSame(List.of(a, b), new int[]{1, 1, 1});
        Map<String, Vec3i> c = ArrayCells.cells(List.of(neu(a), neu(b)), new Vec3i(4, 4, 4));
        assertEquals(5, c.size());
        assertEquals(new Vec3i(12, 0, 5), c.get("2,1"));
    }

    @Test
    void secondModifierStepsOverTheGrownBox() {
        OldMod a = old(true, 3, true, new double[]{1, 0, 0}, false, new int[3]);
        OldMod b = old(true, 2, true, new double[]{1, 0, 0}, false, new int[3]);   // steps by the 3-wide row
        assertSame(List.of(a, b), new int[]{2, 2, 2});
        assertEquals(new Vec3i(6, 0, 0), ArrayCells.steps(List.of(neu(a), neu(b)), new Vec3i(2, 2, 2)).get(1));
    }

    @Test
    void negativeFractionalAndConstantOnly() {
        assertSame(List.of(old(true, 3, true, new double[]{-1, 0.5, 0}, false, new int[3])), new int[]{5, 5, 5});
        assertSame(List.of(old(true, 3, true, new double[]{0.5, 0.5, 0.5}, true, new int[]{-1, 1, 0})), new int[]{3, 3, 3});
        assertSame(List.of(old(true, 5, false, new double[]{1, 0, 0}, true, new int[]{0, 7, 0})), new int[]{3, 3, 3});
        assertSame(List.of(old(true, 3, false, new double[]{1, 0, 0}, false, new int[3])), new int[]{3, 3, 3});   // zero step: stacked copies
    }

    @Test
    void disabledAndDegenerateCounts() {
        OldMod off = old(false, 9, true, new double[]{1, 0, 0}, false, new int[3]);
        OldMod one = old(true, 1, true, new double[]{1, 0, 0}, false, new int[3]);
        OldMod zero = old(true, 0, true, new double[]{1, 0, 0}, false, new int[3]);
        OldMod two = old(true, 2, true, new double[]{0, 1, 0}, false, new int[3]);
        assertSame(List.of(off), new int[]{2, 2, 2});
        assertSame(List.of(one), new int[]{2, 2, 2});
        assertSame(List.of(zero, two), new int[]{2, 2, 2});
        assertSame(List.of(off, two, one), new int[]{2, 2, 2});
        // keys have one index per ENABLED modifier
        assertEquals(List.of("0,1"),
                new ArrayList<>(ArrayCells.cells(List.of(neu(zero), neu(two)), new Vec3i(2, 2, 2)).keySet()));
        assertEquals(List.of("1"), new ArrayList<>(ArrayCells.cells(List.of(neu(off), neu(two)), new Vec3i(2, 2, 2)).keySet()));
    }

    @Test
    void limitIsEnforced() {
        OldMod ok = old(true, 1025, true, new double[]{1, 0, 0}, false, new int[3]);     // 1024 copies: allowed
        OldMod over = old(true, 1026, true, new double[]{1, 0, 0}, false, new int[3]);   // 1025 copies: not
        OldMod huge1 = old(true, 100000, true, new double[]{1, 0, 0}, false, new int[3]);
        OldMod huge2 = old(true, 100000, true, new double[]{0, 1, 0}, false, new int[3]);
        assertSame(List.of(ok), new int[]{1, 1, 1});
        assertSame(List.of(over), new int[]{1, 1, 1});
        assertSame(List.of(huge1, huge2, huge1), new int[]{1, 1, 1});
        assertTrue(ArrayCells.allowed(List.of(neu(ok))));
        assertFalse(ArrayCells.allowed(List.of(neu(over))));
        assertEquals(1024, ArrayCells.cells(List.of(neu(ok)), new Vec3i(1, 1, 1)).size());
        assertTrue(ArrayCells.cells(List.of(neu(over)), new Vec3i(1, 1, 1)).isEmpty());
        assertEquals(1024, ArrayCells.MAX_COPIES);
    }

    @Test
    void manyCombinations() {
        double[] fr = {0, 1, -1, 0.5, 0.25, 1.5};
        int[] sizes = {1, 2, 5, 9};
        for (int c1 = 1; c1 <= 4; c1++) {
            for (double f : fr) {
                for (int s : sizes) {
                    OldMod a = old(true, c1, true, new double[]{f, 0, 1}, c1 % 2 == 0, new int[]{c1, -c1, 0});
                    OldMod b = old(c1 != 3, 3 - c1 % 3, true, new double[]{0, f, 0}, true, new int[]{0, 1, 2});
                    assertSame(List.of(a, b), new int[]{s, s + 1, 3});
                }
            }
        }
    }
}
