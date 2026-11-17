package dev.vitruvius.editor.ops;

import dev.vitruvius.editor.core.Mirror;
import dev.vitruvius.editor.core.Rot;
import dev.vitruvius.editor.core.Transform;
import dev.vitruvius.editor.core.Vec3i;

import java.util.Collection;
import java.util.UUID;

/**
 * Mirrors nodes along X (x becomes -x) about the plane through the block {@code pivot}. Mirror Z is Mirror X plus a
 * half turn: run this and a {@link Rotate} of two quarter turns in one transaction.
 */
public final class MirrorX extends WorldMapEdit {
    private final Vec3i pivot;

    public MirrorX(Collection<UUID> ids, Vec3i pivot) {
        super("Mirror X", ids);
        this.pivot = pivot;
    }

    @Override
    Transform map() {
        Transform m = new Transform(Vec3i.ZERO, Rot.NONE, Mirror.FRONT_BACK);
        return m.withPos(pivot.sub(m.applyVector(pivot)));
    }

    @Override
    Refusal extraRefusal() {
        return null;
    }
}
