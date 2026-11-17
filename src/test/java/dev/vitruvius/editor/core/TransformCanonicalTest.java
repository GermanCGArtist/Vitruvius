package dev.vitruvius.editor.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class TransformCanonicalTest {
    private static final Vec3i POS = new Vec3i(3, -4, 5);

    @Test
    void canonicalNeverUsesLeftRightAndKeepsTheMap() {
        for (Transform t : TransformTest.all12(POS)) {
            Transform c = t.canonical();
            assertTrue(c.mirror() == Mirror.NONE || c.mirror() == Mirror.FRONT_BACK, t.toString());
            TransformTest.assertSameMap(t, c);
            assertEquals(c, c.canonical(), "idempotent");
            assertEquals(POS, c.pos());
        }
    }

    @Test
    void leftRightBecomesFrontBackWithHalfTurn() {
        assertEquals(new Transform(POS, Rot.CW_180, Mirror.FRONT_BACK), new Transform(POS, Rot.NONE, Mirror.LEFT_RIGHT).canonical());
        assertEquals(new Transform(POS, Rot.CW_90, Mirror.FRONT_BACK), new Transform(POS, Rot.CCW_90, Mirror.LEFT_RIGHT).canonical());
    }

    @Test
    void equalMapsHaveEqualCanonicalForms() {
        List<Transform> all = TransformTest.all12(POS);
        for (Transform a : all) {
            for (Transform b : all) {
                boolean same = true;
                for (Vec3i p : TransformTest.samples()) {
                    if (!a.apply(p).equals(b.apply(p))) {
                        same = false;
                        break;
                    }
                }
                if (same) assertEquals(a.canonical(), b.canonical());
                else assertNotEquals(a.canonical(), b.canonical());
            }
        }
    }

    @Test
    void thenIsAlwaysCanonicalAndStillComposes() {
        List<Transform> parents = TransformTest.all12(new Vec3i(7, 1, -2));
        List<Transform> children = TransformTest.all12(new Vec3i(-3, 2, 9));
        int n = 0;
        for (Transform p : parents) {
            for (Transform c : children) {
                Transform r = p.then(c);
                assertTrue(r.mirror() != Mirror.LEFT_RIGHT, p + " then " + c);
                for (Vec3i v : TransformTest.samples()) assertEquals(p.apply(c.apply(v)), r.apply(v));
                n++;
            }
        }
        assertEquals(144, n);
    }
}
