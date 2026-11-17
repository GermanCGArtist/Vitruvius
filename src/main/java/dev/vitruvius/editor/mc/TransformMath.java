package dev.vitruvius.editor.mc;

import dev.vitruvius.editor.core.Mirror;
import dev.vitruvius.editor.core.Transform;
import dev.vitruvius.editor.core.Vec3i;

/** Transform helpers the reconciler needs that {@code Transform} does not have. */
public final class TransformMath {
    private TransformMath() {}

    /**
     * The transform that undoes {@code t}: {@code t.then(inverse(t))} and {@code inverse(t).then(t)} are the identity.
     * In the canonical form the linear part is {@code R F} (F = FRONT_BACK or nothing). F is a reflection, so
     * {@code (R F)^-1 = F R^-1 = R F}: a mirrored transform is its own linear inverse, an unmirrored one inverts R.
     */
    public static Transform inverse(Transform t) {
        Transform c = t.canonical();
        Transform lin = c.mirror() == Mirror.NONE
                ? new Transform(Vec3i.ZERO, c.rot().inverse(), Mirror.NONE)
                : new Transform(Vec3i.ZERO, c.rot(), c.mirror());
        return new Transform(lin.applyVector(c.pos().neg()), lin.rot(), lin.mirror()).canonical();
    }
}
