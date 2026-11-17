package dev.schemarray.editor.gizmo;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.schemarray.editor.core.Box;
import dev.schemarray.editor.core.Vec3i;
import dev.schemarray.editor.gizmo.Pivots.Mode;
import org.junit.jupiter.api.Test;

import java.util.List;

class PivotsTest {
    private static Box box(int x0, int y0, int z0, int x1, int y1, int z1) {
        return new Box(new Vec3i(x0, y0, z0), new Vec3i(x1, y1, z1));
    }

    @Test
    void aSingleBoxPivotsOnItsMiddleBlock() {
        assertEquals(new Vec3i(3, 1, 3), Pivots.compute(Mode.MEDIAN, List.of(box(0, 0, 0, 8, 4, 8)), null));
        assertEquals(new Vec3i(3, 0, 3), Pivots.compute(Mode.BOUNDS, List.of(box(0, 0, 0, 7, 1, 7)), null));
        assertEquals(new Vec3i(13, 0, 3), Pivots.compute(Mode.MEDIAN, List.of(box(10, 0, 0, 17, 1, 7)), null));
    }

    @Test
    void medianAveragesTheCentresBoundsUsesTheUnion() {
        Box a = box(0, 0, 0, 2, 2, 2), b = box(10, 0, 0, 18, 2, 2);          // centres x 1 and 14
        assertEquals(new Vec3i(7, 0, 0), Pivots.compute(Mode.MEDIAN, List.of(a, b), null));  // 7.5 -> block 7
        assertEquals(new Vec3i(8, 0, 0), Pivots.compute(Mode.BOUNDS, List.of(a, b), null));  // union 0..18, centre 9 -> 8
    }

    @Test
    void activeObjectUsesItsOwnBoxAndFallsBackToTheMedian() {
        Box a = box(0, 0, 0, 2, 2, 2), b = box(10, 0, 0, 18, 2, 2);
        assertEquals(new Vec3i(13, 0, 0), Pivots.compute(Mode.ACTIVE, List.of(a, b), b));
        assertEquals(new Vec3i(7, 0, 0), Pivots.compute(Mode.ACTIVE, List.of(a, b), null));
    }

    @Test
    void emptySelectionIsTheOrigin() {
        assertEquals(Vec3i.ZERO, Pivots.compute(Mode.MEDIAN, List.of(), null));
    }

    @Test
    void negativeCoordinatesUseTheMiddleBlock() {
        assertEquals(new Vec3i(-5, 0, 0), Pivots.compute(Mode.MEDIAN, List.of(box(-8, 0, 0, -1, 1, 1)), null));   // blocks -8..-2
    }
}
