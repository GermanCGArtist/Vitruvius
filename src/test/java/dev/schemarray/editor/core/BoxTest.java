package dev.schemarray.editor.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import org.junit.jupiter.api.Test;

class BoxTest {

    static Box box(int x0, int y0, int z0, int x1, int y1, int z1) {
        return new Box(new Vec3i(x0, y0, z0), new Vec3i(x1, y1, z1));
    }

    @Test
    void sizeAndVolume() {
        Box b = Box.ofSize(new Vec3i(3, 2, 5));
        assertEquals(new Vec3i(3, 2, 5), b.size());
        assertEquals(30, b.volume());
        assertEquals(0, box(4, 4, 4, 4, 9, 9).volume());
        assertEquals(0, box(4, 4, 4, 3, 9, 9).volume());
    }

    @Test
    void containsIncludesMinAndExcludesMaxExclusive() {
        Box b = box(1, 1, 1, 3, 3, 3);
        assertTrue(b.contains(new Vec3i(1, 1, 1)));
        assertTrue(b.contains(new Vec3i(2, 2, 2)));
        assertFalse(b.contains(new Vec3i(3, 2, 2)));
        assertFalse(b.contains(new Vec3i(2, 0, 2)));
    }

    @Test
    void intersectOverlapping() {
        Optional<Box> i = box(0, 0, 0, 2, 2, 2).intersect(box(1, 1, 1, 4, 4, 4));
        assertEquals(Optional.of(box(1, 1, 1, 2, 2, 2)), i);
        assertEquals(1, i.get().volume());
    }

    @Test
    void intersectTouchingFacesIsEmpty() {
        assertEquals(Optional.empty(), box(0, 0, 0, 2, 2, 2).intersect(box(2, 0, 0, 4, 2, 2)));
        assertEquals(Optional.empty(), box(0, 0, 0, 2, 2, 2).intersect(box(0, 2, 0, 2, 4, 2)));
        assertEquals(Optional.empty(), box(0, 0, 0, 2, 2, 2).intersect(box(0, 0, 2, 2, 2, 4)));
    }

    @Test
    void intersectTouchingOnlyAlongAnEdgeIsEmpty() {
        assertEquals(Optional.empty(), box(0, 0, 0, 2, 2, 2).intersect(box(2, 2, 0, 4, 4, 2)));
    }

    @Test
    void intersectDisjointIsEmpty() {
        assertEquals(Optional.empty(), box(0, 0, 0, 2, 2, 2).intersect(box(5, 5, 5, 6, 6, 6)));
    }

    @Test
    void intersectContainedAndSelf() {
        Box outer = box(0, 0, 0, 10, 10, 10), inner = box(2, 3, 4, 5, 6, 7);
        assertEquals(Optional.of(inner), outer.intersect(inner));
        assertEquals(Optional.of(inner), inner.intersect(outer));
        assertEquals(Optional.of(inner), inner.intersect(inner));
    }

    @Test
    void intersectWithEmptyBoxIsEmpty() {
        assertEquals(Optional.empty(), box(0, 0, 0, 2, 2, 2).intersect(box(1, 1, 1, 1, 5, 5)));
    }

    @Test
    void unionOfTouchingBoxesMerges() {
        assertEquals(box(0, 0, 0, 4, 2, 2), box(0, 0, 0, 2, 2, 2).union(box(2, 0, 0, 4, 2, 2)));
    }

    @Test
    void unionOfDisjointBoxesIsTheEnclosingBox() {
        assertEquals(box(0, 0, 0, 6, 6, 6), box(0, 0, 0, 2, 2, 2).union(box(5, 5, 5, 6, 6, 6)));
    }

    @Test
    void unionIsSymmetricAndContainsBoth() {
        Box a = box(-3, 0, 1, 2, 4, 2), b = box(0, -1, 0, 1, 1, 9);
        assertEquals(a.union(b), b.union(a));
        assertEquals(box(-3, -1, 0, 2, 4, 9), a.union(b));
    }

    @Test
    void unionWithEmptyBoxAddsNothing() {
        Box a = box(0, 0, 0, 2, 2, 2);
        assertEquals(a, a.union(box(7, 7, 7, 7, 9, 9)));
        assertEquals(a, box(7, 7, 7, 7, 9, 9).union(a));
    }

    @Test
    void singleBlockStaysOnItsBlockUnderMirror() {
        // block 0 maps to block 0 under a negation, so the one-block box stays [0, 1)
        Box one = Box.ofSize(new Vec3i(1, 1, 1));
        assertEquals(one, one.transformed(new Transform(Vec3i.ZERO, Rot.NONE, Mirror.FRONT_BACK)));
        assertEquals(one, one.transformed(new Transform(Vec3i.ZERO, Rot.CW_90, Mirror.NONE)));
    }

    @Test
    void negatedBlockRangeUsesBlockPositionsNotContinuousEdges() {
        // blocks x = 0 and x = 1 become x = 0 and x = -1: the range is [-1, 1), not the continuous [-2, 0)
        Box two = box(0, 0, 0, 2, 1, 1);
        assertEquals(box(-1, 0, 0, 1, 1, 1), two.transformed(new Transform(Vec3i.ZERO, Rot.NONE, Mirror.FRONT_BACK)));
    }

    @Test
    void quarterTurnOfAOneByTwoRow() {
        // blocks z = 0 and z = 1 under CW_90 (x' = -z): x' = 0 and x' = -1, z' = x = 0
        Box row = box(0, 0, 0, 1, 1, 2);
        assertEquals(box(-1, 0, 0, 1, 1, 1), row.transformed(new Transform(Vec3i.ZERO, Rot.CW_90, Mirror.NONE)));
    }

    @Test
    void transformedOfThreeByTwoByFiveAtNonZeroPosForAllTwelvePairs() {
        // block range x 0..2, y 0..1, z 0..4 at pos (10, 20, 30); min and maxExclusive worked by hand
        Vec3i pos = new Vec3i(10, 20, 30);
        Box b = Box.ofSize(new Vec3i(3, 2, 5));
        Object[][] expected = {
            {Rot.NONE, Mirror.NONE, box(10, 20, 30, 13, 22, 35)},
            {Rot.CW_90, Mirror.NONE, box(6, 20, 30, 11, 22, 33)},
            {Rot.CW_180, Mirror.NONE, box(8, 20, 26, 11, 22, 31)},
            {Rot.CCW_90, Mirror.NONE, box(10, 20, 28, 15, 22, 31)},
            {Rot.NONE, Mirror.LEFT_RIGHT, box(10, 20, 26, 13, 22, 31)},
            {Rot.CW_90, Mirror.LEFT_RIGHT, box(10, 20, 30, 15, 22, 33)},
            {Rot.CW_180, Mirror.LEFT_RIGHT, box(8, 20, 30, 11, 22, 35)},
            {Rot.CCW_90, Mirror.LEFT_RIGHT, box(6, 20, 28, 11, 22, 31)},
            {Rot.NONE, Mirror.FRONT_BACK, box(8, 20, 30, 11, 22, 35)},
            {Rot.CW_90, Mirror.FRONT_BACK, box(6, 20, 28, 11, 22, 31)},
            {Rot.CW_180, Mirror.FRONT_BACK, box(10, 20, 26, 13, 22, 31)},
            {Rot.CCW_90, Mirror.FRONT_BACK, box(10, 20, 30, 15, 22, 33)},
        };
        for (Object[] row : expected) {
            Rot r = (Rot) row[0];
            Mirror m = (Mirror) row[1];
            Box want = (Box) row[2];
            assertEquals(want, b.transformed(new Transform(pos, r, m)), r + " " + m);
        }
    }

    @Test
    void enclosingBoxContainsEveryTransformedBlockAndIsTight() {
        Box b = box(-1, 0, 2, 2, 3, 6);
        Vec3i pos = new Vec3i(-7, 4, 9);
        for (Rot r : Rot.values()) {
            for (Mirror m : Mirror.values()) {
                Transform t = new Transform(pos, r, m);
                Box out = b.transformed(t);
                Vec3i lo = null, hi = null;
                for (int x = -1; x < 2; x++)
                    for (int y = 0; y < 3; y++)
                        for (int z = 2; z < 6; z++) {
                            Vec3i p = t.apply(new Vec3i(x, y, z));
                            assertTrue(out.contains(p), r + " " + m + " " + p + " not in " + out);
                            lo = lo == null ? p : new Vec3i(Math.min(lo.x(), p.x()), Math.min(lo.y(), p.y()), Math.min(lo.z(), p.z()));
                            hi = hi == null ? p : new Vec3i(Math.max(hi.x(), p.x()), Math.max(hi.y(), p.y()), Math.max(hi.z(), p.z()));
                        }
                assertEquals(lo, out.min(), r + " " + m);
                assertEquals(hi.add(new Vec3i(1, 1, 1)), out.maxExclusive(), r + " " + m);
                assertEquals(b.volume(), out.volume());
            }
        }
    }

    @Test
    void transformedOfEmptyBoxThrows() {
        assertThrows(IllegalArgumentException.class, () -> box(1, 1, 1, 1, 2, 2).transformed(Transform.IDENTITY));
    }
}
