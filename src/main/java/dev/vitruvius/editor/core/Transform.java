package dev.vitruvius.editor.core;

import java.util.Objects;

/**
 * A placement transform: mirror first, then turn (as {@code ArraySync.transform} and Litematica do), then move by
 * {@code pos}. Pure integer maths, no Minecraft types.
 */
public record Transform(Vec3i pos, Rot rot, Mirror mirror) {
    public static final Transform IDENTITY = new Transform(Vec3i.ZERO, Rot.NONE, Mirror.NONE);

    public Transform {
        Objects.requireNonNull(pos, "pos");
        Objects.requireNonNull(rot, "rot");
        Objects.requireNonNull(mirror, "mirror");
    }

    public Transform withPos(Vec3i p) {
        return new Transform(p, rot, mirror);
    }

    /** Local schematic coordinate to editor coordinate: the linear part, then the offset. */
    public Vec3i apply(Vec3i local) {
        return applyVector(local).add(pos);
    }

    /** The linear part only (mirror, then turn), without the offset. */
    public Vec3i applyVector(Vec3i v) {
        int x = v.x(), y = v.y(), z = v.z();
        if (mirror == Mirror.LEFT_RIGHT) z = -z;
        else if (mirror == Mirror.FRONT_BACK) x = -x;
        return switch (rot) {
            case NONE -> new Vec3i(x, y, z);
            case CW_90 -> new Vec3i(-z, y, x);
            case CW_180 -> new Vec3i(-x, y, -z);
            case CCW_90 -> new Vec3i(z, y, -x);
        };
    }

    /**
     * The transform that does {@code child} first and then this one:
     * {@code parent.then(child).apply(p) == parent.apply(child.apply(p))}.
     *
     * <p>The linear parts are {@code Rp Fp Rc Fc}, with R the turn and F the mirror. A mirror reverses the direction
     * of a turn ({@code F R = R^-1 F}), so {@code Fp Rc = R(sign * kc) Fp}, where sign is -1 when the parent is
     * mirrored. Two mirrors multiply: equal ones give the identity, and LEFT_RIGHT times FRONT_BACK is a half turn
     * (-I), which adds two steps to the turn. The offset is {@code parent.applyVector(child.pos) + parent.pos}.
     */
    public Transform then(Transform child) {
        int sign = mirror == Mirror.NONE ? 1 : -1;
        Mirror m;
        int extra;
        if (mirror == Mirror.NONE) {
            m = child.mirror;
            extra = 0;
        } else if (child.mirror == Mirror.NONE || child.mirror == mirror) {
            m = child.mirror == Mirror.NONE ? mirror : Mirror.NONE;
            extra = 0;
        } else {
            m = Mirror.NONE;
            extra = 2;
        }
        Rot r = Rot.ofSteps(rot.steps() + sign * child.rot.steps() + extra);
        return new Transform(applyVector(child.pos).add(pos), r, m).canonical();
    }

    /**
     * The one representation of this map with mirror in {NONE, FRONT_BACK}: LEFT_RIGHT (z to -z) equals FRONT_BACK
     * followed by a half turn, so a LEFT_RIGHT transform becomes FRONT_BACK with two more steps of turn. Every point
     * maps the same, so transforms that move blocks identically have equal canonical forms. {@link #then} always
     * returns this form.
     */
    public Transform canonical() {
        if (mirror != Mirror.LEFT_RIGHT) return this;
        return new Transform(pos, rot.rotate(2), Mirror.FRONT_BACK);
    }

    /**
     * The transform that undoes this one: {@code this.then(inverse())} and {@code inverse().then(this)} are the
     * identity.
     */
    public Transform inverse() {
        Transform c = canonical();
        Transform lin = c.mirror() == Mirror.NONE
                ? new Transform(Vec3i.ZERO, c.rot().inverse(), Mirror.NONE)
                : new Transform(Vec3i.ZERO, c.rot(), c.mirror());
        return new Transform(lin.applyVector(c.pos().neg()), lin.rot(), lin.mirror()).canonical();
    }
}
