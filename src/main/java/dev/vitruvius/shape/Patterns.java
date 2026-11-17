package dev.vitruvius.shape;

import java.util.List;

/** Picks the block of a slot for a cell, see PatternMode for each mode. */
public final class Patterns {
    /** 4x4 Bayer matrix (0..15) for ordered dithering of GRADIENT. */
    private static final int[] BAYER = {0, 8, 2, 10, 12, 4, 14, 6, 3, 11, 1, 9, 15, 7, 13, 5};

    private Patterns() {}

    /**
     * Index into slot.entries() for cell (x, y, z) of a shape of the given size, or -1 for air (only BRICK without
     * mortar never returns -1; an air slot always returns -1). Deterministic: same inputs, same answer.
     */
    public static int pick(BlockSlot slot, int x, int y, int z, int sizeX, int sizeY, int sizeZ) {
        List<BlockEntry> entries = slot.entries();
        int n = entries.size();
        if (n == 0) return -1;
        if (n == 1) return 0;
        Axis axis = slot.axis();
        return switch (slot.mode()) {
            case SINGLE -> 0;
            case MIX -> mix(slot, entries, x, y, z);
            case CHECKER -> (x / slot.cellX() + y / slot.cellY() + z / slot.cellZ()) % n;
            case STRIPES -> (along(axis, x, y, z) / slot.width()) % n;
            case GRADIENT -> gradient(slot, n, x, y, z, along(axis, sizeX, sizeY, sizeZ));
            case DIAGONAL -> (normalSum(axis, x, y, z) / slot.width()) % n;
            case BRICK -> brick(slot, n, x, y, z);
        };
    }

    /** Weighted random choice, a pure function of (seed, x, y, z). */
    private static int mix(BlockSlot slot, List<BlockEntry> entries, int x, int y, int z) {
        long total = 0;
        for (BlockEntry e : entries) total += e.weight();
        long h = mix64(slot.seed() ^ (x * 0x9E3779B97F4A7C15L));
        h = mix64(h ^ (y * 0xC2B2AE3D27D4EB4FL));
        h = mix64(h ^ (z * 0x165667B19E3779F9L));
        long r = ((h >>> 32) * total) >>> 32;
        for (int i = 0; i < entries.size(); i++) {
            r -= entries.get(i).weight();
            if (r < 0) return i;
        }
        return entries.size() - 1;
    }

    /** Splitmix64 finalizer. */
    private static long mix64(long h) {
        h += 0x9E3779B97F4A7C15L;
        h = (h ^ (h >>> 30)) * 0xBF58476D1CE4E5B9L;
        h = (h ^ (h >>> 27)) * 0x94D049BB133111EBL;
        return h ^ (h >>> 31);
    }

    /** Equal bands along the axis; with dither the band edges are blended by a Bayer threshold. */
    private static int gradient(BlockSlot slot, int n, int x, int y, int z, int size) {
        int c = along(slot.axis(), x, y, z);
        double q = (c + 0.5) * n / size;
        if (!slot.dither()) return Math.min(n - 1, (int) q);
        double pos = q - 0.5;
        if (pos <= 0) return 0;
        int base = (int) pos;
        if (base >= n - 1) return n - 1;
        int a, b;
        switch (slot.axis()) {
            case X -> { a = y; b = z; }
            case Y -> { a = x; b = z; }
            default -> { a = x; b = y; }
        }
        double threshold = (BAYER[(a & 3) * 4 + (b & 3)] + 0.5) / 16.0;
        return pos - base > threshold ? base + 1 : base;
    }

    /**
     * Running-bond bricks. With a second entry the bricks are separated by mortar lines of width 1 (so the period is
     * the brick size + 1); depth joints only exist for bricks deeper than 1. Odd rows shift by half a period in x and z.
     */
    private static int brick(BlockSlot slot, int n, int x, int y, int z) {
        int cx = slot.cellX(), cy = slot.cellY(), cz = slot.cellZ();
        boolean mortar = n >= 2;
        int px = mortar ? cx + 1 : cx;
        int py = mortar ? cy + 1 : cy;
        int pz = mortar && cz > 1 ? cz + 1 : cz;
        int row = y / py;
        boolean odd = (row & 1) == 1;
        int xs = x + (odd ? px / 2 : 0);
        int zs = z + (odd ? pz / 2 : 0);
        if (mortar) {
            boolean joint = y % py == cy || xs % px == cx || (cz > 1 && zs % pz == cz);
            return joint ? 1 : 0;
        }
        return (xs / px + zs / pz + row) % n;
    }

    private static int along(Axis axis, int x, int y, int z) {
        return switch (axis) {
            case X -> x;
            case Y -> y;
            case Z -> z;
        };
    }

    /** Sum of the two coordinates normal to the axis. */
    private static int normalSum(Axis axis, int x, int y, int z) {
        return switch (axis) {
            case X -> y + z;
            case Y -> x + z;
            case Z -> x + y;
        };
    }
}
