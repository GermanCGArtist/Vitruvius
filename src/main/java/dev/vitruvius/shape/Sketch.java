package dev.vitruvius.shape;

import java.util.HashMap;
import java.util.Map;

/**
 * The drawing behind a CUSTOM shape: a 2D grid of pixels, extruded along the shape's axis by {@code depth} blocks.
 * A cell is {@code null} (empty), {@code ""} (part of the shape, coloured by the Outline/Fill patterns) or a block
 * state (pinned to that block, whatever the patterns say). The grid is stored cropped to the drawn pixels
 * (origin x0, y0 in canvas coordinates, w x h cells, row-major, rows growing downward on screen), so the canvas
 * "grows" with the drawing.
 *
 * <p>Canvas to blocks: extruding along Z the canvas is the front view (col = X, row down = Y down); along Y it is
 * the top view (col = X, row = Z); along X it is the side view (col = Z, row down = Y down).
 */
public record Sketch(int depth, int x0, int y0, int w, int h, String[] cells) {
    public static final int MAX = ShapeSpec.MAX_SIZE;
    public static final Sketch EMPTY = new Sketch(1, 0, 0, 0, 0, new String[0]);

    public boolean isEmpty() {
        return w <= 0 || h <= 0;
    }

    /** Number of drawn pixels. */
    public int pixelCount() {
        int n = 0;
        for (String c : cells) if (c != null) n++;
        return n;
    }

    /** The cell at canvas coordinates, null when empty or outside. */
    public String at(int col, int row) {
        int x = col - x0, y = row - y0;
        if (x < 0 || y < 0 || x >= w || y >= h) return null;
        return cells[y * w + x];
    }

    public int depthClamped() {
        return Math.max(1, Math.min(MAX, depth));
    }

    /** Width of the canvas part that is used, cropped to MAX. */
    public int usedW() {
        return Math.max(1, Math.min(MAX, w));
    }

    public int usedH() {
        return Math.max(1, Math.min(MAX, h));
    }

    /** Block size {x, y, z} of the extruded shape along {@code axis}. */
    public int[] size(Axis axis) {
        int cw = usedW(), ch = usedH(), d = depthClamped();
        return switch (axis) {
            case Z -> new int[] {cw, ch, d};
            case Y -> new int[] {cw, d, ch};
            case X -> new int[] {d, ch, cw};
        };
    }

    public Sketch withDepth(int d) {
        return new Sketch(Math.max(1, Math.min(MAX, d)), x0, y0, w, h, cells);
    }

    /** Builds a cropped sketch from a sparse map keyed by {@link #key}. */
    public static Sketch fromMap(Map<Long, String> pixels, int depth) {
        if (pixels.isEmpty()) return new Sketch(depth, 0, 0, 0, 0, new String[0]).withDepth(depth);
        int minC = Integer.MAX_VALUE, minR = Integer.MAX_VALUE, maxC = Integer.MIN_VALUE, maxR = Integer.MIN_VALUE;
        for (long k : pixels.keySet()) {
            int c = col(k), r = row(k);
            minC = Math.min(minC, c);
            maxC = Math.max(maxC, c);
            minR = Math.min(minR, r);
            maxR = Math.max(maxR, r);
        }
        int w = maxC - minC + 1, h = maxR - minR + 1;
        String[] cells = new String[w * h];
        for (var e : pixels.entrySet()) cells[(row(e.getKey()) - minR) * w + (col(e.getKey()) - minC)] = e.getValue();
        return new Sketch(depth, minC, minR, w, h, cells).withDepth(depth);
    }

    public Map<Long, String> toMap() {
        Map<Long, String> m = new HashMap<>();
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                String c = cells[y * w + x];
                if (c != null) m.put(key(x0 + x, y0 + y), c);
            }
        }
        return m;
    }

    public static long key(int col, int row) {
        return ((long) col << 32) | (row & 0xffffffffL);
    }

    public static int col(long key) {
        return (int) (key >> 32);
    }

    public static int row(long key) {
        return (int) key;
    }
}
