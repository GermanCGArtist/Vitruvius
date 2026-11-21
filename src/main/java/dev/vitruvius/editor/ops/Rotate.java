package dev.vitruvius.editor.ops;

import dev.vitruvius.editor.core.Mirror;
import dev.vitruvius.editor.core.Rot;
import dev.vitruvius.editor.core.Transform;
import dev.vitruvius.editor.core.Vec3i;

import java.util.Collection;
import java.util.UUID;

/**
 * Turns nodes by quarter turns about the vertical axis through the block {@code pivot} (world coordinates, chosen by
 * the caller: the selection's centre, the 3D cursor, a node's own origin). Each node's {@code pos} is adjusted so the
 * pivot block stays where it is. Positive turns are clockwise seen from above, as in Litematica.
 */
public final class Rotate extends WorldMapEdit {
    private final Vec3i pivot;
    private final int quarterTurns;

    /** @param quarterTurns +1 = 90 degrees clockwise, -1 = counter-clockwise, 2 = 180 */
    public Rotate(Collection<UUID> ids, Vec3i pivot, int quarterTurns) {
        super(labelFor(quarterTurns), ids);
        this.pivot = pivot;
        this.quarterTurns = quarterTurns;
    }

    private static String labelFor(int quarterTurns) {
        return switch (Math.floorMod(quarterTurns, 4)) {
            case 3 -> "Rotate -90";
            case 2 -> "Rotate 180";
            case 1 -> "Rotate +90";
            default -> "Rotate";
        };
    }

    public static Rotate clockwise(Collection<UUID> ids, Vec3i pivot) {
        return new Rotate(ids, pivot, 1);
    }

    public static Rotate counterClockwise(Collection<UUID> ids, Vec3i pivot) {
        return new Rotate(ids, pivot, -1);
    }

    @Override
    Transform map() {
        Rot r = Rot.NONE.rotate(quarterTurns);
        Transform rot = new Transform(Vec3i.ZERO, r, Mirror.NONE);
        return rot.withPos(pivot.sub(rot.applyVector(pivot)));
    }

    @Override
    Refusal extraRefusal() {
        return Math.floorMod(quarterTurns, 4) == 0 ? new Refusal("A full turn changes nothing") : null;
    }
}
