package dev.vitruvius.editor.pick;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vitruvius.editor.core.AssemblyNode;
import dev.vitruvius.editor.core.Box;
import dev.vitruvius.editor.core.ObjectNode;
import dev.vitruvius.editor.core.ProjectNode;
import dev.vitruvius.editor.core.Rot;
import dev.vitruvius.editor.core.Scene;
import dev.vitruvius.editor.core.Transform;
import dev.vitruvius.editor.core.Vec3i;
import dev.vitruvius.editor.core.ArrayModifier;
import dev.vitruvius.editor.core.Mirror;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

class PickTest {
    private static Box box(int x, int y, int z, int sx, int sy, int sz) {
        return new Box(new Vec3i(x, y, z), new Vec3i(x + sx, y + sy, z + sz));
    }

    @Test
    void rayBoxHitsMissesAndStartsInside() {
        Box b = box(0, 0, 0, 2, 2, 2);
        assertEquals(3.0, PickMath.rayBox(new double[] {1, 1, -3}, new double[] {0, 0, 1}, b, 100), 1e-9);
        assertTrue(Double.isNaN(PickMath.rayBox(new double[] {5, 1, -3}, new double[] {0, 0, 1}, b, 100)));
        assertEquals(0.0, PickMath.rayBox(new double[] {1, 1, 1}, new double[] {0, 0, 1}, b, 100), 1e-9);
        assertTrue(Double.isNaN(PickMath.rayBox(new double[] {1, 1, -300}, new double[] {0, 0, 1}, b, 100)), "beyond the range");
        assertTrue(Double.isNaN(PickMath.rayBox(new double[] {1, 1, 5}, new double[] {0, 0, 1}, b, 100)), "box behind the ray");
    }

    @Test
    void hitBlockPicksTheOwnerInFront() {
        UUID a = new UUID(0, 1), b = new UUID(0, 2);
        // two overlapping objects; b has front number 1 and wins the shared block
        List<PickMath.Candidate> all = List.of(
                new PickMath.Candidate(a, 2, List.of(box(0, 0, 0, 4, 4, 4))),
                new PickMath.Candidate(b, 1, List.of(box(2, 0, 0, 4, 4, 4))));
        double[] o = {3.5, 1.5, -5}, d = {0, 0, 1};
        assertEquals(b, PickMath.choose(all, new Vec3i(3, 1, 0), o, d, 100));
        assertEquals(a, PickMath.choose(all, new Vec3i(0, 1, 0), o, d, 100));
    }

    @Test
    void fallsBackToRayVsBoxAndToNothing() {
        UUID a = new UUID(0, 1), b = new UUID(0, 2);
        List<PickMath.Candidate> all = List.of(
                new PickMath.Candidate(a, 0, List.of(box(0, 0, 10, 2, 2, 2))),
                new PickMath.Candidate(b, 0, List.of(box(0, 0, 0, 2, 2, 2))));
        double[] o = {1, 1, -5}, d = {0, 0, 1};
        assertEquals(b, PickMath.choose(all, null, o, d, 100), "nearest box");
        // the hit block belongs to nobody (a trimmed derived file): still the nearest box
        assertEquals(b, PickMath.choose(all, new Vec3i(50, 50, 50), o, d, 100));
        assertNull(PickMath.choose(all, null, new double[] {9, 9, -5}, d, 100));
    }

    @Test
    void altCyclesThroughOwnersAlongTheRay() {
        UUID a = new UUID(0, 1), b = new UUID(0, 2), c = new UUID(0, 3);
        List<PickMath.Candidate> all = List.of(
                new PickMath.Candidate(a, 0, List.of(box(0, 0, 0, 2, 2, 2))),
                new PickMath.Candidate(b, 0, List.of(box(0, 0, 4, 2, 2, 2))),
                new PickMath.Candidate(c, 0, List.of(box(0, 0, 8, 2, 2, 2))));
        List<UUID> along = PickMath.along(all, new double[] {1, 1, -5}, new double[] {0, 0, 1}, 100);
        assertEquals(List.of(a, b, c), along);
        List<UUID> order = PickMath.cycleOrder(b, along);
        assertEquals(List.of(b, a, c), order);
        assertEquals(b, PickMath.cycle(order, null));
        assertEquals(a, PickMath.cycle(order, b));
        assertEquals(c, PickMath.cycle(order, a));
        assertEquals(b, PickMath.cycle(order, c), "wraps");
        assertEquals(b, PickMath.cycle(order, new UUID(9, 9)), "unknown current: first");
        assertNull(PickMath.cycle(List.of(), a));
    }

    @Test
    void projectIsTheInverseOfRay() {
        ViewMath.Basis basis = ViewMath.Basis.of(37f, 21f);
        double[] cam = {10, 70, -4};
        double w = 1280, h = 720, fov = 70;
        double[] dir = ViewMath.ray(basis, fov, w, h, 900, 200);
        double[] p = {cam[0] + dir[0] * 12, cam[1] + dir[1] * 12, cam[2] + dir[2] * 12};
        double[] s = ViewMath.project(basis, cam, fov, w, h, p[0], p[1], p[2]);
        assertNotNull(s);
        assertEquals(900, s[0], 1e-6);
        assertEquals(200, s[1], 1e-6);
        // the screen centre looks along the forward vector
        double[] mid = ViewMath.ray(basis, fov, w, h, w / 2, h / 2);
        assertEquals(basis.fx(), mid[0], 1e-9);
        assertEquals(basis.fy(), mid[1], 1e-9);
        // a point behind the camera does not project
        assertNull(ViewMath.project(basis, cam, fov, w, h, cam[0] - basis.fx() * 5, cam[1] - basis.fy() * 5, cam[2] - basis.fz() * 5));
    }

    @Test
    void facingSouthRightIsWestAndUpIsUp() {
        ViewMath.Basis b = ViewMath.Basis.of(0f, 0f);
        assertEquals(1, b.fz(), 1e-9);
        assertEquals(-1, b.rx(), 1e-9);
        assertEquals(1, b.uy(), 1e-9);
        // a point to the camera's right (west, -X) lands in the right half of the screen
        double[] s = ViewMath.project(b, new double[] {0, 0, 0}, 70, 800, 600, -2, 0, 10);
        assertTrue(s[0] > 400);
    }

    @Test
    void worldBoxesFollowRotationArraysAndAssemblies() {
        Scene scene = new Scene();
        ProjectNode p = new ProjectNode("P");
        scene.openProject(p);
        ObjectNode o = new ObjectNode("o", "files/o.litematic");
        o.setTransform(new Transform(new Vec3i(10, 0, 0), Rot.CW_90, Mirror.NONE));
        p.addChild(o);
        WorldBoxes wb = new WorldBoxes(scene, n -> new Vec3i(4, 2, 3));
        Box base = wb.objectBoxes(o.id()).get(0);
        // rotated a quarter turn the 4 x 3 footprint becomes 3 x 4
        assertEquals(3, base.size().x());
        assertEquals(4, base.size().z());
        assertEquals(2, base.size().y());

        ArrayModifier arr = new ArrayModifier();
        arr.count = 3;
        o.setMods(List.of(arr));
        wb = new WorldBoxes(scene, n -> new Vec3i(4, 2, 3));
        assertEquals(3, wb.objectBoxes(o.id()).size(), "base plus two copies");
        assertEquals(1, wb.owners().size());

        // objects inside an assembly belong to the assembly
        AssemblyNode asm = new AssemblyNode("A");
        p.addChild(asm);
        ObjectNode part = new ObjectNode("part", "files/p.litematic");
        asm.addChild(part);
        wb = new WorldBoxes(scene, n -> new Vec3i(1, 1, 1));
        assertNotNull(wb.ownerBoxes(asm.id()));
        assertNull(wb.ownerBoxes(part.id()));
        assertEquals(asm.id(), wb.candidates().stream().filter(c -> c.id().equals(asm.id())).findFirst().orElseThrow().id());

        // hidden and missing objects are not pickable
        o.setHidden(true);
        part.setMissing(true);
        wb = new WorldBoxes(scene, n -> new Vec3i(1, 1, 1));
        assertTrue(wb.owners().isEmpty());
    }
}
