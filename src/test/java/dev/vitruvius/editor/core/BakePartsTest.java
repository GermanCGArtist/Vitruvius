package dev.vitruvius.editor.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class BakePartsTest {
    private final Map<String, List<Node>> links = new HashMap<>();
    private final AssemblyBounds.Env env = new AssemblyBounds.Env() {
        @Override
        public Vec3i size(ObjectNode o, Path base) {
            return new Vec3i(4, 3, 2);
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

    private static ArrayModifier array(int count) {
        ArrayModifier m = new ArrayModifier();
        m.count = count;
        m.relative = new double[] {1, 0, 0};
        return m;
    }

    @Test
    void anObjectGivesItsOriginalAndItsArrayCopies() {
        ObjectNode o = new ObjectNode("tower", "tower.litematic");
        o.setTransform(at(5, 0, 0));
        o.mods().add(array(3));
        List<BakeParts.Part> parts = BakeParts.collect(List.of(o), Transform.IDENTITY, null, env);
        assertEquals(3, parts.size());
        assertEquals(new Vec3i(5, 0, 0), parts.get(0).world().pos());
        assertEquals(new Vec3i(9, 0, 0), parts.get(1).world().pos());
        assertEquals("tower #1", parts.get(1).label());
        assertEquals("tower", parts.get(0).label());
    }

    @Test
    void anAssemblyArrayRepeatsEveryPartAndHiddenPartsAreSkipped() {
        AssemblyNode a = new AssemblyNode("a");
        a.setTransform(at(0, 10, 0));
        ObjectNode p1 = new ObjectNode("p1", "p1.litematic");
        ObjectNode p2 = new ObjectNode("p2", "p2.litematic");
        p2.setTransform(at(10, 0, 0));
        ObjectNode hidden = new ObjectNode("h", "h.litematic");
        hidden.setHidden(true);
        a.addChild(p1);
        a.addChild(p2);
        a.addChild(hidden);
        a.mods().add(array(2));
        List<BakeParts.Part> parts = BakeParts.collect(List.of(a), Transform.IDENTITY, null, env);
        assertEquals(4, parts.size());
        assertEquals(new Vec3i(0, 10, 0), parts.get(0).world().pos());
        assertEquals(new Vec3i(10, 10, 0), parts.get(1).world().pos());
        assertEquals(new Vec3i(14, 10, 0), parts.get(2).world().pos());
        assertEquals("p1 #1", parts.get(2).label());
    }

    @Test
    void aLinkedAssemblyIsExpandedFromItsFile() {
        ObjectNode part = new ObjectNode("part", "part.litematic");
        part.setTransform(at(2, 0, 0));
        links.put("x.lasm", List.of(part));
        AssemblyNode a = new AssemblyNode("a");
        a.setSource(new AssemblyNode.Link("x.lasm"));
        a.setTransform(new Transform(new Vec3i(50, 0, 0), Rot.CW_180, Mirror.NONE));
        List<BakeParts.Part> parts = BakeParts.collect(List.of(a), Transform.IDENTITY, null, env);
        assertEquals(1, parts.size());
        assertEquals(new Vec3i(48, 0, 0), parts.get(0).world().pos());
        assertEquals(Rot.CW_180, parts.get(0).world().rot());
        assertEquals(Path.of("base"), parts.get(0).base());
    }

    @Test
    void theParentTransformIsApplied() {
        FolderNode f = new FolderNode("f");
        ObjectNode o = new ObjectNode("o", "o.litematic");
        o.setTransform(at(1, 0, 0));
        f.addChild(o);
        List<BakeParts.Part> parts = BakeParts.collect(List.of(f), at(100, 0, 0), null, env);
        assertEquals(new Vec3i(101, 0, 0), parts.get(0).world().pos());
    }
}
