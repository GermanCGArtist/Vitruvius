package dev.vitruvius.editor.gizmo;

import dev.vitruvius.editor.pick.ViewMath;

/**
 * The screen geometry of the move gizmo and the Y ring (DESIGN 11.8, 11.9) without Minecraft or ImGui types: the
 * constant-pixel-size arrows and the XZ plane handle around a projected pivot, and their hit tests. Sizes are
 * logical pixels times {@code sc} (the UI scale).
 */
public final class GizmoLayout {
    public static final double SHAFT_FROM = 14, SHAFT_TO = 84, HEAD_LEN = 14, HEAD_WIDTH = 12, PLANE_FROM = 30, PLANE_TO = 48;
    public static final double HIT_RADIUS = 9, RING_RADIUS = 72, RING_HIT = 8;
    /** A block along an axis that projects to fewer pixels than this makes the arrow unusable (it points at the camera). */
    public static final double MIN_PX_PER_BLOCK = 2;
    /** The plane handle hides when its area is smaller than this (px squared at scale 1). */
    public static final double MIN_PLANE_AREA = 60;

    public enum Part { NONE, X, Y, Z, PLANE }

    /** One arrow: its unit direction on screen and how many pixels a block along it spans. */
    public record Arrow(int axis, double dx, double dy, double pxPerBlock, boolean usable) {}

    public final double ox, oy, sc;
    public final double dist;
    private final Arrow[] arrows;

    private GizmoLayout(double ox, double oy, double sc, double dist, Arrow[] arrows) {
        this.ox = ox;
        this.oy = oy;
        this.sc = sc;
        this.dist = dist;
        this.arrows = arrows;
    }

    /**
     * @param cam   camera position
     * @param pivot gizmo origin in the world (the centre of the pivot block)
     * @return the layout, or null when the pivot is behind the camera
     */
    public static GizmoLayout of(ViewMath.Basis b, double[] cam, double fovDeg, double w, double h, double[] pivot, double sc) {
        double[] o = ViewMath.project(b, cam, fovDeg, w, h, pivot[0], pivot[1], pivot[2]);
        if (o == null) return null;
        Arrow[] arrows = new Arrow[3];
        for (int a = 0; a < 3; a++) arrows[a] = arrow(b, cam, fovDeg, w, h, pivot, o, a);
        return new GizmoLayout(o[0], o[1], sc, o[2], arrows);
    }

    private static Arrow arrow(ViewMath.Basis b, double[] cam, double fov, double w, double h, double[] pivot, double[] o, int a) {
        double step = 0.5;
        for (int sign = 1; sign >= -1; sign -= 2) {
            double[] p = pivot.clone();
            p[a] += sign * step;
            double[] q = ViewMath.project(b, cam, fov, w, h, p[0], p[1], p[2]);
            if (q == null) continue;
            double dx = (q[0] - o[0]) * sign, dy = (q[1] - o[1]) * sign;
            double len = Math.hypot(dx, dy);
            double px = len / step;
            if (len < 1e-6) return new Arrow(a, 0, 0, 0, false);
            return new Arrow(a, dx / len, dy / len, px, px >= MIN_PX_PER_BLOCK);
        }
        return new Arrow(a, 0, 0, 0, false);
    }

    public Arrow arrow(int axis) {
        return arrows[axis];
    }

    /** The screen point {@code px} (scaled) along an arrow from the origin. */
    public double[] along(int axis, double px) {
        Arrow a = arrows[axis];
        return new double[] {ox + a.dx() * px * sc, oy + a.dy() * px * sc};
    }

    /** The four corners of the XZ plane handle (30 to 48 px along each of the projected X and Z). */
    public double[][] planeCorners() {
        Arrow x = arrows[0], z = arrows[2];
        double f = PLANE_FROM * sc, t = PLANE_TO * sc;
        return new double[][] {
                {ox + x.dx() * f + z.dx() * f, oy + x.dy() * f + z.dy() * f},
                {ox + x.dx() * t + z.dx() * f, oy + x.dy() * t + z.dy() * f},
                {ox + x.dx() * t + z.dx() * t, oy + x.dy() * t + z.dy() * t},
                {ox + x.dx() * f + z.dx() * t, oy + x.dy() * f + z.dy() * t}};
    }

    /** Whether the plane handle shows: both flat axes usable and its projected area big enough. */
    public boolean planeVisible() {
        Arrow x = arrows[0], z = arrows[2];
        if (!x.usable() || !z.usable()) return false;
        double side = PLANE_TO - PLANE_FROM;
        double area = Math.abs(x.dx() * z.dy() - x.dy() * z.dx()) * side * side;
        return area >= MIN_PLANE_AREA;
    }

    /** What is under the pointer: the plane handle first, else the nearest usable arrow within {@link #HIT_RADIUS}. */
    public Part hit(double mx, double my, boolean planeAllowed) {
        if (planeAllowed && planeVisible() && inQuad(planeCorners(), mx, my)) return Part.PLANE;
        Part best = Part.NONE;
        double bestD = HIT_RADIUS * sc;
        for (int a = 0; a < 3; a++) {
            if (!arrows[a].usable()) continue;
            double[] p0 = along(a, SHAFT_FROM), p1 = along(a, SHAFT_TO);
            double d = distToSegment(mx, my, p0[0], p0[1], p1[0], p1[1]);
            if (d <= bestD) {
                bestD = d;
                best = a == 0 ? Part.X : a == 1 ? Part.Y : Part.Z;
            }
        }
        return best;
    }

    public static boolean inQuad(double[][] q, double x, double y) {
        boolean pos = false, neg = false;
        for (int i = 0; i < 4; i++) {
            double[] a = q[i], b = q[(i + 1) % 4];
            double c = (b[0] - a[0]) * (y - a[1]) - (b[1] - a[1]) * (x - a[0]);
            if (c > 0) pos = true;
            else if (c < 0) neg = true;
        }
        return !(pos && neg);
    }

    public static double distToSegment(double px, double py, double ax, double ay, double bx, double by) {
        double dx = bx - ax, dy = by - ay;
        double len2 = dx * dx + dy * dy;
        double t = len2 < 1e-12 ? 0 : Math.max(0, Math.min(1, ((px - ax) * dx + (py - ay) * dy) / len2));
        return Math.hypot(px - (ax + t * dx), py - (ay + t * dy));
    }

    // ------------------------------------------------------------------ ring

    /** The world length that projects to {@code px} pixels at distance {@code dist} (DESIGN 11.1). */
    public static double worldLen(double px, double dist, double fovDeg, double viewportH) {
        return px * dist * 2 * Math.tan(Math.toRadians(fovDeg) * 0.5) / viewportH;
    }

    /**
     * The ring in the horizontal plane through {@code centre}: {@code segments + 1} projected points (the last equals
     * the first); an entry is null when that point is behind the camera. {@code startAngle} (radians) lets the arc
     * helpers reuse it.
     */
    public static double[][] ring(ViewMath.Basis b, double[] cam, double fovDeg, double w, double h, double[] centre, double radius, int segments) {
        double[][] out = new double[segments + 1][];
        for (int i = 0; i <= segments; i++) {
            double a = 2 * Math.PI * i / segments;
            out[i] = ViewMath.project(b, cam, fovDeg, w, h, centre[0] + Math.cos(a) * radius, centre[1], centre[2] + Math.sin(a) * radius);
        }
        return out;
    }

    /** The smallest distance from a point to a projected polyline (null points break it). */
    public static double distToPolyline(double[][] pts, double x, double y) {
        double best = Double.MAX_VALUE;
        for (int i = 0; i + 1 < pts.length; i++) {
            if (pts[i] == null || pts[i + 1] == null) continue;
            best = Math.min(best, distToSegment(x, y, pts[i][0], pts[i][1], pts[i + 1][0], pts[i + 1][1]));
        }
        return best;
    }
}
