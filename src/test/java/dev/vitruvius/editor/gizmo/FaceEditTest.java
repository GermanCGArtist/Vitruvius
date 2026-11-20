package dev.vitruvius.editor.gizmo;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import dev.vitruvius.editor.core.Box;
import dev.vitruvius.editor.core.Mirror;
import dev.vitruvius.editor.core.Rot;
import dev.vitruvius.editor.core.Transform;
import dev.vitruvius.editor.core.Vec3i;
import java.util.List;
import org.junit.jupiter.api.Test;

class FaceEditTest {
    private static final Box CUBE = new Box(new Vec3i(0, 0, 0), new Vec3i(4, 4, 4));

    @Test
    void aRayFromOutsideMeetsTheNearSide() {
        FaceEdit.Face f = FaceEdit.pick(List.of(CUBE), new double[] {-5, 2, 2}, new double[] {1, 0, 0}, 100);
        assertEquals(0, f.box());
        assertEquals(0, f.axis());
        assertEquals(-1, f.sign());
        assertEquals(5.0, f.t(), 1e-9);
    }

    @Test
    void aRayFromInsideMeetsTheFarSide() {
        FaceEdit.Face f = FaceEdit.pick(List.of(CUBE), new double[] {2, 2, 2}, new double[] {0, 1, 0}, 100);
        assertEquals(1, f.axis());
        assertEquals(1, f.sign());
        assertEquals(2.0, f.t(), 1e-9);
    }

    @Test
    void theNearestBoxWinsAndAMissIsNull() {
        Box far = new Box(new Vec3i(10, 0, 0), new Vec3i(12, 4, 4));
        FaceEdit.Face f = FaceEdit.pick(List.of(far, CUBE), new double[] {-5, 2, 2}, new double[] {1, 0, 0}, 100);
        assertEquals(1, f.box());
        assertNull(FaceEdit.pick(List.of(CUBE), new double[] {-5, 9, 2}, new double[] {1, 0, 0}, 100));
        assertNull(FaceEdit.pick(List.of(CUBE), new double[] {-500, 2, 2}, new double[] {1, 0, 0}, 100));
    }

    @Test
    void resizingMovesOneSideAndKeepsOneBlock() {
        assertEquals(new Box(new Vec3i(0, 0, 0), new Vec3i(7, 4, 4)), FaceEdit.resized(CUBE, 0, 1, 3));
        assertEquals(new Box(new Vec3i(-2, 0, 0), new Vec3i(4, 4, 4)), FaceEdit.resized(CUBE, 0, -1, 2));
        assertEquals(new Box(new Vec3i(0, 0, 0), new Vec3i(4, 4, 1)), FaceEdit.resized(CUBE, 2, 1, -9));
        assertEquals(new Box(new Vec3i(0, 3, 0), new Vec3i(4, 4, 4)), FaceEdit.resized(CUBE, 1, -1, -9));
    }

    @Test
    void aSlabSitsOutsideTheSide() {
        assertEquals(new Box(new Vec3i(4, 0, 0), new Vec3i(6, 4, 4)), FaceEdit.slab(CUBE, 0, 1, 2));
        assertEquals(new Box(new Vec3i(0, -3, 0), new Vec3i(4, 0, 4)), FaceEdit.slab(CUBE, 1, -1, 3));
        assertNull(FaceEdit.slab(CUBE, 0, 1, 0));
    }

    @Test
    void aWorldSideMapsBackToTheLocalSide() {
        Transform turned = new Transform(new Vec3i(5, 0, 5), Rot.CW_90, Mirror.NONE);
        for (int axis = 0; axis < 3; axis += 2) {
            for (int sign = -1; sign <= 1; sign += 2) {
                int[] local = FaceEdit.localSide(turned, axis, sign);
                Vec3i unit = new Vec3i(local[0] == 0 ? local[1] : 0, local[0] == 1 ? local[1] : 0, local[0] == 2 ? local[1] : 0);
                Vec3i back = turned.applyVector(unit);
                assertEquals(sign, back.get(axis));
            }
        }
        Transform mirrored = new Transform(Vec3i.ZERO, Rot.NONE, Mirror.FRONT_BACK);
        assertArrayEquals(new int[] {0, -1}, FaceEdit.localSide(mirrored, 0, 1));
        assertArrayEquals(new int[] {1, 1}, FaceEdit.localSide(mirrored, 1, 1));
    }

    @Test
    void withLengthSetsExactLengthByMovingChosenSide() {
        assertEquals(new Box(new Vec3i(0, 0, 0), new Vec3i(12, 4, 4)), FaceEdit.withLength(CUBE, 0, 1, 12));
        assertEquals(new Box(new Vec3i(-8, 0, 0), new Vec3i(4, 4, 4)), FaceEdit.withLength(CUBE, 0, -1, 12));
        assertEquals(new Box(new Vec3i(0, 0, 0), new Vec3i(4, 1, 4)), FaceEdit.withLength(CUBE, 1, 1, 0));
        assertEquals(new Box(new Vec3i(0, 3, 0), new Vec3i(4, 4, 4)), FaceEdit.withLength(CUBE, 1, -1, -5));
    }

    @Test
    void typedLengthSupportsExactAndRelativeInput() {
        FaceEdit.TypedLength t = new FaceEdit.TypedLength();
        assertNull(t.apply(CUBE, 0, 1, false));
        t.digit(1);
        t.digit(0);
        assertEquals("10", t.text());
        assertEquals(new Box(new Vec3i(0, 0, 0), new Vec3i(10, 4, 4)), t.apply(CUBE, 0, 1, false));
        assertEquals(new Box(new Vec3i(4, 0, 0), new Vec3i(14, 4, 4)), t.apply(CUBE, 0, 1, true));

        // relative delta with leading - or +
        t.toggleSign('-');
        assertEquals("-10", t.text());
        assertEquals(new Box(new Vec3i(0, 0, 0), new Vec3i(1, 4, 4)), t.apply(CUBE, 0, 1, false));
        t.toggleSign('+');
        assertEquals("+10", t.text());
        assertEquals(new Box(new Vec3i(0, 0, 0), new Vec3i(14, 4, 4)), t.apply(CUBE, 0, 1, false));
        t.backspace();
        t.backspace();
        assertEquals("+", t.text());
        assertNull(t.apply(CUBE, 0, 1, false));
    }
}
