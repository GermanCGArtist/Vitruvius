package dev.schemarray.editor.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AssemblyBoundsTest {
    private static final Vec3i SIZE = new Vec3i(4, 3, 2);
    private final Map<String, List<Node>> links = new HashMap<>();
    private final AssemblyBounds.Env env = new AssemblyBounds.Env() {
        @Override
        public Vec3i size(ObjectNode o, Path base) {
            return o.file().equals("none.litematic") ? null : SIZE;
        }

        @Override
        public AssemblyBounds.Linked linked(AssemblyNode a, Path base) {
            List<Node> parts = links.get(((AssemblyNode.Link) a.source()).path());
            return parts == null ? null : new AssemblyBounds.Linked(parts, Path.of("base"));
        }
    };

    private static Transform at(int x, int y, int z) {
        return Transform.IDENTITY.withPos(new Vec3i(x, y, z));
    }

    private static ObjectNode obj(String name, int x) {
        ObjectNode o = new ObjectNode(name, name + ".litematic");
        o.setTransform(at(x, 0, 0));
        return o;
    }

    private static ArrayModifier array(int count, double rx) {
        ArrayModifier m = new ArrayModifier();
        m.count = count;
        m.relative = new double[] {rx, 0, 0};
        return m;
    }

    @Test
    void theSizeOfAnAssemblyIsTheUnionOfItsParts() {
        AssemblyNode a = new AssemblyNode("a");
        a.addChild(obj("p1", 0));
        a.addChild(obj("p2", 10));
        AssemblyBounds.Frame f = AssemblyBounds.frame(a, null, env);
        assertEquals(new Vec3i(14, 3, 2), f.size());
        assertTrue(f.cells().isEmpty());
    }

    @Test
    void partsAtNegativeOffsetsStillGiveTheirUnion() {
        AssemblyNode a = new AssemblyNode("a");
        a.addChild(obj("p1", -6));
        a.addChild(obj("p2", 2));
        assertEquals(new Vec3i(12, 3, 2), AssemblyBounds.frame(a, null, env).size());
    }

    @Test
    void anArrayMakesCellsStepByTheUnionSize() {
        AssemblyNode a = new AssemblyNode("a");
        a.addChild(obj("p1", 0));
        a.addChild(obj("p2", 10));
        a.mods().add(array(3, 1.0));
        AssemblyBounds.Frame f = AssemblyBounds.frame(a, null, env);
        assertEquals(Map.of("1", new Vec3i(14, 0, 0), "2", new Vec3i(28, 0, 0)), f.cells());
        List<Box> all = AssemblyBounds.boxesIn(a, null, env);
        assertEquals(6, all.size());   // 2 parts x 3 cells
        assertEquals(new Box(new Vec3i(0, 0, 0), new Vec3i(42, 3, 2)), AssemblyBounds.union(all));
    }

    @Test
    void aDisabledArrayAddsNothing() {
        AssemblyNode a = new AssemblyNode("a");
        a.addChild(obj("p1", 0));
        ArrayModifier m = array(3, 1.0);
        m.enabled = false;
        a.mods().add(m);
        assertTrue(AssemblyBounds.frame(a, null, env).cells().isEmpty());
        assertEquals(1, AssemblyBounds.boxesIn(a, null, env).size());
    }

    @Test
    void anObjectArrayInsideCountsInTheAssemblySize() {
        AssemblyNode a = new AssemblyNode("a");
        ObjectNode p = obj("p", 0);
        p.mods().add(array(2, 1.0));
        a.addChild(p);
        assertEquals(new Vec3i(8, 3, 2), AssemblyBounds.frame(a, null, env).size());
    }

    @Test
    void theAssemblyTransformMovesAndTurnsTheBoxes() {
        AssemblyNode a = new AssemblyNode("a");
        a.setTransform(new Transform(new Vec3i(100, 0, 0), Rot.CW_90, Mirror.NONE));
        a.addChild(obj("p", 0));
        List<Box> boxes = AssemblyBounds.boxesIn(a, null, env);
        // CW_90 maps (x,y,z) to (-z,y,x): the 4x3x2 box turns into 2 wide in x and 4 deep in z
        assertEquals(new Vec3i(2, 3, 4), boxes.get(0).size());
        assertEquals(new Vec3i(99, 0, 0), boxes.get(0).min());
    }

    @Test
    void aLinkedAssemblyIsMeasuredFromItsFile() {
        links.put("x.lasm", List.of(obj("p1", 0), obj("p2", 6)));
        AssemblyNode a = new AssemblyNode("a");
        a.setSource(new AssemblyNode.Link("x.lasm"));
        a.mods().add(array(2, 1.0));
        AssemblyBounds.Frame f = AssemblyBounds.frame(a, null, env);
        assertEquals(new Vec3i(10, 3, 2), f.size());
        assertEquals(4, AssemblyBounds.boxesIn(a, null, env).size());
    }

    @Test
    void anUnreadableLinkAndUnknownSizesGiveNothing() {
        AssemblyNode a = new AssemblyNode("a");
        a.setSource(new AssemblyNode.Link("missing.lasm"));
        a.mods().add(array(2, 1.0));
        assertNull(AssemblyBounds.frame(a, null, env).size());
        assertTrue(AssemblyBounds.boxesIn(a, null, env).isEmpty());
        AssemblyNode b = new AssemblyNode("b");
        b.addChild(new ObjectNode("n", "none.litematic"));
        assertNull(AssemblyBounds.frame(b, null, env).size());
    }

    @Test
    void aLoopInTheFilesEnds() {
        AssemblyNode loop = new AssemblyNode("loop");
        loop.setSource(new AssemblyNode.Link("loop.lasm"));
        links.put("loop.lasm", List.of(loop, obj("p", 0)));
        AssemblyNode a = new AssemblyNode("a");
        a.setSource(new AssemblyNode.Link("loop.lasm"));
        assertTrue(AssemblyBounds.boxesIn(a, null, env).size() > 0);   // terminates
    }

    @Test
    void cellUnionsAreOneBoxPerCellInTheWorld() {
        AssemblyNode a = new AssemblyNode("a");
        a.addChild(obj("p1", 0));
        a.mods().add(array(3, 1.0));
        AssemblyBounds.Frame f = AssemblyBounds.frame(a, null, env);
        List<Box> cells = AssemblyBounds.cellUnions(f, at(100, 0, 0));
        assertEquals(3, cells.size());
        assertEquals(new Vec3i(100, 0, 0), cells.get(0).min());
        assertEquals(new Vec3i(104, 0, 0), cells.get(1).min());
        assertEquals(new Vec3i(108, 0, 0), cells.get(2).min());
    }
}
