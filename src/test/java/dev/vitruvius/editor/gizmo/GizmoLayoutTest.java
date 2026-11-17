package dev.vitruvius.editor.gizmo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vitruvius.editor.gizmo.GizmoLayout.Part;
import dev.vitruvius.editor.pick.ViewMath;
import org.junit.jupiter.api.Test;

class GizmoLayoutTest {
    private static final double W = 1000, H = 600, FOV = 70;
    private static final double[] PIVOT = {0.5, 0.5, 0.5};

    /** A camera 12 blocks from the pivot, looking at it with the given yaw and pitch. */
    private static GizmoLayout view(float yaw, float pitch, double sc) {
        ViewMath.Basis b = ViewMath.Basis.of(yaw, pitch);
        double dist = 12;
        double[] cam = {PIVOT[0] - b.fx() * dist, PIVOT[1] - b.fy() * dist, PIVOT[2] - b.fz() * dist};
        return GizmoLayout.of(b, cam, FOV, W, H, PIVOT, sc);
    }

    @Test
    void theOriginProjectsToTheMiddleWhenLookedAt() {
        GizmoLayout g = view(30, 35, 1);
        assertEquals(W / 2, g.ox, 1e-6);
        assertEquals(H / 2, g.oy, 1e-6);
    }

    @Test
    void arrowsKeepAConstantPixelLengthWhateverTheDistance() {
        GizmoLayout near = view(30, 35, 1);
        ViewMath.Basis b = ViewMath.Basis.of(30, 35);
        double[] cam = {PIVOT[0] - b.fx() * 40, PIVOT[1] - b.fy() * 40, PIVOT[2] - b.fz() * 40};
        GizmoLayout far = GizmoLayout.of(b, cam, FOV, W, H, PIVOT, 1);
        double[] t1 = near.along(0, GizmoLayout.SHAFT_TO), t2 = far.along(0, GizmoLayout.SHAFT_TO);
        assertEquals(Math.hypot(t1[0] - near.ox, t1[1] - near.oy), Math.hypot(t2[0] - far.ox, t2[1] - far.oy), 1e-6);
    }

    @Test
    void hitsAnArrowNearItsShaftAndNothingFarAway() {
        GizmoLayout g = view(30, 35, 1);
        for (int a = 0; a < 3; a++) {
            double[] p = g.along(a, 50);
            assertEquals(a == 0 ? Part.X : a == 1 ? Part.Y : Part.Z, g.hit(p[0] + 3, p[1] + 2, false), "axis " + a);
        }
        assertEquals(Part.NONE, g.hit(g.ox + 400, g.oy + 200, true));
    }

    @Test
    void theArrowAlongTheViewDirectionIsUnusable() {
        GizmoLayout g = view(0, 0, 1);                       // looking along +Z: Z projects to a point
        assertFalse(g.arrow(2).usable());
        assertTrue(g.arrow(0).usable());
        assertTrue(g.arrow(1).usable());
        assertFalse(g.planeVisible());
    }

    @Test
    void thePlaneHandleShowsFromAboveAndIsHit() {
        GizmoLayout g = view(30, 45, 1);
        assertTrue(g.planeVisible());
        double[][] q = g.planeCorners();
        double cx = (q[0][0] + q[2][0]) / 2, cy = (q[0][1] + q[2][1]) / 2;
        assertEquals(Part.PLANE, g.hit(cx, cy, true));
        assertTrue(g.hit(cx, cy, false) != Part.PLANE);
    }

    @Test
    void thePlaneHidesWhenSeenEdgeOn() {
        assertFalse(view(30, 2, 1).planeVisible());
    }

    @Test
    void behindTheCameraThereIsNoLayout() {
        ViewMath.Basis b = ViewMath.Basis.of(0, 0);
        assertNull(GizmoLayout.of(b, new double[] {0, 0, 10}, FOV, W, H, PIVOT, 1));
        assertNotNull(GizmoLayout.of(b, new double[] {0, 0, -10}, FOV, W, H, PIVOT, 1));
    }

    @Test
    void worldLengthOfTheRingScalesWithDistance() {
        double r10 = GizmoLayout.worldLen(72, 10, FOV, H), r20 = GizmoLayout.worldLen(72, 20, FOV, H);
        assertEquals(2 * r10, r20, 1e-9);
    }

    @Test
    void ringHitTestMeasuresToThePolyline() {
        ViewMath.Basis b = ViewMath.Basis.of(30, 40);
        double[] cam = {PIVOT[0] - b.fx() * 12, PIVOT[1] - b.fy() * 12, PIVOT[2] - b.fz() * 12};
        double r = GizmoLayout.worldLen(GizmoLayout.RING_RADIUS, 12, FOV, H);
        double[][] ring = GizmoLayout.ring(b, cam, FOV, W, H, PIVOT, r, 64);
        double[] p = ring[10];
        assertTrue(GizmoLayout.distToPolyline(ring, p[0], p[1]) < 1e-6);
        assertTrue(GizmoLayout.distToPolyline(ring, W / 2, H / 2) > 20, "the centre is far from the ring");
    }
}
