package dev.vitruvius.editor.gizmo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vitruvius.editor.core.Box;
import dev.vitruvius.editor.core.Vec3i;
import dev.vitruvius.editor.pick.ViewMath;
import org.junit.jupiter.api.Test;

class GizmoAnchorTest {
    private static final double W = 1000, H = 600, FOV = 70;
    /** Looking along +Z from 20 blocks in front of the origin. */
    private static final ViewMath.Basis B = ViewMath.Basis.of(0, 0);
    private static final double[] CAM = {0.5, 0.5, -20};

    @Test
    void aSmallSelectionKeepsItsPivot() {
        Box box = new Box(new Vec3i(-2, -2, 0), new Vec3i(3, 3, 5));
        Vec3i pivot = new Vec3i(0, 0, 2);
        assertEquals(pivot, GizmoAnchor.choose(B, CAM, FOV, W, H, pivot, box));
    }

    @Test
    void aLongSelectionGetsTheGizmoAtItsNearEnd() {
        Box box = new Box(new Vec3i(-2, -2, 0), new Vec3i(3, 3, 400));
        Vec3i pivot = new Vec3i(0, 0, 200);
        Vec3i at = GizmoAnchor.choose(B, CAM, FOV, W, H, pivot, box);
        assertTrue(at.z() < 10, at.toString());
        assertTrue(box.contains(at));
    }

    @Test
    void aPivotOutsideTheWindowMovesToTheVisiblePart() {
        Box box = new Box(new Vec3i(-300, -2, 0), new Vec3i(300, 3, 6));
        Vec3i pivot = new Vec3i(-290, 0, 2);      // far to the side, off screen
        Vec3i at = GizmoAnchor.choose(B, CAM, FOV, W, H, pivot, box);
        double[] p = ViewMath.project(B, CAM, FOV, W, H, at.x() + 0.5, at.y() + 0.5, at.z() + 0.5);
        assertTrue(p != null && p[0] >= W * GizmoAnchor.MARGIN && p[0] <= W * (1 - GizmoAnchor.MARGIN), at.toString());
    }

    @Test
    void withoutABoxThePivotStays() {
        Vec3i pivot = new Vec3i(1, 2, 3);
        assertEquals(pivot, GizmoAnchor.choose(B, CAM, FOV, W, H, pivot, null));
        assertNull(GizmoAnchor.choose(B, CAM, FOV, W, H, null, null));
    }

    @Test
    void nothingVisibleKeepsThePivot() {
        // the whole selection is behind the camera
        Box box = new Box(new Vec3i(-2, -2, -100), new Vec3i(3, 3, -50));
        Vec3i pivot = new Vec3i(0, 0, -75);
        assertEquals(pivot, GizmoAnchor.choose(B, CAM, FOV, W, H, pivot, box));
    }
}
