package dev.vitruvius.shape;

/**
 * Inside tests for every ShapeType, in the shape's own box coordinates (0..size-1 per axis). Every test works on
 * cell centers (c + 0.5) against the analytic shape, so results are symmetric for odd and even sizes and a size of
 * 1 always yields a block.
 */
public final class Shapes {
    private static final double EPS = 1e-9;

    private Shapes() {}

    /** Whether cell (x, y, z) belongs to the solid shape (before the outline/fill split). spec is already clamped. */
    public static boolean inside(ShapeSpec spec, int x, int y, int z) {
        int sx = spec.sizeX(), sy = spec.sizeY(), sz = spec.sizeZ();
        return switch (spec.type()) {
            case BOX, PLANE, CUSTOM -> true;     // CUSTOM is generated from its Sketch, see ShapeGenerator
            case FRAME -> frame(spec.thickness(), x, y, z, sx, sy, sz);
            case ELLIPSOID -> ellipse(x, sx, y, sy) + sq(off(z, sz) / (sz / 2.0)) <= 1 + EPS;
            case ARCH -> spec.axis() == Axis.X
                    ? arch(spec.param1(), z, sz, y, sy)
                    : arch(spec.param1(), x, sx, y, sy);
            default -> switch (spec.axis()) {
                case X -> axial(spec, y, z, x, sy, sz, sx);
                case Y -> axial(spec, x, z, y, sx, sz, sy);
                case Z -> axial(spec, x, y, z, sx, sy, sz);
            };
        };
    }

    /** Shapes with an axis, in axis-relative coordinates: u and v span the cross section, w runs along the axis. */
    private static boolean axial(ShapeSpec s, int u, int v, int w, int su, int sv, int sw) {
        int p1 = s.param1();
        return switch (s.type()) {
            case DOME -> ellipse(u, su, v, sv) + sq((w + 0.5) / sw) <= 1 + EPS;
            case CYLINDER -> ellipse(u, su, v, sv) <= 1 + EPS;
            case CONE -> {
                double ku = 1 - (1 - top(p1, su)) * ((w + 0.5) / sw);
                double kv = 1 - (1 - top(p1, sv)) * ((w + 0.5) / sw);
                yield sq(off(u, su) / (su / 2.0 * ku)) + sq(off(v, sv) / (sv / 2.0 * kv)) <= 1 + EPS;
            }
            case PYRAMID -> {
                double f = (w + 0.5) / sw;
                double ru = Math.max(0.5, su / 2.0 * (1 - (1 - top(p1, su)) * f));
                double rv = Math.max(0.5, sv / 2.0 * (1 - (1 - top(p1, sv)) * f));
                yield Math.abs(off(u, su)) <= ru + EPS && Math.abs(off(v, sv)) <= rv + EPS;
            }
            case TORUS -> torus(Math.max(1, p1), u, v, w, su, sv, sw);
            case TUBE -> tube(Math.max(1, p1), u, su, v, sv);
            case GRID -> grid(Math.max(1, p1), Math.max(1, s.param2()), u, v, w, sw);
            default -> throw new IllegalStateException("not an axial shape: " + s.type());
        };
    }

    /** Top size as a fraction of the base size, 0 = point, 1 = no taper. */
    private static double top(int topSize, int baseSize) {
        return Math.min(1.0, topSize / (double) baseSize);
    }

    /** Only the edges: a cell belongs when at least two of its coordinates lie within t of the box boundary. */
    private static boolean frame(int t, int x, int y, int z, int sx, int sy, int sz) {
        int near = 0;
        if (x < t || x >= sx - t) near++;
        if (y < t || y >= sy - t) near++;
        if (z < t || z >= sz - t) near++;
        return near >= 2;
    }

    /** Ring in the (u, v) plane of outer size su x sv with a round-ish tube of diameter p; solid if no hole is left. */
    private static boolean torus(int p, int u, int v, int w, int su, int sv, int sw) {
        double ringU = su / 2.0 - p / 2.0, ringV = sv / 2.0 - p / 2.0;
        double du = off(u, su), dv = off(v, sv), dw = off(w, sw);
        if (ringU <= 0 || ringV <= 0) {
            return sq(du / (su / 2.0)) + sq(dv / (sv / 2.0)) + sq(dw / (sw / 2.0)) <= 1 + EPS;
        }
        double d = Math.sqrt(sq(du / ringU) + sq(dv / ringV));
        double planar;
        if (d < 1e-9) {
            planar = Math.min(ringU, ringV);
        } else {
            planar = Math.hypot(du * (1 - 1 / d), dv * (1 - 1 / d));
        }
        return sq(planar / (p / 2.0)) + sq(dw / (sw / 2.0)) <= 1 + EPS;
    }

    /** Elliptic cross section of wall width p; solid if the inner ellipse would vanish. */
    private static boolean tube(int p, int u, int su, int v, int sv) {
        if (ellipse(u, su, v, sv) > 1 + EPS) return false;
        double iu = su / 2.0 - p, iv = sv / 2.0 - p;
        if (iu <= 0 || iv <= 0) return true;
        return sq(off(u, su) / iu) + sq(off(v, sv) / iv) >= 1;
    }

    /** Upright half ring standing on the bottom of the box: width along its horizontal axis, ring width p. */
    private static boolean arch(int p, int a, int sa, int h, int sh) {
        double rx = sa / 2.0, ry = sh;
        double dx = off(a, sa), dy = h + 0.5;
        if (sq(dx / rx) + sq(dy / ry) > 1 + EPS) return false;
        double ix = rx - Math.max(1, p), iy = ry - Math.max(1, p);
        if (ix <= 0 || iy <= 0) return true;
        return sq(dx / ix) + sq(dy / iy) >= 1;
    }

    /** Lines of width wd repeating every s from coordinate 0: a flat grid for a 1 thick layer, else a 3D lattice. */
    private static boolean grid(int s, int wd, int u, int v, int w, int sw) {
        boolean lu = u % s < wd, lv = v % s < wd;
        if (sw == 1) return lu || lv;
        boolean lw = w % s < wd;
        return (lu ? 1 : 0) + (lv ? 1 : 0) + (lw ? 1 : 0) >= 2;
    }

    /** Squared normalized distance of the cell center from the center of an ellipse spanning sa x sb. */
    private static double ellipse(int a, int sa, int b, int sb) {
        return sq(off(a, sa) / (sa / 2.0)) + sq(off(b, sb) / (sb / 2.0));
    }

    /** Offset of the cell center from the box center along one axis. */
    private static double off(int c, int size) {
        return c + 0.5 - size / 2.0;
    }

    private static double sq(double d) {
        return d * d;
    }
}
