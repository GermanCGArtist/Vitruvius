package dev.vitruvius.editor.gizmo;

import dev.vitruvius.editor.core.Box;
import dev.vitruvius.editor.core.Transform;
import dev.vitruvius.editor.core.Vec3i;

import java.util.List;

/**
 * The maths of the edit mode that drags the sides of boxes, without Minecraft types. Axes are 0 = X, 1 = Y, 2 = Z;
 * a side is an axis and a sign (+1 the max side, -1 the min side).
 */
public final class FaceEdit {
    private FaceEdit() {}

    /** The side of box {@code box} (an index into the list that was searched) that a ray meets, at distance {@code t}. */
    public record Face(int box, int axis, int sign, double t) {}

    /**
     * The nearest box side along a ray. A ray that starts inside a box meets its far side, so a side can be grabbed
     * from the inside too. Null when nothing is hit within {@code maxDist}.
     */
    public static Face pick(List<Box> boxes, double[] o, double[] d, double maxDist) {
        Face best = null;
        for (int i = 0; i < boxes.size(); i++) {
            Face f = pickOne(i, boxes.get(i), o, d, maxDist);
            if (f != null && (best == null || f.t() < best.t())) best = f;
        }
        return best;
    }

    private static Face pickOne(int index, Box b, double[] o, double[] d, double maxDist) {
        double tEnter = Double.NEGATIVE_INFINITY, tExit = Double.POSITIVE_INFINITY;
        int aEnter = -1, aExit = -1, sEnter = 0, sExit = 0;
        for (int a = 0; a < 3; a++) {
            double lo = b.min().get(a), hi = b.maxExclusive().get(a);
            if (Math.abs(d[a]) < 1e-12) {
                if (o[a] < lo || o[a] > hi) return null;
                continue;
            }
            double t1 = (lo - o[a]) / d[a], t2 = (hi - o[a]) / d[a];
            boolean forward = d[a] > 0;
            double tn = forward ? t1 : t2, tf = forward ? t2 : t1;
            if (tn > tEnter) {
                tEnter = tn;
                aEnter = a;
                sEnter = forward ? -1 : 1;
            }
            if (tf < tExit) {
                tExit = tf;
                aExit = a;
                sExit = forward ? 1 : -1;
            }
        }
        if (aEnter < 0 || tEnter > tExit || tExit < 0) return null;
        if (tEnter >= 0) return tEnter <= maxDist ? new Face(index, aEnter, sEnter, tEnter) : null;
        return tExit <= maxDist ? new Face(index, aExit, sExit, tExit) : null;
    }

    /** The local side that a world side is: {axis, sign}. The transform turns and mirrors, so the sign can flip. */
    public static int[] localSide(Transform world, int axis, int sign) {
        for (int a = 0; a < 3; a++) {
            for (int s = -1; s <= 1; s += 2) {
                Vec3i w = world.applyVector(new Vec3i(a == 0 ? s : 0, a == 1 ? s : 0, a == 2 ? s : 0));
                if (w.get(axis) == sign) return new int[] {a, s};
            }
        }
        throw new IllegalStateException("not an axis vector");
    }

    /** The box with one side moved {@code blocks} outward (negative: inward), never thinner than one block. */
    public static Box resized(Box b, int axis, int sign, int blocks) {
        int lo = b.min().get(axis), hi = b.maxExclusive().get(axis);
        if (sign > 0) hi = Math.max(lo + 1, hi + blocks);
        else lo = Math.min(hi - 1, lo - blocks);
        return new Box(b.min().with(axis, lo), b.maxExclusive().with(axis, hi));
    }

    /** The box with one side moved so its length along {@code axis} becomes {@code length} (at least 1). */
    public static Box withLength(Box b, int axis, int sign, int length) {
        int len = Math.max(1, length);
        int lo = b.min().get(axis), hi = b.maxExclusive().get(axis);
        if (sign > 0) hi = lo + len;
        else lo = hi - len;
        return new Box(b.min().with(axis, lo), b.maxExclusive().with(axis, hi));
    }

    /**
     * Typed length input for a box side (pure): plain digits set the exact side length ({@code 12} -> 12 blocks),
     * while a leading {@code +} or {@code -} adjusts relatively ({@code +4} grows by 4, {@code -2} shrinks by 2).
     */
    public static final class TypedLength {
        public static final int MAX_CHARS = 6;
        private final StringBuilder buf = new StringBuilder();

        public boolean digit(int d) {
            if (d < 0 || d > 9 || buf.length() >= MAX_CHARS) return false;
            String s = buf.toString();
            if (s.equals("0") || s.equals("+0") || s.equals("-0")) buf.setLength(buf.length() - 1);
            buf.append((char) ('0' + d));
            return true;
        }

        /** Sets or toggles a leading {@code +} or {@code -} sign for relative adjustment. */
        public void toggleSign(char ch) {
            if (ch != '+' && ch != '-') return;
            if (buf.length() > 0 && (buf.charAt(0) == '+' || buf.charAt(0) == '-')) {
                if (buf.charAt(0) == ch) buf.deleteCharAt(0);
                else buf.setCharAt(0, ch);
            } else if (buf.length() < MAX_CHARS) {
                buf.insert(0, ch);
            }
        }

        public void backspace() {
            if (buf.length() > 0) buf.setLength(buf.length() - 1);
        }

        public void clear() {
            buf.setLength(0);
        }

        public boolean isEmpty() {
            return buf.length() == 0;
        }

        public boolean isRelative() {
            return buf.length() > 0 && (buf.charAt(0) == '+' || buf.charAt(0) == '-');
        }

        public String text() {
            return buf.toString();
        }

        public Integer value() {
            String s = buf.toString();
            if (s.isEmpty() || s.equals("+") || s.equals("-")) return null;
            try {
                return Integer.parseInt(s);
            } catch (NumberFormatException e) {
                return null;
            }
        }

        /** Computes the resulting local box, or null when the typed input is incomplete or invalid (e.g. length 0). */
        public Box apply(Box local, int localAxis, int localSign, boolean extrude) {
            Integer v = value();
            if (v == null) return null;
            if (isRelative()) {
                return extrude ? slab(local, localAxis, localSign, v) : resized(local, localAxis, localSign, v);
            }
            if (v < 1) return null;
            return extrude ? slab(local, localAxis, localSign, v) : withLength(local, localAxis, localSign, v);
        }
    }

    /** A new box on the outside of a side: the same cross section, {@code blocks} thick. Null for less than one block. */
    public static Box slab(Box b, int axis, int sign, int blocks) {
        if (blocks < 1) return null;
        if (sign > 0) {
            int at = b.maxExclusive().get(axis);
            return new Box(b.min().with(axis, at), b.maxExclusive().with(axis, at + blocks));
        }
        int at = b.min().get(axis);
        return new Box(b.min().with(axis, at - blocks), b.maxExclusive().with(axis, at));
    }

    /** The four corners of a side in world coordinates, for drawing. */
    public static double[][] corners(Box b, int axis, int sign) {
        int o1 = axis == 0 ? 1 : 0, o2 = axis == 2 ? 1 : 2;
        double c = sign > 0 ? b.maxExclusive().get(axis) : b.min().get(axis);
        double[] s1 = {b.min().get(o1), b.maxExclusive().get(o1), b.maxExclusive().get(o1), b.min().get(o1)};
        double[] s2 = {b.min().get(o2), b.min().get(o2), b.maxExclusive().get(o2), b.maxExclusive().get(o2)};
        double[][] out = new double[4][3];
        for (int k = 0; k < 4; k++) {
            out[k][axis] = c;
            out[k][o1] = s1[k];
            out[k][o2] = s2[k];
        }
        return out;
    }

    /** The centre of a side. */
    public static double[] centre(Box b, int axis, int sign) {
        double[] c = new double[3];
        for (int a = 0; a < 3; a++) c[a] = (b.min().get(a) + b.maxExclusive().get(a)) * 0.5;
        c[axis] = sign > 0 ? b.maxExclusive().get(axis) : b.min().get(axis);
        return c;
    }
}
