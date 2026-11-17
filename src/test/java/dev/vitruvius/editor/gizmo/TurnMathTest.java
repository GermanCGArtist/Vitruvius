package dev.vitruvius.editor.gizmo;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.vitruvius.editor.core.Box;
import dev.vitruvius.editor.core.Vec3i;
import org.junit.jupiter.api.Test;

class TurnMathTest {
    private static Box box(int x0, int y0, int z0, int x1, int y1, int z1) {
        return new Box(new Vec3i(x0, y0, z0), new Vec3i(x1, y1, z1));
    }

    @Test
    void aQuarterTurnClockwiseSendsXToZ() {
        Box b = box(0, 0, 0, 4, 2, 2);
        assertEquals(box(-1, 0, 0, 1, 2, 4), TurnMath.rotated(b, Vec3i.ZERO, 1));
        assertEquals(box(0, 0, -3, 2, 2, 1), TurnMath.rotated(b, Vec3i.ZERO, -1));
    }

    @Test
    void aFullTurnAndTwoHalfTurnsChangeNothing() {
        Box b = box(3, 1, -2, 9, 5, 4);
        Vec3i p = new Vec3i(5, 2, 0);
        assertEquals(b, TurnMath.rotated(b, p, 4));
        assertEquals(b, TurnMath.rotated(TurnMath.rotated(b, p, 2), p, 2));
    }

    @Test
    void thePivotBlockStaysWhereItIs() {
        Box b = box(0, 0, 0, 7, 3, 7);                       // 7 wide: the middle block is 3
        assertEquals(b, TurnMath.rotated(b, new Vec3i(3, 0, 3), 1));
    }

    @Test
    void movedShiftsBothCorners() {
        assertEquals(box(2, 1, 0, 6, 3, 2), TurnMath.moved(box(0, 0, 0, 4, 2, 2), new Vec3i(2, 1, 0)));
    }

    @Test
    void dragAnglesAccumulateAcrossTheSeamAndRoundToQuarters() {
        assertEquals(-0.2, TurnMath.wrapDelta(Math.PI - 0.1, -Math.PI + 0.1), 1e-9);
        assertEquals(1, TurnMath.quarterTurns(Math.PI / 2 * 0.6));
        assertEquals(0, TurnMath.quarterTurns(Math.PI / 2 * 0.4));
        assertEquals(-2, TurnMath.quarterTurns(-Math.PI * 1.05));
        assertEquals(-1, TurnMath.quarterTurns(-Math.PI / 4));      // halves go away from zero
    }
}
