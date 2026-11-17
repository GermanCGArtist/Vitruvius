package dev.schemarray.editor.ops;

import dev.schemarray.editor.core.Mirror;
import dev.schemarray.editor.core.Rot;
import dev.schemarray.editor.core.Transform;
import dev.schemarray.editor.core.Vec3i;

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
        super(quarterTurns < 0 || quarterTurns % 4 == 3 ? "Rotate -90" : quarterTurns % 4 == 2 ? "Rotate 180" : "Rotate +90", ids);
        this.pivot = pivot;
        this.quarterTurns = quarterTurns;
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
