package dev.vitruvius.editor.pick;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vitruvius.editor.core.ArrayModifier;
import dev.vitruvius.editor.core.AssemblyBounds;
import dev.vitruvius.editor.core.AssemblyNode;
import dev.vitruvius.editor.core.Box;
import dev.vitruvius.editor.core.Node;
import dev.vitruvius.editor.core.ObjectNode;
import dev.vitruvius.editor.core.ProjectNode;
import dev.vitruvius.editor.core.Scene;
import dev.vitruvius.editor.core.Transform;
import dev.vitruvius.editor.core.Vec3i;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Boxes of assemblies with arrays and of linked assemblies (WP 3.1 / 4.1). */
class WorldBoxesAssemblyTest {
    private static final Vec3i SIZE = new Vec3i(4, 3, 2);
    private final Scene scene = new Scene();
    private final ProjectNode proj = new ProjectNode("P");
    private List<Node> linkedParts = List.of();
    private final AssemblyBounds.Env env = new AssemblyBounds.Env() {
        @Override
        public Vec3i size(ObjectNode o, Path base) {
            return SIZE;
        }

        @Override
        public AssemblyBounds.Linked linked(AssemblyNode a, Path base) {
            return new AssemblyBounds.Linked(linkedParts, Path.of("base"));
        }
    };

    WorldBoxesAssemblyTest() {
        proj.setPath("P");
        scene.openProject(proj);
    }

    private static Transform at(int x, int y, int z) {
        return Transform.IDENTITY.withPos(new Vec3i(x, y, z));
    }

    private static ArrayModifier array(int count) {
        ArrayModifier m = new ArrayModifier();
        m.count = count;
        m.relative = new double[] {1, 0, 0};
        return m;
    }

    private WorldBoxes boxes() {
        return new WorldBoxes(scene, o -> SIZE, env);
    }

    @Test
    void anAssemblyWithAnArrayHasABoxPerCellAndTheUnionCoversAll() {
        AssemblyNode a = new AssemblyNode("a");
        a.setTransform(at(100, 0, 0));
        ObjectNode p = new ObjectNode("p", "p.litematic");
        a.addChild(p);
        a.mods().add(array(3));
        proj.addChild(a);
        WorldBoxes wb = boxes();
        WorldBoxes.Owned o = wb.ownerBoxes(a.id());
        assertEquals(3, o.boxes().size());
        assertEquals(new Box(new Vec3i(100, 0, 0), new Vec3i(112, 3, 2)), o.union());
        assertEquals(3, wb.cellBoxes(a.id()).size());
        assertEquals(new Vec3i(104, 0, 0), wb.cellBoxes(a.id()).get(1).min());
        assertEquals(o.union(), wb.unionOf(scene, List.of(a.id())), "selecting the assembly frames all its copies");
        assertEquals(1, wb.objectBoxes(p.id()).size(), "the object itself still has only its own box");
    }

    @Test
    void anAssemblyWithoutAnArrayKeepsItsOldBehaviourAndHasNoCells() {
        AssemblyNode a = new AssemblyNode("a");
        a.addChild(new ObjectNode("p", "p.litematic"));
        proj.addChild(a);
        WorldBoxes wb = boxes();
        assertEquals(1, wb.ownerBoxes(a.id()).boxes().size());
        assertTrue(wb.cellBoxes(a.id()).isEmpty());
    }

    @Test
    void anArrayDeepInsideMakesTheTopAssemblyMeasuredToo() {
        AssemblyNode top = new AssemblyNode("top");
        AssemblyNode inner = new AssemblyNode("inner");
        inner.addChild(new ObjectNode("p", "p.litematic"));
        inner.mods().add(array(2));
        top.addChild(inner);
        proj.addChild(top);
        WorldBoxes wb = boxes();
        assertEquals(2, wb.ownerBoxes(top.id()).boxes().size());
        assertEquals(new Vec3i(8, 3, 2), wb.ownerBoxes(top.id()).union().size());
    }

    @Test
    void aLinkedAssemblyHasBoxesFromItsFileAndItsArrayCells() {
        ObjectNode part = new ObjectNode("part", "part.litematic");
        part.setTransform(at(2, 0, 0));
        linkedParts = List.of(part);
        AssemblyNode a = new AssemblyNode("linked");
        a.setSource(new AssemblyNode.Link("files/x.lasm"));
        a.setTransform(at(10, 0, 0));
        proj.addChild(a);
        WorldBoxes wb = boxes();
        assertEquals(new Box(new Vec3i(12, 0, 0), new Vec3i(16, 3, 2)), wb.ownerBoxes(a.id()).union());
        assertTrue(a.children().isEmpty());

        a.mods().add(array(2));
        wb = boxes();
        assertEquals(2, wb.ownerBoxes(a.id()).boxes().size());
        assertEquals(new Box(new Vec3i(12, 0, 0), new Vec3i(20, 3, 2)), wb.ownerBoxes(a.id()).union());
        UUID id = a.id();
        assertTrue(wb.candidates().stream().anyMatch(c -> c.id().equals(id)), "it can be picked");
    }

    @Test
    void withoutAnEnvironmentTheOldConstructorIgnoresLinkedAssemblies() {
        AssemblyNode a = new AssemblyNode("linked");
        a.setSource(new AssemblyNode.Link("files/x.lasm"));
        proj.addChild(a);
        assertNull(new WorldBoxes(scene, o -> SIZE).ownerBoxes(a.id()));
    }
}
