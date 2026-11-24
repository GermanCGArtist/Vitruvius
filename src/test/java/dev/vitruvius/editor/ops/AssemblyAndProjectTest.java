package dev.vitruvius.editor.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vitruvius.editor.core.ArrayModifier;
import dev.vitruvius.editor.core.AssemblyNode;
import dev.vitruvius.editor.core.BooleanModifier;
import dev.vitruvius.editor.core.FolderNode;
import dev.vitruvius.editor.core.Ids;
import dev.vitruvius.editor.core.Mirror;
import dev.vitruvius.editor.core.Node;
import dev.vitruvius.editor.core.ObjectNode;
import dev.vitruvius.editor.core.ProjectNode;
import dev.vitruvius.editor.core.Rot;
import dev.vitruvius.editor.core.Transform;
import dev.vitruvius.editor.core.Vec3i;
import dev.vitruvius.editor.io.AssemblyIo;

import java.util.List;
import org.junit.jupiter.api.Test;

class AssemblyAndProjectTest {
    private final Fx fx = new Fx();
    private final ObjectNode a = fx.obj(fx.p, "a", 10, 0, 0);
    private final ObjectNode b = fx.obj(fx.p, "b", 20, 5, 3);

    private AssemblyNode turnedAssembly() {
        AssemblyNode asm = fx.asm(fx.p, "asm", 10, 0, 0);
        asm.setTransform(new Transform(new Vec3i(10, 0, 0), Rot.CW_90, Mirror.NONE));
        asm.setFront(5);
        return asm;
    }

    // ---- BreakUp ----

    @Test
    void breakUpBakesTheTransformSoNothingMoves() {
        AssemblyNode asm = turnedAssembly();
        ObjectNode p1 = fx.obj(asm, "p1", 1, 0, 0);
        FolderNode inner = fx.folder(asm, "inner");
        ObjectNode p2 = fx.obj(inner, "p2", 0, 0, 2);
        asm.mods().add(new BooleanModifier(BooleanModifier.Op.DIFFERENCE, b.id()));
        Transform w1 = fx.scene.worldTransform(p1), w2 = fx.scene.worldTransform(p2);
        int index = fx.p.children().indexOf(asm);
        fx.roundTrip(new BreakUp(asm.id(), null));
        FolderNode folder = (FolderNode) fx.p.children().get(index);
        assertEquals("asm", folder.name());
        assertSame(folder, p1.parent());
        assertEquals(w1, fx.scene.worldTransform(p1));
        assertEquals(w2, fx.scene.worldTransform(p2));
        assertEquals(5, p1.front());   // the parts are things of their own now
        assertEquals(5, p2.front());
        assertEquals(1, p1.mods().size());   // the Boolean went with them
        assertEquals(Rot.CW_90, p1.transform().rot());
    }

    @Test
    void breakUpRealisesTheArrayFirst() {
        AssemblyNode asm = fx.asm(fx.p, "asm", 0, 0, 0);
        ObjectNode part = fx.obj(asm, "part", 1, 0, 0);
        ArrayModifier m = new ArrayModifier();
        m.count = 2;
        m.relative = new double[] {0, 0, 1};
        asm.mods().add(m);
        assertNotNull(fx.refusal(new BreakUp(asm.id(), null)));   // needs the size
        fx.roundTrip(new BreakUp(asm.id(), new Vec3i(1, 1, 3)));
        FolderNode outer = (FolderNode) fx.find("asm (realised)");
        assertEquals(List.of("asm", "asm 2"), outer.children().stream().map(Node::name).toList());
        assertEquals(new Vec3i(1, 0, 0), fx.world(part));
        ObjectNode copy = (ObjectNode) outer.children().get(1).children().get(0);
        assertEquals(new Vec3i(1, 0, 3), fx.world(copy));
        assertNotEquals(part.id(), copy.id());
        assertTrue(part.mods().isEmpty());   // the array is not copied onto the parts
    }

    @Test
    void realiseAnAssemblyGivesIndependentFiles() {
        AssemblyNode asm = fx.asm(fx.p, "asm", 0, 0, 0);
        ObjectNode part = fx.obj(asm, "part", 1, 0, 0);
        fx.roundTrip(new BreakUp(asm.id(), null, true));
        assertEquals("files/part 2.litematic", part.file());
        assertTrue(fx.fs.exists("Projects/Metro/files/part.litematic"));
        assertTrue(fx.fs.exists("Projects/Metro/files/part 2.litematic"));
    }

    @Test
    void breakUpRefusals() {
        AssemblyNode l = fx.asm(fx.p, "l", 0, 0, 0);
        l.setSource(new AssemblyNode.Link("files/l.lasm"));
        assertTrue(fx.refusal(new BreakUp(l.id(), null)).reason().contains("make it local first"));
        assertNotNull(fx.refusal(new BreakUp(a.id(), null)));
    }

    // ---- MakeLocal ----

    private java.util.UUID lasmRoofId;

    private AssemblyNode linkedStation() throws Exception {
        AssemblyIo.Lasm l = new AssemblyIo.Lasm("station");
        ObjectNode roof = new ObjectNode("roof", "files/roof.litematic");
        roof.setTransform(Fx.at(1, 0, 0));
        lasmRoofId = roof.id();
        l.nodes.add(roof);
        fx.fs.writeString("Projects/Metro/files/station.lasm", AssemblyIo.toJson(l));
        fx.fs.put("Projects/Metro/files/roof.litematic", "roof");
        AssemblyNode link = fx.asm(fx.p, "station", 50, 0, 0);
        link.setSource(new AssemblyNode.Link("files/station.lasm"));
        return link;
    }

    @Test
    void makeLocalCopiesThePartsAndTheirFiles() throws Exception {
        AssemblyNode link = linkedStation();
        fx.roundTrip(new MakeLocal(link.id()));
        assertFalse(link.isLinked());
        assertEquals(1, link.children().size());
        ObjectNode roof = (ObjectNode) link.children().get(0);
        assertEquals("files/roof 2.litematic", roof.file());
        assertEquals("roof", new String(fx.fs.bytes("Projects/Metro/files/roof 2.litematic")));
        assertTrue(fx.fs.exists("Projects/Metro/files/roof.litematic"));   // the original stays
        assertTrue(roof.id().equals(Ids.derived(link.id() + ":" + lasmRoofId)));
    }

    @Test
    void twoInstancesMadeLocalGetDifferentPartIds() throws Exception {
        AssemblyNode one = linkedStation();
        AssemblyNode two = fx.asm(fx.p, "station 2", 60, 0, 0);
        two.setSource(new AssemblyNode.Link("files/station.lasm"));
        fx.stack.execute(new MakeLocal(one.id()));
        fx.stack.execute(new MakeLocal(two.id()));
        assertNotEquals(one.children().get(0).id(), two.children().get(0).id());
        assertNotEquals("", ((ObjectNode) two.children().get(0)).file());
        assertNotEquals(((ObjectNode) one.children().get(0)).file(), ((ObjectNode) two.children().get(0)).file());
    }

    @Test
    void makeLocalRefusals() throws Exception {
        AssemblyNode link = fx.asm(fx.p, "gone", 0, 0, 0);
        link.setSource(new AssemblyNode.Link("files/gone.lasm"));
        assertTrue(fx.refusal(new MakeLocal(link.id())).reason().contains("missing"));
        fx.fs.writeString("Projects/Metro/files/bad.lasm", "not json");
        link.setSource(new AssemblyNode.Link("files/bad.lasm"));
        assertTrue(fx.refusal(new MakeLocal(link.id())).reason().contains("Cannot read"));
        AssemblyNode station = linkedStation();
        fx.fs.copy("Projects/Metro/files/roof.litematic", "Projects/Metro/files/tmp.litematic");
        fx.fs.moveToTrash("Projects/Metro/files/roof.litematic");
        assertTrue(fx.refusal(new MakeLocal(station.id())).reason().contains("roof.litematic"));
        AssemblyNode local = fx.asm(fx.p, "local", 0, 0, 0);
        assertTrue(fx.refusal(new MakeLocal(local.id())).reason().contains("already local"));
    }

    // ---- CreateAssembly ----

    @Test
    void createAssemblyAtTheMinCornerKeepingWorldPositions() {
        a.setFront(3);
        b.setFront(2);
        int index = fx.p.children().indexOf(a);
        CreateAssembly cmd = new CreateAssembly(Fx.ids(a, b), "Group", null);
        fx.roundTrip(cmd);
        AssemblyNode asm = (AssemblyNode) fx.scene.byId(cmd.assemblyId());
        assertEquals("Group", asm.name());
        assertEquals(new Vec3i(10, 0, 0), fx.pos(asm));
        assertEquals(new Vec3i(0, 0, 0), fx.pos(a));
        assertEquals(new Vec3i(10, 5, 3), fx.pos(b));
        assertEquals(new Vec3i(20, 5, 3), fx.world(b));
        assertEquals(index, fx.p.children().indexOf(asm));
        assertEquals(2, asm.front());   // the best front of its parts
        assertFalse(asm.isLinked());
    }

    @Test
    void createAssemblyWithAGivenAnchorInsideATurnedParent() {
        AssemblyNode outer = turnedAssembly();
        ObjectNode part = fx.obj(outer, "part", 2, 0, 0);
        Vec3i world = fx.world(part);
        CreateAssembly cmd = new CreateAssembly(Fx.ids(part), "Sub", fx.world(part));
        fx.roundTrip(cmd);
        assertEquals(world, fx.world(part));
        assertEquals(fx.scene.worldTransform(part).rot(), Rot.CW_90);
    }

    @Test
    void createAssemblyFromAFolderReplacesTheFolder() {
        FolderNode f = fx.folder(fx.p, "tunnels");
        ObjectNode t1 = fx.obj(f, "t1", 5, 0, 5);
        fx.obj(f, "t2", 6, 1, 7);
        CreateAssembly cmd = new CreateAssembly(Fx.ids(f), null, null);
        fx.roundTrip(cmd);
        AssemblyNode asm = (AssemblyNode) fx.scene.byId(cmd.assemblyId());
        assertEquals("tunnels", asm.name());
        assertEquals(new Vec3i(5, 0, 5), fx.pos(asm));
        assertEquals(2, asm.children().size());
        assertSame(asm, t1.parent());
        assertTrue(fx.p.children().stream().noneMatch(n -> n instanceof FolderNode));
    }

    @Test
    void createAssemblyRefusals() {
        var other = fx.project("Other", "w");
        ObjectNode far = fx.obj(other, "far", 0, 0, 0);
        assertTrue(fx.refusal(new CreateAssembly(Fx.ids(a, far), "x", null)).reason().contains("several projects"));
        assertNotNull(fx.refusal(new CreateAssembly(Fx.ids(fx.folder(fx.p, "empty")), "x", null)));
        AssemblyNode l = fx.asm(fx.p, "l", 0, 0, 0);
        l.setSource(new AssemblyNode.Link("files/l.lasm"));
        assertNotNull(fx.refusal(new CreateAssembly(Fx.ids(fx.obj(l, "lp", 0, 0, 0)), "x", null)));
        assertNotNull(fx.refusal(new CreateAssembly(List.of(), "x", null)));
    }

    // ---- LinkAssembly / LinkProject ----

    @Test
    void linkAnAssemblyFile() {
        LinkAssembly cmd = new LinkAssembly(fx.p.id(), 0, "Station", "files/station.lasm", "Projects/Metro/files/station.lasm", new Vec3i(5, 6, 7));
        fx.roundTrip(cmd);
        AssemblyNode l = (AssemblyNode) fx.scene.byId(cmd.assemblyId());
        assertEquals(new AssemblyNode.Link("files/station.lasm"), l.source());
        assertEquals(new Vec3i(5, 6, 7), fx.pos(l));
        assertSame(l, fx.p.children().get(0));
    }

    @Test
    void linkingThatWouldLoopIsRefusedWithAReason() {
        ProjectNode other = fx.project("Other", "w");
        fx.ctx.setLinks(path -> path.equals("Projects/Other") ? List.of("Projects/Metro") : List.of());
        Refusal r = fx.refusal(new LinkProject(fx.p.id(), -1, other, "../Other", Vec3i.ZERO));
        assertTrue(r.reason().contains("loop"), r.reason());
        assertTrue(r.reason().contains("Projects/Other -> Projects/Metro"), r.reason());
        // a project into itself
        assertTrue(fx.refusal(new LinkProject(fx.p.id(), -1, fx.p, "x", Vec3i.ZERO)).reason().contains("loop"));
        // the other direction is fine
        LinkProject ok = new LinkProject(other.id(), -1, fx.p, "../Metro", Vec3i.ZERO);
        fx.ctx.setLinks(path -> List.of());
        fx.roundTrip(ok);
        assertEquals("Metro", other.children().get(0).name());
    }

    // ---- MoveToProject ----

    @Test
    void moveToAnotherProjectMovesAFileNobodyElseUses() {
        ProjectNode other = fx.project("Other", "w");
        fx.roundTrip(new MoveToProject(Fx.ids(a), other.id(), -1));
        assertSame(other, a.parent());
        assertEquals("files/a.litematic", a.file());
        assertTrue(fx.fs.exists("Projects/Other/files/a.litematic"));
        assertFalse(fx.fs.exists("Projects/Metro/files/a.litematic"));
        assertEquals(new Vec3i(10, 0, 0), fx.world(a));
        assertTrue(fx.sink.dirty.contains(other.id()) && fx.sink.dirty.contains(fx.p.id()));
        fx.stack.undo();
        assertTrue(fx.fs.exists("Projects/Metro/files/a.litematic"));
        assertFalse(fx.fs.exists("Projects/Other/files/a.litematic"));
    }

    @Test
    void moveToAProjectWithAnAbsolutePathUsesThePortPath() {
        // the project manager keeps absolute project paths; the file port only takes paths below its root
        java.nio.file.Path root = java.nio.file.Path.of("schem-root").toAbsolutePath().normalize();
        fx.ctx.setPortRoot(root);
        ProjectNode other = fx.project("Other", "w");
        other.setPath(root.resolve("Projects").resolve("Other").toString());
        fx.roundTrip(new MoveToProject(Fx.ids(a), other.id(), -1));
        assertSame(other, a.parent());
        assertEquals("files/a.litematic", a.file());
        assertTrue(fx.fs.exists("Projects/Other/files/a.litematic"));
        assertFalse(fx.fs.exists("Projects/Metro/files/a.litematic"));
    }

    @Test
    void moveToAnotherProjectCopiesAFileThatIsStillUsed() {
        ProjectNode other = fx.project("Other", "w");
        ObjectNode sharer = new ObjectNode("a again", a.file());
        fx.p.addChild(sharer);
        fx.roundTrip(new MoveToProject(Fx.ids(a), other.id(), -1));
        assertTrue(fx.fs.exists("Projects/Metro/files/a.litematic"));   // the sharer keeps working
        assertTrue(fx.fs.exists("Projects/Other/files/a.litematic"));
        fx.stack.undo();
        assertFalse(fx.fs.exists("Projects/Other/files/a.litematic"));
        assertEquals(2, fx.fs.list("Projects/Other/.trash").size());   // the copies went to the trash (roundTrip undid once already)
    }

    @Test
    void moveGivesAFreeNameWhenTheTargetHasThatFile() {
        ProjectNode other = fx.project("Other", "w");
        fx.fs.put("Projects/Other/files/a.litematic", "someone else's");
        fx.roundTrip(new MoveToProject(Fx.ids(a), other.id(), -1));
        assertEquals("files/a 2.litematic", a.file());
        assertEquals("someone else's", new String(fx.fs.bytes("Projects/Other/files/a.litematic")));
    }

    @Test
    void moveToAFolderOfAnotherProjectWithAnAssembly() {
        ProjectNode other = fx.project("Other", "w");
        FolderNode dest = fx.folder(other, "dest");
        AssemblyNode asm = turnedAssembly();
        ObjectNode part = fx.obj(asm, "part", 1, 0, 0);
        Transform w = fx.scene.worldTransform(part);
        fx.roundTrip(new MoveToProject(Fx.ids(asm), dest.id(), 0));
        assertSame(dest, asm.parent());
        assertEquals(w, fx.scene.worldTransform(part));
        assertTrue(fx.fs.exists("Projects/Other/files/part.litematic"));
    }

    @Test
    void moveToProjectRefusals() {
        ProjectNode other = fx.project("Other", "w");
        assertTrue(fx.refusal(new MoveToProject(Fx.ids(a), fx.p.id(), -1)).reason().contains("already"));
        assertNotNull(fx.refusal(new MoveToProject(Fx.ids(a), a.id(), -1)));
        assertNotNull(fx.refusal(new MoveToProject(Fx.ids(other), fx.p.id(), -1)));
        AssemblyNode l = fx.asm(fx.p, "l", 0, 0, 0);
        l.setSource(new AssemblyNode.Link("files/l.lasm"));
        assertTrue(fx.refusal(new MoveToProject(Fx.ids(l), other.id(), -1)).reason().contains("make it local first"));
        a.setFile("files/missing.litematic");
        assertTrue(fx.refusal(new MoveToProject(Fx.ids(a), other.id(), -1)).reason().contains("missing"));
    }

    // ---- NewProject / ConvertFolderToProject ----

    @Test
    void newProjectWritesProjectVprojAndOpensIt() {
        NewProject cmd = new NewProject("Fresh", "w2", "minecraft:the_nether", new Vec3i(1, 2, 3));
        fx.roundTrip(cmd);
        ProjectNode p = (ProjectNode) fx.scene.byId(cmd.projectId());
        assertEquals("Projects/Fresh", p.path());
        assertSame(p, fx.scene.activeProject());
        assertTrue(new String(fx.fs.bytes("Projects/Fresh/project.vproj")).contains("Fresh"));
        fx.stack.undo();
        assertSame(fx.p, fx.scene.activeProject());
        assertEquals(1, fx.scene.projects().size());
        assertFalse(fx.fs.exists("Projects/Fresh/project.vproj"));
        fx.stack.redo();
        assertEquals("Projects/Fresh", ((ProjectNode) fx.scene.byId(cmd.projectId())).path());
        NewProject again = new NewProject("Fresh", "w2", null, null);
        fx.stack.execute(again);
        assertEquals("Projects/Fresh 2", ((ProjectNode) fx.scene.byId(again.projectId())).path());
        assertNotNull(fx.refusal(new NewProject(" ", "w", null, null)));
    }

    @Test
    void convertAFolderToAProject() {
        FolderNode f = fx.folder(fx.p, "tunnels");
        ObjectNode t1 = fx.obj(f, "t1", 1, 0, 0);
        fx.obj(f, "t2", 2, 0, 0);
        ConvertFolderToProject cmd = new ConvertFolderToProject(f.id(), null);
        fx.roundTrip(cmd);
        ProjectNode np = (ProjectNode) fx.scene.byId(cmd.projectId());
        assertEquals("tunnels", np.name());
        assertEquals("w", np.world());
        assertEquals(2, np.children().size());
        assertSame(np, t1.parent());
        assertTrue(fx.fs.exists("Projects/tunnels/files/t1.litematic"));
        assertFalse(fx.fs.exists("Projects/Metro/files/t1.litematic"));
        assertTrue(new String(fx.fs.bytes("Projects/tunnels/project.vproj")).contains("t2"));
        assertFalse(fx.scene.allNodes().stream().anyMatch(n -> n instanceof FolderNode && n.name().equals("tunnels")));
    }

    @Test
    void convertLooseToAProject() {
        ObjectNode x = new ObjectNode("x", "loose/x.litematic");
        fx.scene.loose().addChild(x);
        fx.fs.put("loose/x.litematic", "loose data");
        ConvertFolderToProject cmd = new ConvertFolderToProject(fx.scene.loose().id(), "Old stuff");
        fx.roundTrip(cmd);
        assertTrue(fx.scene.loose().children().isEmpty());
        assertEquals("files/x.litematic", x.file());
        assertEquals("loose data", new String(fx.fs.bytes("Projects/Old stuff/files/x.litematic")));
        assertEquals("w", ((ProjectNode) fx.scene.byId(cmd.projectId())).world());
        assertNotNull(fx.refusal(new ConvertFolderToProject(a.id(), null)));
        assertNotNull(fx.refusal(new ConvertFolderToProject(fx.scene.loose().id(), "Again")));
    }
}
