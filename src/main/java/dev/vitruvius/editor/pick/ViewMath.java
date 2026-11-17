package dev.vitruvius.editor.pick;

/**
 * Camera maths without Minecraft types: the ray under a screen point and the screen point of a world point. The
 * conventions are those of {@code EditorCamera}: yaw 0 looks south (+Z), pitch positive looks down, the vertical
 * field of view is {@code fovDeg}, and screen coordinates start at the top left.
 */
public final class ViewMath {
    private ViewMath() {}

    /** The camera basis for a yaw and pitch (degrees). */
    public record Basis(double fx, double fy, double fz, double rx, double ry, double rz, double ux, double uy, double uz) {
        public static Basis of(float yawDeg, float pitchDeg) {
            double y = Math.toRadians(yawDeg), p = Math.toRadians(pitchDeg), cp = Math.cos(p);
            double fx = -Math.sin(y) * cp, fy = -Math.sin(p), fz = Math.cos(y) * cp;
            double rx = -Math.cos(y), ry = 0, rz = -Math.sin(y);
            // up = right x forward
            double ux = ry * fz - rz * fy, uy = rz * fx - rx * fz, uz = rx * fy - ry * fx;
            return new Basis(fx, fy, fz, rx, ry, rz, ux, uy, uz);
        }
    }

    /** The unit direction of the ray through the pixel (sx, sy) of a w x h viewport. */
    public static double[] ray(Basis b, double fovDeg, double w, double h, double sx, double sy) {
        double tan = Math.tan(Math.toRadians(fovDeg) * 0.5), aspect = w / h;
        double nx = (2 * sx / w - 1) * tan * aspect, ny = (1 - 2 * sy / h) * tan;
        double dx = b.fx() + b.rx() * nx + b.ux() * ny;
        double dy = b.fy() + b.ry() * nx + b.uy() * ny;
        double dz = b.fz() + b.rz() * nx + b.uz() * ny;
        double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
        return new double[] {dx / len, dy / len, dz / len};
    }

    /**
     * The screen position of a world point as {x, y, depth}, or null when it is behind the camera.
     *
     * @param c the camera position (x, y, z)
     */
    public static double[] project(Basis b, double[] c, double fovDeg, double w, double h, double px, double py, double pz) {
        double vx = px - c[0], vy = py - c[1], vz = pz - c[2];
        double depth = vx * b.fx() + vy * b.fy() + vz * b.fz();
        if (depth <= 0.05) return null;
        double tan = Math.tan(Math.toRadians(fovDeg) * 0.5), aspect = w / h;
        double x = (vx * b.rx() + vy * b.ry() + vz * b.rz()) / (depth * tan * aspect);
        double y = (vx * b.ux() + vy * b.uy() + vz * b.uz()) / (depth * tan);
        return new double[] {(x * 0.5 + 0.5) * w, (0.5 - y * 0.5) * h, depth};
    }
}
