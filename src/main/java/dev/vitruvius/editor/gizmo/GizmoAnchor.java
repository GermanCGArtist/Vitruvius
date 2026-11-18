package dev.vitruvius.editor.gizmo;

import dev.vitruvius.editor.core.Box;
import dev.vitruvius.editor.core.Vec3i;
import dev.vitruvius.editor.pick.ViewMath;

/**
 * Where the move gizmo is drawn. A move does not care where its arrows start (the drag measures differences), so for a big
 * selection, such as a long array, the gizmo is put on the part of the selection that is close to the camera and inside
 * the window instead of at the pivot, which can be far away at the other end. The Y ring (rotate) keeps the real pivot.
 * Pure.
 */
public final class GizmoAnchor {
    private GizmoAnchor() {}

    /** The part of the window that counts as comfortable: this share of the width and height is kept free on every side. */
    public static final double MARGIN = 0.12;
    /** The pivot is kept while it is not farther from the camera than this times the closest part of the selection (plus a few blocks). */
    public static final double FAR_RATIO = 1.6;
    private static final double FAR_SLACK = 6;
    private static final int STEPS = 8;

    /**
     * @param pivot the pivot block of the selection
     * @param union the box around the selection (may be null: the pivot is kept)
     * @return the block to draw the move gizmo on: the pivot, or a block of {@code union} near the camera
     */
    public static Vec3i choose(ViewMath.Basis b, double[] cam, double fovDeg, double w, double h, Vec3i pivot, Box union) {
        if (union == null || pivot == null) return pivot;
        double[] pc = {pivot.x() + 0.5, pivot.y() + 0.5, pivot.z() + 0.5};
        double near = nearestDistance(cam, union);
        double[] p = ViewMath.project(b, cam, fovDeg, w, h, pc[0], pc[1], pc[2]);
        boolean comfortable = p != null && inside(p, w, h) && dist(cam, pc) <= FAR_RATIO * near + FAR_SLACK;
        if (comfortable) return pivot;

        Vec3i best = null;
        double bestD = Double.MAX_VALUE;
        for (int ix = 0; ix <= STEPS; ix++) {
            for (int iy = 0; iy <= STEPS; iy++) {
                for (int iz = 0; iz <= STEPS; iz++) {
                    Vec3i blk = new Vec3i(lerp(union.min().x(), union.maxExclusive().x(), ix), lerp(union.min().y(), union.maxExclusive().y(), iy),
                            lerp(union.min().z(), union.maxExclusive().z(), iz));
                    double[] c = {blk.x() + 0.5, blk.y() + 0.5, blk.z() + 0.5};
                    double[] q = ViewMath.project(b, cam, fovDeg, w, h, c[0], c[1], c[2]);
                    if (q == null || !inside(q, w, h)) continue;
                    double d = dist(cam, c);
                    if (d < bestD) {
                        bestD = d;
                        best = blk;
                    }
                }
            }
        }
        return best == null ? pivot : best;
    }

    private static boolean inside(double[] p, double w, double h) {
        return p[0] >= w * MARGIN && p[0] <= w * (1 - MARGIN) && p[1] >= h * MARGIN && p[1] <= h * (1 - MARGIN);
    }

    /** A block coordinate at step {@code i} of {@link #STEPS} between the first and the last block of the box side. */
    private static int lerp(int min, int maxExclusive, int i) {
        int last = Math.max(min, maxExclusive - 1);
        return min + (int) Math.round((last - min) * (i / (double) STEPS));
    }

    private static double dist(double[] a, double[] b) {
        return Math.sqrt((a[0] - b[0]) * (a[0] - b[0]) + (a[1] - b[1]) * (a[1] - b[1]) + (a[2] - b[2]) * (a[2] - b[2]));
    }

    /** Distance from the camera to the closest point of the box. */
    static double nearestDistance(double[] cam, Box u) {
        double[] q = new double[3];
        int[] lo = {u.min().x(), u.min().y(), u.min().z()}, hi = {u.maxExclusive().x(), u.maxExclusive().y(), u.maxExclusive().z()};
        for (int i = 0; i < 3; i++) q[i] = Math.max(lo[i], Math.min(hi[i], cam[i]));
        return dist(cam, q);
    }
}
