package dev.vitruvius.editor.gizmo;

import dev.vitruvius.editor.core.Box;
import dev.vitruvius.editor.core.Vec3i;

import java.util.List;

/**
 * Face snapping while dragging (DESIGN 11.10): a face of the moving box is pulled onto a face of another object when
 * it is within {@link #RANGE} blocks along the drag axis, and the other object is near in the two other axes. Faces
 * can butt against each other (min to max, max to min) or lie flush (min to min, max to max). Butting wins a tie.
 */
public final class FaceSnap {
    private FaceSnap() {}

    public static final double RANGE = 1.5;

    /** A box another object offers, with the name the label shows. */
    public record Target(String name, Box box) {}

    /** The touching face: constant {@code coord} on {@code axis}, spanning [lo1,hi1] x [lo2,hi2] on the two other axes (ascending). */
    public record Face(int axis, int coord, double lo1, double hi1, double lo2, double hi2) {}

    /** The corrected delta along the axis, the target it snapped to and the face to highlight. */
    public record Result(int delta, Target target, Face face) {}

    private static int[] others(int axis) {
        return axis == 0 ? new int[] {1, 2} : axis == 1 ? new int[] {0, 2} : new int[] {0, 1};
    }

    private static int lo(Box b, int a) {
        return b.min().get(a);
    }

    private static int hi(Box b, int a) {
        return b.maxExclusive().get(a);
    }

    /** The box moved by {@code delta} blocks along one axis. */
    public static Box moved(Box b, int axis, int delta) {
        Vec3i d = new Vec3i(axis == 0 ? delta : 0, axis == 1 ? delta : 0, axis == 2 ? delta : 0);
        return new Box(b.min().add(d), b.maxExclusive().add(d));
    }

    private static int gap(Box a, Box b, int axis) {
        return Math.max(0, Math.max(lo(a, axis) - hi(b, axis), lo(b, axis) - hi(a, axis)));
    }

    /**
     * Looks for the best snap of {@code moving} (at its start position) moved by {@code delta} along {@code axis}.
     * Returns null when no face is within range.
     */
    public static Result solve(Box moving, int axis, int delta, List<Target> targets) {
        Box now = moved(moving, axis, delta);
        int[] o = others(axis);
        int m0 = lo(now, axis), m1 = hi(now, axis);
        Target bestTarget = null;
        int bestAdj = 0;
        double bestKey = Double.MAX_VALUE;
        for (Target t : targets) {
            Box tb = t.box();
            int g1 = gap(now, tb, o[0]), g2 = gap(now, tb, o[1]);
            if (g1 > RANGE || g2 > RANGE) continue;
            int t0 = lo(tb, axis), t1 = hi(tb, axis);
            int[][] cands = {{t1 - m0, 0}, {t0 - m1, 0}, {t0 - m0, 1}, {t1 - m1, 1}};
            for (int[] c : cands) {
                int adj = c[0];
                if (Math.abs(adj) > RANGE) continue;
                // key: distance first, then butting before flush, then the nearer object
                double key = Math.abs(adj) * 1000.0 + c[1] * 100.0 + (g1 + g2);
                if (key < bestKey) {
                    bestKey = key;
                    bestTarget = t;
                    bestAdj = adj;
                }
            }
        }
        if (bestTarget == null) return null;
        Box fin = moved(moving, axis, delta + bestAdj);
        return new Result(delta + bestAdj, bestTarget, face(fin, bestTarget.box(), axis));
    }

    private static Face face(Box mv, Box tb, int axis) {
        int[] o = others(axis);
        double[] span = new double[4];
        for (int k = 0; k < 2; k++) {
            int a = o[k];
            double l = Math.max(lo(mv, a), lo(tb, a)), h = Math.min(hi(mv, a), hi(tb, a));
            if (l >= h) {                       // no overlap: the strip between them
                l = Math.min(hi(mv, a), hi(tb, a));
                h = Math.max(lo(mv, a), lo(tb, a));
            }
            span[k * 2] = l;
            span[k * 2 + 1] = h;
        }
        // the touching coordinate: the face of the target the moving box now lies against
        int m0 = lo(mv, axis), m1 = hi(mv, axis), t0 = lo(tb, axis), t1 = hi(tb, axis);
        int coord = m0 == t1 ? t1 : m1 == t0 ? t0 : m0 == t0 ? t0 : t1;
        return new Face(axis, coord, span[0], span[1], span[2], span[3]);
    }

    /** What a plane drag found: the corrected deltas and the results per axis (null when that axis did not snap). */
    public record PlaneResult(int dx, int dz, Result x, Result z) {}

    /** Snaps X and then Z independently (the second sees the corrected first). */
    public static PlaneResult solvePlane(Box moving, int dx, int dz, List<Target> targets) {
        Result rx = solve(moving, 0, dx, targets);
        int nx = rx == null ? dx : rx.delta();
        Box shifted = moved(moving, 0, nx);
        Result rz = solve(shifted, 2, dz, targets);
        int nz = rz == null ? dz : rz.delta();
        return new PlaneResult(nx, nz, rx, rz);
    }
}
