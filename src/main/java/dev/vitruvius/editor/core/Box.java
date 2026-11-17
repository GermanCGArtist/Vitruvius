package dev.vitruvius.editor.core;

import java.util.Objects;
import java.util.Optional;

/**
 * A block range: blocks with {@code min <= p < maxExclusive} on every axis. A block at x occupies [x, x+1).
 */
public record Box(Vec3i min, Vec3i maxExclusive) {

    public Box {
        Objects.requireNonNull(min, "min");
        Objects.requireNonNull(maxExclusive, "maxExclusive");
    }

    public static Box ofSize(Vec3i size) {
        return new Box(Vec3i.ZERO, size);
    }

    public Vec3i size() {
        return maxExclusive.sub(min);
    }

    /** Number of blocks; 0 when any side is empty or negative. */
    public long volume() {
        Vec3i s = size();
        if (s.x() <= 0 || s.y() <= 0 || s.z() <= 0) return 0;
        return (long) s.x() * s.y() * s.z();
    }

    /** The common blocks, or empty when they share no volume (touching faces share none). */
    public Optional<Box> intersect(Box o) {
        Box r = new Box(maxVec(min, o.min), minVec(maxExclusive, o.maxExclusive));
        return r.volume() > 0 ? Optional.of(r) : Optional.empty();
    }

    /**
     * The smallest box holding both. An empty box (no volume) adds nothing, so the other box is returned as is.
     */
    public Box union(Box o) {
        if (volume() == 0) return o;
        if (o.volume() == 0) return this;
        return new Box(minVec(min, o.min), maxVec(maxExclusive, o.maxExclusive));
    }

    public boolean contains(Vec3i p) {
        return p.x() >= min.x() && p.x() < maxExclusive.x()
                && p.y() >= min.y() && p.y() < maxExclusive.y()
                && p.z() >= min.z() && p.z() < maxExclusive.z();
    }

    /**
     * The enclosing block range of this box after {@code t}. Every block moves to {@code t.apply(block)} (the same
     * rule as the placements), so the 8 corner blocks (min and maxExclusive - 1 on each axis) are transformed, and
     * the result runs from the smallest to the largest of them, plus one on the max side. Each output axis is a
     * signed input axis plus a constant, so the extremes are always at the corners.
     *
     * @throws IllegalArgumentException if this box has no volume
     */
    public Box transformed(Transform t) {
        if (volume() == 0) throw new IllegalArgumentException("empty box " + this);
        Vec3i hi = maxExclusive.sub(new Vec3i(1, 1, 1));
        int[] lo = {Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE};
        int[] up = {Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE};
        for (int c = 0; c < 8; c++) {
            int x = (c & 1) == 0 ? min.x() : hi.x();
            int y = (c & 2) == 0 ? min.y() : hi.y();
            int z = (c & 4) == 0 ? min.z() : hi.z();
            Vec3i p = t.apply(new Vec3i(x, y, z));
            for (int a = 0; a < 3; a++) {
                lo[a] = Math.min(lo[a], p.get(a));
                up[a] = Math.max(up[a], p.get(a));
            }
        }
        return new Box(new Vec3i(lo[0], lo[1], lo[2]), new Vec3i(up[0] + 1, up[1] + 1, up[2] + 1));
    }

    private static Vec3i minVec(Vec3i a, Vec3i b) {
        return new Vec3i(Math.min(a.x(), b.x()), Math.min(a.y(), b.y()), Math.min(a.z(), b.z()));
    }

    private static Vec3i maxVec(Vec3i a, Vec3i b) {
        return new Vec3i(Math.max(a.x(), b.x()), Math.max(a.y(), b.y()), Math.max(a.z(), b.z()));
    }
}
