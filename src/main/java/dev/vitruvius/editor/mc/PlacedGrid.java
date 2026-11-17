package dev.vitruvius.editor.mc;

import dev.vitruvius.editor.core.MaskPlan;
import dev.vitruvius.editor.core.Transform;
import dev.vitruvius.editor.core.Vec3i;

import java.util.List;

/**
 * The occupancy of one or more placed schematics in world coordinates. Each copy answers in its own schematic-local
 * coordinates ({@link Local}); a world position is converted with the inverse of the copy's world transform. No
 * Minecraft types, so the conversion is unit-tested for every rotation and mirror.
 */
public final class PlacedGrid implements MaskPlan.Occupancy {
    /** Non-air test in schematic-local coordinates (region offsets included). */
    @FunctionalInterface
    public interface Local {
        boolean solid(int x, int y, int z);
    }

    public record Copy(Local local, Transform world) {}

    private final Transform[] inverse;
    private final Local[] locals;

    public PlacedGrid(List<Copy> copies) {
        inverse = new Transform[copies.size()];
        locals = new Local[copies.size()];
        for (int i = 0; i < inverse.length; i++) {
            inverse[i] = TransformMath.inverse(copies.get(i).world());
            locals[i] = copies.get(i).local();
        }
    }

    /** World position to schematic-local position for a copy placed with {@code world}. */
    public static Vec3i toLocal(Transform world, Vec3i p) {
        return TransformMath.inverse(world).apply(p);
    }

    @Override
    public boolean solid(int x, int y, int z) {
        Vec3i p = new Vec3i(x, y, z);
        for (int i = 0; i < inverse.length; i++) {
            Vec3i l = inverse[i].apply(p);
            if (locals[i].solid(l.x(), l.y(), l.z())) return true;
        }
        return false;
    }
}
