package dev.schemarray.editor.gizmo;

/**
 * Turns a mouse ray into a position along an axis or on a plane, without Minecraft types. Rays are
 * {@code origin} and {@code direction} (3 doubles each); axes are 0 = X, 1 = Y, 2 = Z.
 */
public final class AxisProjection {
    private AxisProjection() {}

    /**
     * The parameter {@code t} of the point {@code p0 + t * e(axis)} on the axis line that is closest to the ray, or
     * NaN when the ray runs (almost) parallel to the axis (an arrow that points at the camera).
     */
    public static double axisParam(double[] o, double[] d, double[] p0, int axis) {
        double a = d[0] * d[0] + d[1] * d[1] + d[2] * d[2];
        double b = d[axis];
        double wd = 0;
        for (int i = 0; i < 3; i++) wd += d[i] * (o[i] - p0[i]);
        double w0 = o[axis] - p0[axis];
        double denom = a - b * b;
        if (denom < 1e-6 * a) return Double.NaN;
        return (a * w0 - b * wd) / denom;
    }

    /**
     * Where the ray meets the plane through {@code p0} whose normal is axis {@code normalAxis}: the two other
     * coordinates in ascending axis order (for Y: {x, z}), or null when parallel or behind the origin.
     */
    public static double[] planeHit(double[] o, double[] d, double[] p0, int normalAxis) {
        if (Math.abs(d[normalAxis]) < 1e-9) return null;
        double t = (p0[normalAxis] - o[normalAxis]) / d[normalAxis];
        if (t < 0) return null;
        double[] out = new double[2];
        int k = 0;
        for (int i = 0; i < 3; i++) {
            if (i != normalAxis) out[k++] = o[i] + t * d[i];
        }
        return out;
    }

    /** Whole blocks (rounds half up). */
    public static int blocks(double v) {
        return (int) Math.floor(v + 0.5);
    }
}
