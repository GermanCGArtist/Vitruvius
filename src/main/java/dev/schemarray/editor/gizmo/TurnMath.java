package dev.schemarray.editor.gizmo;

import dev.schemarray.editor.core.Box;
import dev.schemarray.editor.core.Mirror;
import dev.schemarray.editor.core.Rot;
import dev.schemarray.editor.core.Transform;
import dev.schemarray.editor.core.Vec3i;

/** Ghost boxes and ring-drag angles: what a move or a quarter turn does to a box, and how a drag becomes turns. */
public final class TurnMath {
    private TurnMath() {}

    public static Box moved(Box b, Vec3i delta) {
        return new Box(b.min().add(delta), b.maxExclusive().add(delta));
    }

    /** The world map of {@code ops.Rotate}: quarter turns clockwise (seen from above) about the pivot block. */
    public static Transform rotationAbout(Vec3i pivot, int quarterTurns) {
        Transform rot = new Transform(Vec3i.ZERO, Rot.NONE.rotate(quarterTurns), Mirror.NONE);
        return rot.withPos(pivot.sub(rot.applyVector(pivot)));
    }

    public static Box rotated(Box b, Vec3i pivot, int quarterTurns) {
        return b.transformed(rotationAbout(pivot, quarterTurns));
    }

    /** The change of an angle from {@code last} to {@code now} wrapped to (-pi, pi], so a drag past 180 degrees keeps counting. */
    public static double wrapDelta(double now, double last) {
        double d = now - last;
        while (d > Math.PI) d -= 2 * Math.PI;
        while (d <= -Math.PI) d += 2 * Math.PI;
        return d;
    }

    /** Quarter turns for an accumulated angle in radians (rounds to the nearest, halves away from zero). */
    public static int quarterTurns(double radians) {
        double q = radians / (Math.PI / 2);
        return (int) (Math.signum(q) * Math.floor(Math.abs(q) + 0.5));
    }
}
