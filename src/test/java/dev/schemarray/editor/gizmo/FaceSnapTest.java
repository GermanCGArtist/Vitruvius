package dev.schemarray.editor.gizmo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import dev.schemarray.editor.core.Box;
import dev.schemarray.editor.core.Vec3i;
import dev.schemarray.editor.gizmo.FaceSnap.Target;
import org.junit.jupiter.api.Test;

import java.util.List;

class FaceSnapTest {
    private static Box box(int x0, int y0, int z0, int x1, int y1, int z1) {
        return new Box(new Vec3i(x0, y0, z0), new Vec3i(x1, y1, z1));
    }

    private final Box moving = box(0, 0, 0, 4, 4, 4);
    private final Target wall = new Target("wall", box(6, 0, 0, 10, 4, 4));

    @Test
    void buttsAFaceThatIsWithinRange() {
        FaceSnap.Result r = FaceSnap.solve(moving, 0, 1, List.of(wall));    // right face at 5, wall starts at 6
        assertNotNull(r);
        assertEquals(2, r.delta());
        assertEquals("wall", r.target().name());
        assertEquals(6, r.face().coord());
        assertEquals(0, r.face().lo1());
        assertEquals(4, r.face().hi1());
    }

    @Test
    void nothingSnapsBeyondTheRange() {
        assertNull(FaceSnap.solve(moving, 0, 0, List.of(wall)));            // gap of 2 blocks
    }

    @Test
    void otherAxesMustBeNear() {
        Target far = new Target("far", box(6, 0, 10, 10, 4, 14));
        assertNull(FaceSnap.solve(moving, 0, 2, List.of(far)));
        Target near = new Target("near", box(6, 0, 5, 10, 4, 9));            // 1 block gap in Z
        assertNotNull(FaceSnap.solve(moving, 0, 2, List.of(near)));
    }

    @Test
    void alignsFlushFacesToo() {
        Target t = new Target("t", box(10, 0, 0, 14, 4, 4));
        FaceSnap.Result r = FaceSnap.solve(box(0, 0, 0, 3, 4, 4), 0, 11, List.of(t));   // box at 11..14, flush with the far face
        assertNotNull(r);
        assertEquals(11, r.delta());
        assertEquals(14, r.face().coord());
    }

    @Test
    void picksTheSmallestCorrectionAndButtingOnATie() {
        Target a = new Target("a", box(6, 0, 0, 10, 4, 4));
        Target b = new Target("b", box(5, 0, 0, 12, 4, 4));                  // right face at 5 is already flush at delta 1
        FaceSnap.Result r = FaceSnap.solve(moving, 0, 1, List.of(a, b));
        assertEquals(1, r.delta());
        assertEquals("b", r.target().name());
    }

    @Test
    void worksOnTheVerticalAxis() {
        Target floor = new Target("floor", box(0, -3, 0, 4, 0, 4));
        FaceSnap.Result r = FaceSnap.solve(moving, 1, 1, List.of(floor));       // bottom at 1, floor top at 0
        assertEquals(0, r.delta());
        assertEquals(0, r.face().coord());
    }

    @Test
    void planeSnapCorrectsXThenZ() {
        Target side = new Target("side", box(0, 0, 6, 4, 4, 10));
        FaceSnap.PlaneResult r = FaceSnap.solvePlane(moving, 1, 1, List.of(wall, side));
        assertEquals(2, r.dx());
        assertEquals(2, r.dz());
    }
}
