package dev.schemarray.shape;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class PatternsTest {
    private static List<BlockEntry> entries(String... names) {
        List<BlockEntry> list = new ArrayList<>();
        for (String n : names) list.add(new BlockEntry(n, 1));
        return list;
    }

    private static BlockSlot slot(PatternMode mode, List<BlockEntry> e, int cx, int cy, int cz, Axis axis, int width,
                                  long seed, boolean dither) {
        return new BlockSlot(mode, e, cx, cy, cz, axis, width, seed, dither);
    }

    @Test
    void airSlotPicksNothing() {
        assertEquals(-1, Patterns.pick(BlockSlot.air(), 1, 2, 3, 9, 9, 9));
    }

    @Test
    void chessboardAlternates() {
        BlockSlot s = slot(PatternMode.CHECKER, entries("a", "b"), 1, 1, 1, Axis.Y, 1, 0, false);
        for (int x = 0; x < 6; x++) {
            for (int z = 0; z < 6; z++) {
                int here = Patterns.pick(s, x, 0, z, 7, 2, 7);
                assertNotEquals(here, Patterns.pick(s, x + 1, 0, z, 7, 2, 7));
                assertNotEquals(here, Patterns.pick(s, x, 0, z + 1, 7, 2, 7));
                assertNotEquals(here, Patterns.pick(s, x, 1, z, 7, 2, 7));
            }
        }
        BlockSlot big = slot(PatternMode.CHECKER, entries("a", "b"), 2, 1, 1, Axis.Y, 1, 0, false);
        assertEquals(0, Patterns.pick(big, 0, 0, 0, 9, 9, 9));
        assertEquals(0, Patterns.pick(big, 1, 0, 0, 9, 9, 9));
        assertEquals(1, Patterns.pick(big, 2, 0, 0, 9, 9, 9));
    }

    @Test
    void stripesUseWidthAlongTheAxis() {
        BlockSlot s = slot(PatternMode.STRIPES, entries("a", "b"), 1, 1, 1, Axis.X, 2, 0, false);
        int[] expected = {0, 0, 1, 1, 0, 0, 1, 1};
        for (int x = 0; x < expected.length; x++) {
            assertEquals(expected[x], Patterns.pick(s, x, 5, 5, 8, 9, 9));
        }
    }

    @Test
    void gradientRunsInOrderAlongTheAxis() {
        BlockSlot hard = slot(PatternMode.GRADIENT, entries("a", "b", "c"), 1, 1, 1, Axis.Y, 1, 0, false);
        assertEquals(0, Patterns.pick(hard, 0, 0, 0, 9, 9, 9));
        assertEquals(1, Patterns.pick(hard, 0, 4, 0, 9, 9, 9));
        assertEquals(2, Patterns.pick(hard, 0, 8, 0, 9, 9, 9));
        int last = 0;
        for (int y = 0; y < 9; y++) {
            int p = Patterns.pick(hard, 3, y, 3, 9, 9, 9);
            assertTrue(p >= last);
            last = p;
        }

        BlockSlot dithered = slot(PatternMode.GRADIENT, entries("a", "b", "c"), 1, 1, 1, Axis.Y, 1, 0, true);
        assertEquals(0, Patterns.pick(dithered, 0, 0, 0, 9, 9, 9));
        assertEquals(2, Patterns.pick(dithered, 0, 8, 0, 9, 9, 9));
        boolean sawLow = false, sawNext = false;
        for (int x = 0; x < 4; x++) {
            for (int z = 0; z < 4; z++) {
                int p = Patterns.pick(dithered, x, 2, z, 9, 9, 9);
                assertTrue(p == 0 || p == 1);
                sawLow |= p == 0;
                sawNext |= p == 1;
            }
        }
        assertTrue(sawLow && sawNext, "dither blends neighbouring bands");
    }

    @Test
    void mixIsDeterministicAndFollowsTheWeights() {
        List<BlockEntry> e = List.of(new BlockEntry("a", 3), new BlockEntry("b", 1));
        BlockSlot s = slot(PatternMode.MIX, e, 1, 1, 1, Axis.Y, 1, 42, false);
        BlockSlot other = slot(PatternMode.MIX, e, 1, 1, 1, Axis.Y, 1, 43, false);
        int[] counts = new int[2];
        boolean differs = false;
        for (int x = 0; x < 40; x++) {
            for (int y = 0; y < 20; y++) {
                for (int z = 0; z < 25; z++) {
                    int p = Patterns.pick(s, x, y, z, 40, 20, 25);
                    assertEquals(p, Patterns.pick(s, x, y, z, 40, 20, 25));
                    counts[p]++;
                    differs |= p != Patterns.pick(other, x, y, z, 40, 20, 25);
                }
            }
        }
        double ratio = counts[0] / (double) counts[1];
        assertTrue(ratio > 2.7 && ratio < 3.3, "ratio " + ratio);
        assertTrue(differs, "the seed changes the result");
    }

    @Test
    void diagonalSumsTheNormalAxes() {
        BlockSlot s = slot(PatternMode.DIAGONAL, entries("a", "b"), 1, 1, 1, Axis.Y, 1, 0, false);
        assertEquals(0, Patterns.pick(s, 1, 7, 1, 9, 9, 9));
        assertEquals(1, Patterns.pick(s, 1, 7, 2, 9, 9, 9));
        assertEquals(1, Patterns.pick(s, 3, 0, 0, 9, 9, 9));
    }

    @Test
    void bricksShiftEveryOtherRow() {
        BlockSlot s = slot(PatternMode.BRICK, entries("brick", "mortar"), 4, 1, 1, Axis.Y, 1, 0, false);
        for (int x = 0; x < 20; x++) {
            assertEquals(x % 5 == 4 ? 1 : 0, Patterns.pick(s, x, 0, 0, 20, 4, 1), "row 0 x=" + x);
            assertEquals(1, Patterns.pick(s, x, 1, 0, 20, 4, 1), "mortar row x=" + x);
            assertEquals((x + 2) % 5 == 4 ? 1 : 0, Patterns.pick(s, x, 2, 0, 20, 4, 1), "row 2 x=" + x);
        }
        BlockSlot noMortar = slot(PatternMode.BRICK, entries("a", "b"), 2, 1, 1, Axis.Y, 1, 0, false);
        assertEquals(0, Patterns.pick(noMortar, 0, 0, 0, 8, 8, 1));
    }
}
