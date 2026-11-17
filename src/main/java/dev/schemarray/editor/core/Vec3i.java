package dev.schemarray.editor.core;

/** An immutable integer vector (block coordinates). */
public record Vec3i(int x, int y, int z) {
    public static final Vec3i ZERO = new Vec3i(0, 0, 0);

    public Vec3i add(Vec3i o) {
        return new Vec3i(x + o.x, y + o.y, z + o.z);
    }

    public Vec3i sub(Vec3i o) {
        return new Vec3i(x - o.x, y - o.y, z - o.z);
    }

    public Vec3i neg() {
        return new Vec3i(-x, -y, -z);
    }

    /** The component on an axis: 0 = x, 1 = y, 2 = z. */
    public int get(int axis) {
        return switch (axis) {
            case 0 -> x;
            case 1 -> y;
            case 2 -> z;
            default -> throw new IllegalArgumentException("axis " + axis);
        };
    }
}
