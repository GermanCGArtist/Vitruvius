package dev.schemarray.shape;

/**
 * The geometry of one shape. Sizes are the bounding box in blocks (1..MAX_SIZE). thickness = outline shell width
 * in blocks (>= 1); a shell at least as thick as the shape means everything is outline. param1/param2 mean what
 * the type's labels say (see ShapeType). anchorCenter: the placement origin is the box center instead of its min
 * corner (used by the preview/placement, not by the generator).
 */
public record ShapeSpec(ShapeType type, int sizeX, int sizeY, int sizeZ, Axis axis, int thickness,
                        int param1, int param2, boolean anchorCenter) {
    public static final int MAX_SIZE = 256;

    public static ShapeSpec defaults() {
        return new ShapeSpec(ShapeType.BOX, 9, 9, 9, Axis.Y, 1, 3, 1, false);
    }

    /** Copy with every number clamped to its valid range (and PLANE forced to 1 along its axis). */
    public ShapeSpec clamped() {
        int x = clamp(sizeX, 1, MAX_SIZE), y = clamp(sizeY, 1, MAX_SIZE), z = clamp(sizeZ, 1, MAX_SIZE);
        Axis a = axis == null ? Axis.Y : axis;
        if (type == ShapeType.PLANE) {
            switch (a) {
                case X -> x = 1;
                case Y -> y = 1;
                case Z -> z = 1;
            }
        }
        return new ShapeSpec(type == null ? ShapeType.BOX : type, x, y, z, a,
                clamp(thickness, 1, MAX_SIZE), clamp(param1, 0, MAX_SIZE), clamp(param2, 0, MAX_SIZE), anchorCenter);
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
