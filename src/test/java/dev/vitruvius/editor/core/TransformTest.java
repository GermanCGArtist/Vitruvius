package dev.vitruvius.editor.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class TransformTest {

    /** A copy of the rule in ArraySync.transform: mirror first, then the turn. */
    static Vec3i reference(Vec3i p, Mirror m, Rot r) {
        int x = p.x(), y = p.y(), z = p.z();
        if (m == Mirror.LEFT_RIGHT) z = -z;
        else if (m == Mirror.FRONT_BACK) x = -x;
        return switch (r) {
            case CW_90 -> new Vec3i(-z, y, x);
            case CCW_90 -> new Vec3i(z, y, -x);
            case CW_180 -> new Vec3i(-x, y, -z);
            default -> new Vec3i(x, y, z);
        };
    }

    static List<Vec3i> samples() {
        List<Vec3i> out = new ArrayList<>();
        for (int x = -2; x <= 2; x++)
            for (int y = -2; y <= 2; y++)
                for (int z = -2; z <= 2; z++) out.add(new Vec3i(x, y, z));
        return out;
    }

    static List<Transform> all12(Vec3i pos) {
        List<Transform> out = new ArrayList<>();
        for (Rot r : Rot.values())
            for (Mirror m : Mirror.values()) out.add(new Transform(pos, r, m));
        return out;
    }

    static void assertSameMap(Transform expected, Transform actual) {
        for (Vec3i p : samples()) {
            assertEquals(expected.apply(p), actual.apply(p), expected + " vs " + actual + " at " + p);
        }
    }

    @Test
    void rotCwIsPositiveSteps() {
        assertEquals(Rot.CW_90, Rot.NONE.rotate(1));
        assertEquals(Rot.CW_180, Rot.NONE.rotate(2));
        assertEquals(Rot.CCW_90, Rot.NONE.rotate(3));
        assertEquals(Rot.CCW_90, Rot.NONE.rotate(-1));
        assertEquals(Rot.NONE, Rot.NONE.rotate(4));
        assertEquals(Rot.CW_90, Rot.CCW_90.rotate(2));
        assertEquals(Rot.NONE, Rot.CW_90.rotate(-1));
        assertEquals(Rot.NONE, Rot.CW_90.rotate(-5));
    }

    @Test
    void stepsAndInverse() {
        assertEquals(0, Rot.NONE.steps());
        assertEquals(1, Rot.CW_90.steps());
        assertEquals(2, Rot.CW_180.steps());
        assertEquals(3, Rot.CCW_90.steps());
        for (Rot r : Rot.values()) {
            assertEquals(Rot.NONE, r.rotate(r.inverse().steps()), r.toString());
            assertEquals(Rot.NONE, r.inverse().rotate(r.steps()), r.toString());
        }
    }

    @Test
    void everyRotMirrorPairMatchesArraySyncRule() {
        for (Rot r : Rot.values()) {
            for (Mirror m : Mirror.values()) {
                Transform t = new Transform(Vec3i.ZERO, r, m);
                for (Vec3i p : samples()) {
                    assertEquals(reference(p, m, r), t.applyVector(p), r + " " + m + " " + p);
                    assertEquals(reference(p, m, r), t.apply(p), r + " " + m + " " + p);
                }
            }
        }
    }

    @Test
    void offsetIsAddedAfterTheLinearPart() {
        Vec3i pos = new Vec3i(3, -4, 5);
        for (Transform t : all12(pos)) {
            for (Vec3i p : samples()) {
                Vec3i expected = reference(p, t.mirror(), t.rot()).add(pos);
                assertEquals(expected, t.apply(p), t + " " + p);
                assertEquals(reference(p, t.mirror(), t.rot()), t.applyVector(p), t + " " + p);
            }
        }
    }

    @Test
    void handWorkedExample() {
        // CW_90 maps (x, y, z) to (-z, y, x): (1, 2, 3) -> (-3, 2, 1), then + (10, 0, 0)
        assertEquals(new Vec3i(7, 2, 1), new Transform(new Vec3i(10, 0, 0), Rot.CW_90, Mirror.NONE).apply(new Vec3i(1, 2, 3)));
        // FRONT_BACK negates x first, then CW_90: (1, 2, 3) -> (-1, 2, 3) -> (-3, 2, -1)
        assertEquals(new Vec3i(-3, 2, -1), new Transform(Vec3i.ZERO, Rot.CW_90, Mirror.FRONT_BACK).apply(new Vec3i(1, 2, 3)));
    }

    @Test
    void identityDoesNothing() {
        for (Vec3i p : samples()) assertEquals(p, Transform.IDENTITY.apply(p));
        assertEquals(new Transform(new Vec3i(1, 1, 1), Rot.NONE, Mirror.NONE), Transform.IDENTITY.withPos(new Vec3i(1, 1, 1)));
    }

    @Test
    void compositionMatchesNestedApplyForAllPairs() {
        List<Transform> ts = new ArrayList<>();
        int i = 0;
        for (Transform t : all12(Vec3i.ZERO)) {
            ts.add(t.withPos(new Vec3i(i, -2 * i, 2 * i + 1))); // a distinct offset each
            i++;
        }
        for (Transform parent : ts) {
            for (Transform child : ts) {
                Transform c = parent.then(child);
                for (Vec3i p : samples()) {
                    assertEquals(parent.apply(child.apply(p)), c.apply(p), parent + " then " + child + " at " + p);
                }
            }
        }
    }

    @Test
    void compositionIsAssociativeForAllTriples() {
        List<Transform> ts = all12(new Vec3i(2, -1, 4));
        for (Transform a : ts)
            for (Transform b : ts)
                for (Transform c : ts) {
                    Transform left = a.then(b).then(c);
                    Transform right = a.then(b.then(c));
                    assertSameMap(left, right);
                }
    }

    @Test
    void composingWithIdentityChangesNothing() {
        for (Transform t : all12(new Vec3i(5, 6, 7))) {
            assertSameMap(t, t.then(Transform.IDENTITY));
            assertSameMap(t, Transform.IDENTITY.then(t));
        }
    }

    @Test
    void twoMirrorsOfTheSameKindCancel() {
        Transform lr = new Transform(Vec3i.ZERO, Rot.NONE, Mirror.LEFT_RIGHT);
        Transform fb = new Transform(Vec3i.ZERO, Rot.NONE, Mirror.FRONT_BACK);
        assertEquals(Transform.IDENTITY, lr.then(lr));
        assertEquals(Transform.IDENTITY, fb.then(fb));
    }

    @Test
    void leftRightThenFrontBackIsAHalfTurn() {
        // LEFT_RIGHT then FRONT_BACK negates both x and z: a 180 degree turn, no mirror
        Transform t = new Transform(Vec3i.ZERO, Rot.NONE, Mirror.LEFT_RIGHT).then(new Transform(Vec3i.ZERO, Rot.NONE, Mirror.FRONT_BACK));
        assertEquals(Mirror.NONE, t.mirror());
        assertEquals(Rot.CW_180, t.rot());
    }

    @Test
    void mirrorPushesThroughTheTurnOfTheChild() {
        // a mirrored parent reverses the child's turn: LEFT_RIGHT then CW_90 is the same map as CCW_90 with LEFT_RIGHT
        Transform parent = new Transform(Vec3i.ZERO, Rot.NONE, Mirror.LEFT_RIGHT);
        Transform child = new Transform(Vec3i.ZERO, Rot.CW_90, Mirror.NONE);
        assertSameMap(new Transform(Vec3i.ZERO, Rot.CCW_90, Mirror.LEFT_RIGHT), parent.then(child));
    }

    @Test
    void vec3iArithmetic() {
        Vec3i a = new Vec3i(1, -2, 3), b = new Vec3i(4, 5, -6);
        assertEquals(new Vec3i(5, 3, -3), a.add(b));
        assertEquals(new Vec3i(-3, -7, 9), a.sub(b));
        assertEquals(new Vec3i(-1, 2, -3), a.neg());
        assertEquals(1, a.get(0));
        assertEquals(-2, a.get(1));
        assertEquals(3, a.get(2));
        assertThrows(IllegalArgumentException.class, () -> a.get(3));
    }
}
