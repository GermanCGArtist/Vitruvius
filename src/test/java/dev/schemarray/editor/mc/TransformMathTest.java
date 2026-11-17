package dev.schemarray.editor.mc;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.schemarray.editor.core.Mirror;
import dev.schemarray.editor.core.Rot;
import dev.schemarray.editor.core.Transform;
import dev.schemarray.editor.core.Vec3i;
import org.junit.jupiter.api.Test;

class TransformMathTest {
    @Test
    void inverseUndoesEveryRotMirrorAndOffset() {
        Vec3i[] points = {new Vec3i(0, 0, 0), new Vec3i(3, -2, 5), new Vec3i(-7, 4, 1)};
        for (Rot r : Rot.values()) {
            for (Mirror m : Mirror.values()) {
                Transform t = new Transform(new Vec3i(11, -3, 7), r, m);
                Transform inv = TransformMath.inverse(t);
                assertEquals(Transform.IDENTITY, t.then(inv), r + " " + m);
                assertEquals(Transform.IDENTITY, inv.then(t), r + " " + m);
                for (Vec3i p : points) assertEquals(p, inv.apply(t.apply(p)), r + " " + m + " " + p);
            }
        }
    }
}
