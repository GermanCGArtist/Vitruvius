package dev.schemarray.editor.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.schemarray.editor.core.AssemblyNode;
import dev.schemarray.editor.core.BooleanModifier;
import dev.schemarray.editor.core.FolderNode;
import dev.schemarray.editor.core.Mirror;
import dev.schemarray.editor.core.Node;
import dev.schemarray.editor.core.ObjectNode;
import dev.schemarray.editor.core.Rot;
import dev.schemarray.editor.core.Transform;
import dev.schemarray.editor.core.Vec3i;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class StructureCommandsTest {
    private final Fx fx = new Fx();
    private final ObjectNode a = fx.obj(fx.p, "a", 10, 0, 0);
    private final ObjectNode b = fx.obj(fx.p, "b", 20, 0, 0);
    private final ObjectNode c = fx.obj(fx.p, "c", 30, 0, 0);

    {
        a.setFront(1);
        b.setFront(2);
    }

    // ---- Duplicate ----

    @Test
    void duplicateCopiesTheNodeAndItsFile() {
        fx.roundTrip(new Duplicate(Fx.ids(a), new Vec3i(2, 0, 0)));
        ObjectNode copy = (ObjectNode) fx.find("a 2");
        assertNotNull(copy);
        assertNotEquals(a.id(), copy.id());
        assertEquals(new Vec3i(12, 0, 0), fx.pos(copy));
        assertEquals("files/a 2.litematic", copy.file());
        assertTrue(fx.fs.exists("Projects/Metro/files/a 2.litematic"));
        assertEquals(1, fx.p.children().indexOf(copy));   // right after the original
        assertEquals(3, copy.front());                    // behind everything
        assertEquals(1, a.front());
        assertEquals(new Vec3i(10, 0, 0), fx.pos(a));
    }

    @Test
    void undoMovesTheCopiedFileToTrashAndNeverDeletesIt() {
        Duplicate d = new Duplicate(Fx.ids(a), Vec3i.ZERO);
        fx.stack.execute(d);
        fx.stack.undo();
        assertFalse(fx.fs.exists("Projects/Metro/files/a 2.litematic"));
        List<String> trash = fx.fs.list("Projects/Metro/.trash");
        assertEquals(1, trash.size());
        assertTrue(trash.get(0).endsWith("a 2.litematic"));
        assertEquals("data of a", new String(fx.fs.bytes(trash.get(0))));
    }

    @Test
    void duplicateWithoutFileCopySharesTheFile() {
        fx.stack.execute(new Duplicate(Fx.ids(a), Vec3i.ZERO, false));
        assertEquals("files/a.litematic", ((ObjectNode) fx.find("a 2")).file());
        assertFalse(fx.fs.exists("Projects/Metro/files/a 2.litematic"));
    }

    @Test
    void duplicatingTwiceNumbersTheNames() {
        fx.stack.execute(new Duplicate(Fx.ids(a), Vec3i.ZERO));
        fx.stack.execute(new Duplicate(Fx.ids(a), Vec3i.ZERO));
        assertNotNull(fx.find("a 2"));
        assertNotNull(fx.find("a 3"));
        assertEquals("files/a 3.litematic", ((ObjectNode) fx.find("a 3")).file());
    }

    @Test
    void duplicateAFolderWithAnAssemblyMakesNewIdsEverywhere() {
        FolderNode f = fx.folder(fx.p, "f");
        AssemblyNode asm = fx.asm(f, "asm", 0, 0, 0);
        fx.obj(asm, "part", 1, 0, 0);
        Set<UUID> before = new HashSet<>();
        for (Node n : fx.scene.allNodes()) before.add(n.id());
        fx.roundTrip(new Duplicate(Fx.ids(f), new Vec3i(0, 0, 8)));
        FolderNode copy = (FolderNode) fx.find("f 2");
        List<Node> sub = copy.subtree();
        assertEquals(3, sub.size());
        for (Node n : sub) assertFalse(before.contains(n.id()));
        assertEquals(new Vec3i(0, 0, 8), fx.pos(sub.get(1)));
        assertEquals(new Vec3i(1, 0, 0), fx.pos(sub.get(2)));   // the part moves with its assembly
    }

    @Test
    void duplicateKeepsBooleanTargetsPointingAtCopiesThatWereMadeToo() {
        c.mods().add(new BooleanModifier(BooleanModifier.Op.DIFFERENCE, a.id()));
        fx.stack.execute(new Duplicate(Fx.ids(a, c), Vec3i.ZERO, false));
        ObjectNode a2 = (ObjectNode) fx.find("a 2"), c2 = (ObjectNode) fx.find("c 2");
        assertEquals(a2.id(), ((BooleanModifier) c2.mods().get(0)).targetId);
        assertEquals(a.id(), ((BooleanModifier) c.mods().get(0)).targetId);
        // a copy of c alone keeps cutting with the original a
        fx.stack.execute(new Duplicate(Fx.ids(c), Vec3i.ZERO, false));
        assertEquals(a.id(), ((BooleanModifier) ((ObjectNode) fx.find("c 3")).mods().get(0)).targetId);
    }

    @Test
    void duplicateOffsetIsInWorldSpaceInsideATurnedAssembly() {
        AssemblyNode asm = fx.asm(fx.p, "asm", 0, 0, 0);
        asm.setTransform(new Transform(Vec3i.ZERO, Rot.CW_90, Mirror.NONE));
        ObjectNode part = fx.obj(asm, "part", 1, 0, 0);
        fx.stack.execute(new Duplicate(Fx.ids(part), new Vec3i(0, 0, 5), false));
        assertEquals(new Vec3i(0, 0, 6), fx.world(fx.find("part 2")));
    }

    @Test
    void duplicateRefusals() {
        assertNotNull(fx.refusal(new Duplicate(Fx.ids(fx.p), Vec3i.ZERO)));
        AssemblyNode linked = fx.asm(fx.p, "l", 0, 0, 0);
        linked.setSource(new AssemblyNode.Link("files/l.lasm"));
        ObjectNode part = fx.obj(linked, "lp", 0, 0, 0);
        assertNotNull(fx.refusal(new Duplicate(Fx.ids(part), Vec3i.ZERO)));
    }

    // ---- LinkedDuplicate ----

    @Test
    void linkedDuplicateOfAnObjectSharesTheFile() {
        int files = fx.fs.allFiles().size();
        fx.roundTrip(new LinkedDuplicate(Fx.ids(a), new Vec3i(0, 4, 0)));
        ObjectNode copy = (ObjectNode) fx.find("a 2");
        assertEquals(a.file(), copy.file());
        assertEquals(files, fx.fs.allFiles().size());
        assertEquals(new Vec3i(10, 4, 0), fx.pos(copy));
    }

    @Test
    void linkedDuplicateOfALinkedAssemblyKeepsTheLinkPath() {
        AssemblyNode l = fx.asm(fx.p, "station", 0, 0, 0);
        l.setSource(new AssemblyNode.Link("files/station.lasm"));
        fx.roundTrip(new LinkedDuplicate(Fx.ids(l), new Vec3i(9, 0, 0)));
        AssemblyNode copy = (AssemblyNode) fx.find("station 2");
        assertEquals(new AssemblyNode.Link("files/station.lasm"), copy.source());
        assertTrue(copy.children().isEmpty());
    }

    @Test
    void linkedDuplicateOfALocalAssemblyCopiesPartsAndMarksBothAsAGroup() {
        AssemblyNode asm = fx.asm(fx.p, "gate", 0, 0, 0);
        ObjectNode part = fx.obj(asm, "pillar", 1, 0, 0);
        fx.roundTrip(new LinkedDuplicate(Fx.ids(asm), new Vec3i(0, 0, 6)));
        AssemblyNode copy = (AssemblyNode) fx.find("gate 2");
        String group = asm.extra().get("linkedGroup").getAsString();
        assertEquals(group, copy.extra().get("linkedGroup").getAsString());
        assertEquals(1, copy.children().size());
        ObjectNode partCopy = (ObjectNode) copy.children().get(0);
        assertNotEquals(part.id(), partCopy.id());
        assertEquals(part.file(), partCopy.file());
    }

    // ---- Delete ----

    @Test
    void deleteIsUndoneAtTheSameIndex() {
        fx.roundTrip(new Delete(Fx.ids(b)));
        assertEquals(List.of(a, c), fx.p.children());
        fx.stack.undo();
        assertEquals(List.of(a, b, c), fx.p.children());
        assertSame(fx.p, b.parent());
    }

    @Test
    void deleteSeveralIncludingANestedOne() {
        FolderNode f = fx.folder(fx.p, "f");
        ObjectNode inner = fx.obj(f, "inner", 0, 0, 0);
        fx.roundTrip(new Delete(Fx.ids(a, f, inner, c)));
        assertEquals(List.of(b), fx.p.children());
        assertTrue(fx.sink.affected.contains(inner.id()));   // the reconciler must drop its placements
    }

    @Test
    void deleteLeavesTheFilesAlone() {
        fx.stack.execute(new Delete(Fx.ids(a)));
        assertTrue(fx.fs.exists("Projects/Metro/files/a.litematic"));
    }

    @Test
    void deleteRefusals() {
        assertNotNull(fx.refusal(new Delete(Fx.ids(fx.p))));
        assertNotNull(fx.refusal(new Delete(Fx.ids(fx.scene.loose()))));
        AssemblyNode l = fx.asm(fx.p, "l", 0, 0, 0);
        l.setSource(new AssemblyNode.Link("files/l.lasm"));
        ObjectNode part = fx.obj(l, "lp", 0, 0, 0);
        assertNotNull(fx.refusal(new Delete(Fx.ids(part))));
    }

    // ---- Reparent / reorder ----

    @Test
    void reparentIntoAFolderAtAnIndex() {
        FolderNode f = fx.folder(fx.p, "f");
        fx.obj(f, "x", 0, 0, 0);
        fx.roundTrip(new Reparent(Fx.ids(a, b), f.id(), 0));
        assertEquals(List.of("a", "b", "x"), f.children().stream().map(Node::name).toList());
        assertEquals(List.of(c, f), fx.p.children());
    }

    @Test
    void reorderWithinTheSameParent() {
        fx.roundTrip(new Reparent(Fx.ids(a), fx.p.id(), 3));   // 3 = behind c: indexes count before the move
        assertEquals(List.of(b, c, a), fx.p.children());
        fx.stack.undo();
        fx.roundTrip((Command) Reparent.reorder(c, 0));
        assertEquals(List.of(c, a, b), fx.p.children());
    }

    @Test
    void reparentKeepsTheNodeWhereItIsInTheWorld() {
        AssemblyNode asm = fx.asm(fx.p, "asm", 100, 0, 0);
        asm.setTransform(new Transform(new Vec3i(100, 0, 0), Rot.CW_90, Mirror.NONE));
        ObjectNode part = fx.obj(asm, "part", 1, 0, 0);
        Transform world = fx.scene.worldTransform(part);
        fx.roundTrip(new Reparent(Fx.ids(part), fx.p.id(), -1));
        assertEquals(world, fx.scene.worldTransform(part));
        assertSame(fx.p, part.parent());
        fx.stack.undo();
        assertEquals(new Vec3i(1, 0, 0), fx.pos(part));
        fx.roundTrip(new Reparent(Fx.ids(a), asm.id(), -1));   // and into an assembly
        assertEquals(new Vec3i(10, 0, 0), fx.world(a));
    }

    @Test
    void reparentRefusals() {
        FolderNode f = fx.folder(fx.p, "f");
        FolderNode inner = fx.folder(f, "inner");
        assertTrue(fx.refusal(new Reparent(Fx.ids(f), f.id(), 0)).reason().contains("into itself"));
        assertTrue(fx.refusal(new Reparent(Fx.ids(f), inner.id(), 0)).reason().contains("into itself"));
        assertTrue(fx.refusal(new Reparent(Fx.ids(a), b.id(), 0)).reason().contains("cannot hold"));
        assertNotNull(fx.refusal(new Reparent(Fx.ids(a), f.id(), 5)));
        assertNotNull(fx.refusal(new Reparent(Fx.ids(fx.p), f.id(), 0)));

        AssemblyNode l = fx.asm(fx.p, "station", 0, 0, 0);
        l.setSource(new AssemblyNode.Link("files/station.lasm"));
        ObjectNode part = fx.obj(l, "roof", 0, 0, 0);
        Refusal out = fx.refusal(new Reparent(Fx.ids(part), fx.p.id(), 0));
        assertTrue(out.reason().contains("cannot be moved out of it"), out.reason());
        assertNotNull(fx.refusal(new Reparent(Fx.ids(a), l.id(), 0)));   // nor into a linked one

        var other = fx.project("Other", "w");
        Refusal cross = fx.refusal(new Reparent(Fx.ids(a), other.id(), 0));
        assertTrue(cross.reason().contains("Move to project"), cross.reason());
    }

    // ---- Rename, hide, new folder ----

    @Test
    void rename() {
        fx.roundTrip(new Rename(a.id(), "  alpha "));
        assertEquals("alpha", a.name());
        assertNotNull(fx.refusal(new Rename(fx.scene.loose().id(), "x")));
        assertNotNull(fx.refusal(new Rename(a.id(), "")));
    }

    @Test
    void hideAndShowSeveral() {
        fx.roundTrip(new SetHidden(Fx.ids(a, b), true));
        assertTrue(a.hidden() && b.hidden() && !c.hidden());
        assertEquals("Hide 2 objects", fx.stack.undoLabel().orElseThrow());
        fx.roundTrip(new SetHidden(Fx.ids(a), false));
        assertFalse(a.hidden());
    }

    @Test
    void newFolderGetsAFreeNameAndAPlace() {
        NewFolder nf = new NewFolder(fx.p.id(), "Tunnels", 1);
        fx.roundTrip(nf);
        assertEquals("Tunnels", fx.p.children().get(1).name());
        assertEquals(nf.folderId(), fx.p.children().get(1).id());
        fx.stack.execute(new NewFolder(fx.p.id(), "Tunnels", -1));
        assertEquals("Tunnels 2", fx.p.children().get(fx.p.children().size() - 1).name());
        assertNotNull(fx.refusal(new NewFolder(a.id(), "x", -1)));
        assertNull(fx.stack.execute(new NewFolder(fx.p.id(), "", -1)).orElse(null));
    }
}
