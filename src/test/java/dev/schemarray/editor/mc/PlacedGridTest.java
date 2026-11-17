package dev.schemarray.editor.mc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.schemarray.editor.core.Mirror;
import dev.schemarray.editor.core.Rot;
import dev.schemarray.editor.core.Transform;
import dev.schemarray.editor.core.Vec3i;
import java.util.List;
import org.junit.jupiter.api.Test;

class PlacedGridTest {
    @Test
    void worldAndLocalRoundTripForEveryRotationAndMirror() {
        Vec3i[] pts = {new Vec3i(0, 0, 0), new Vec3i(3, 1, -2), new Vec3i(-4, 7, 5)};
        for (Rot r : Rot.values()) {
            for (Mirror m : Mirror.values()) {
                Transform t = new Transform(new Vec3i(10, -4, 6), r, m);
                for (Vec3i p : pts) {
                    assertEquals(p, PlacedGrid.toLocal(t, t.apply(p)), r + " " + m);
                    assertEquals(p, t.apply(PlacedGrid.toLocal(t, p)), r + " " + m);
                }
            }
        }
    }

    @Test
    void aSingleLocalBlockIsFoundAtItsWorldPositionForEveryRotationAndMirror() {
        Vec3i block = new Vec3i(2, 1, 3);
        for (Rot r : Rot.values()) {
            for (Mirror m : Mirror.values()) {
                Transform t = new Transform(new Vec3i(-5, 20, 8), r, m);
                PlacedGrid g = new PlacedGrid(List.of(new PlacedGrid.Copy((x, y, z) -> x == 2 && y == 1 && z == 3, t)));
                Vec3i w = t.apply(block);
                assertTrue(g.solid(w.x(), w.y(), w.z()), r + " " + m);
                assertFalse(g.solid(w.x() + 1, w.y(), w.z()), r + " " + m);
                assertFalse(g.solid(w.x(), w.y() + 1, w.z()), r + " " + m);
            }
        }
    }

    @Test
    void severalCopiesAreAUnion() {
        var a = new PlacedGrid.Copy((x, y, z) -> x == 0 && y == 0 && z == 0, Transform.IDENTITY);
        var b = new PlacedGrid.Copy((x, y, z) -> x == 0 && y == 0 && z == 0, Transform.IDENTITY.withPos(new Vec3i(5, 0, 0)));
        PlacedGrid g = new PlacedGrid(List.of(a, b));
        assertTrue(g.solid(0, 0, 0));
        assertTrue(g.solid(5, 0, 0));
        assertFalse(g.solid(3, 0, 0));
    }
}
