package dev.vitruvius.editor.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vitruvius.editor.core.ArrayCells;
import dev.vitruvius.editor.core.ArrayModifier;
import dev.vitruvius.editor.core.AssemblyBounds;
import dev.vitruvius.editor.core.AssemblyNode;
import dev.vitruvius.editor.core.FolderNode;
import dev.vitruvius.editor.core.Node;
import dev.vitruvius.editor.core.ObjectNode;
import dev.vitruvius.editor.core.Vec3i;
import dev.vitruvius.editor.io.AssemblyIo;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/** M3 and M4: Save as linked, Make local from other places, the break-up chain, and realised arrays that are arrayed again. */
class LinkedAssemblyOpsTest {
    private final Fx fx = new Fx();

    private static ArrayModifier array(int count, double rx) {
        ArrayModifier m = new ArrayModifier();
        m.count = count;
        m.relative = new double[] {rx, 0, 0};
        return m;
    }

    private AssemblyNode twoPartAssembly(String name) {
        AssemblyNode asm = fx.asm(fx.p, name, 100, 0, 0);
        fx.obj(asm, "p1", 0, 0, 0);
        fx.obj(asm, "p2", 6, 0, 0);
        return asm;
    }

    // ---- Save as linked ----

    @Test
    void saveAsLinkedWritesTheLasmAndTheAssemblyBecomesAnInstanceOfIt() throws IOException {
        AssemblyNode asm = twoPartAssembly("tower");
        asm.setFront(3);
        asm.mods().add(array(2, 1.0));
        List<Node> parts = List.copyOf(asm.children());
        SaveAsLinked cmd = new SaveAsLinked(asm.id(), null);
        fx.roundTrip(cmd);
        assertEquals("files/assemblies/tower.lasm", cmd.lasmPath());
        assertTrue(asm.isLinked());
        assertEquals("files/assemblies/tower.lasm", ((AssemblyNode.Link) asm.source()).path());
        assertTrue(asm.children().isEmpty());
        assertEquals(3, asm.front(), "the instance keeps its front number");
        assertEquals(1, asm.mods().size(), "and its modifiers");
        assertEquals(fx.at(100, 0, 0), asm.transform(), "and its place");
        AssemblyIo.Lasm l = AssemblyIo.fromJson(fx.fs.readString("Projects/Metro/files/assemblies/tower.lasm"));
        assertEquals(2, l.nodes.size());
        assertEquals(parts.get(0).id(), l.nodes.get(0).id(), "the parts keep their ids in the file");
        assertEquals("files/p1.litematic", ((ObjectNode) l.nodes.get(0)).file());
        assertEquals(fx.at(6, 0, 0), l.nodes.get(1).localTransform());
    }

    @Test
    void undoMovesTheCreatedFileToTrashAndGivesThePartsBack() throws IOException {
        AssemblyNode asm = twoPartAssembly("tower");
        assertTrue(fx.stack.execute(new SaveAsLinked(asm.id(), null)).isEmpty());
        assertTrue(fx.fs.exists("Projects/Metro/files/assemblies/tower.lasm"));
        assertTrue(fx.stack.undo());
        assertFalse(fx.fs.exists("Projects/Metro/files/assemblies/tower.lasm"));
        assertTrue(fx.fs.allFiles().stream().anyMatch(f -> f.contains(".trash/") && f.endsWith("tower.lasm")), "the file is in the trash, not deleted");
        assertFalse(asm.isLinked());
        assertEquals(2, asm.children().size());
    }

    @Test
    void aSecondAssemblyWithTheSameNameGetsAFreeFileName() {
        AssemblyNode a = twoPartAssembly("tower");
        AssemblyNode b = twoPartAssembly("tower");
        SaveAsLinked c1 = new SaveAsLinked(a.id(), null), c2 = new SaveAsLinked(b.id(), null);
        assertTrue(fx.stack.execute(c1).isEmpty());
        assertTrue(fx.stack.execute(c2).isEmpty());
        assertEquals("files/assemblies/tower.lasm", c1.lasmPath());
        assertEquals("files/assemblies/tower 2.lasm", c2.lasmPath());
    }

    @Test
    void aFileOutsideTheProjectIsCopiedNextToTheLasm() throws IOException {
        AssemblyNode asm = fx.asm(fx.p, "tower", 0, 0, 0);
        ObjectNode o = new ObjectNode("out", "places/out.litematic");   // not in the project, but in the schematics folder
        asm.addChild(o);
        fx.fs.put("places/out.litematic", "OUT");
        SaveAsLinked cmd = new SaveAsLinked(asm.id(), "Tower Set");
        fx.roundTrip(cmd);
        assertEquals("files/assemblies/Tower Set.lasm", cmd.lasmPath());
        AssemblyIo.Lasm l = AssemblyIo.fromJson(fx.fs.readString("Projects/Metro/files/assemblies/Tower Set.lasm"));
        String file = ((ObjectNode) l.nodes.get(0)).file();
        assertEquals("files/assemblies/Tower Set_files/out.litematic", file);
        assertEquals("OUT", new String(fx.fs.bytes("Projects/Metro/" + file)));
        assertTrue(fx.fs.exists("places/out.litematic"), "the original stays");
    }

    @Test
    void saveAsLinkedIsRefusedForLinkedEmptyAndLooseAssemblies() {
        AssemblyNode linked = fx.asm(fx.p, "l", 0, 0, 0);
        linked.setSource(new AssemblyNode.Link("files/x.lasm"));
        assertNotNull(fx.refusal(new SaveAsLinked(linked.id(), null)));
        AssemblyNode empty = fx.asm(fx.p, "e", 0, 0, 0);
        assertNotNull(fx.refusal(new SaveAsLinked(empty.id(), null)));
        AssemblyNode loose = fx.asm(fx.scene.loose(), "loose", 0, 0, 0);
        fx.obj(loose, "q", 0, 0, 0);
        assertNotNull(fx.refusal(new SaveAsLinked(loose.id(), null)));
    }

    @Test
    void theSelectionBecomesALinkedAssemblyInOneUndoStep() {
        ObjectNode a = fx.obj(fx.p, "a", 10, 0, 0), b = fx.obj(fx.p, "b", 20, 0, 0);
        CreateAssembly create = new CreateAssembly(Fx.ids(a, b), "Pair", null);
        SaveAsLinked save = new SaveAsLinked(create.assemblyId(), null);
        String before = fx.state();
        fx.stack.begin("Create linked assembly");
        assertTrue(fx.stack.execute(create).isEmpty());
        assertTrue(fx.stack.execute(save).isEmpty());
        fx.stack.commit();
        AssemblyNode asm = (AssemblyNode) fx.scene.byId(create.assemblyId());
        assertTrue(asm.isLinked());
        assertTrue(fx.fs.exists("Projects/Metro/files/assemblies/Pair.lasm"));
        assertTrue(fx.stack.undo());
        assertEquals(before, fx.state(), "one undo undoes both");
        assertTrue(fx.stack.redo());
        assertTrue(fx.fs.exists("Projects/Metro/files/assemblies/Pair.lasm"));
    }

    // ---- Make local, Break up for linked assemblies ----

    private void writeLasm(String path, String... partFiles) throws IOException {
        AssemblyIo.Lasm l = new AssemblyIo.Lasm("x");
        int x = 0;
        for (String f : partFiles) {
            ObjectNode o = new ObjectNode("part", f);
            o.setTransform(Fx.at(x, 0, 0));
            x += 5;
            l.nodes.add(o);
        }
        fx.fs.put(path, AssemblyIo.toJson(l));
    }

    private AssemblyNode linkTo(String link) {
        AssemblyNode a = fx.asm(fx.p, "inst", 0, 0, 0);
        a.setSource(new AssemblyNode.Link(link));
        return a;
    }

    @Test
    void makeLocalReadsAProjectLinkFromTheOtherProjectsFilesFolder() throws IOException {
        fx.project("Depot", "w");
        writeLasm("Projects/Depot/project.json", "files/hall.litematic");
        fx.fs.put("Projects/Depot/files/hall.litematic", "HALL");
        AssemblyNode a = linkTo("../Depot/project.json");
        fx.roundTrip(new MakeLocal(a.id()));
        assertFalse(a.isLinked());
        assertEquals(1, a.children().size());
        String file = ((ObjectNode) a.children().get(0)).file();
        assertEquals("HALL", new String(fx.fs.bytes("Projects/Metro/" + file)));
        assertTrue(fx.fs.exists("Projects/Depot/files/hall.litematic"), "the source project is untouched");
    }

    @Test
    void makeLocalReadsALasmFromTheSchematicsFolder() throws IOException {
        writeLasm("places/shared.lasm", "hall.litematic");
        fx.fs.put("places/hall.litematic", "HALL");
        AssemblyNode a = linkTo("../../places/shared.lasm");
        fx.roundTrip(new MakeLocal(a.id()));
        assertEquals(1, a.children().size());
        assertEquals("HALL", new String(fx.fs.bytes("Projects/Metro/" + ((ObjectNode) a.children().get(0)).file())));
    }

    @Test
    void makeLocalPointsNestedLinksAtTheSameFilesFromTheNewProject() throws IOException {
        fx.project("Depot", "w");
        AssemblyIo.Lasm l = new AssemblyIo.Lasm("x");
        AssemblyNode nested = new AssemblyNode("nested");
        nested.setSource(new AssemblyNode.Link("files/inner.lasm"));
        l.nodes.add(nested);
        fx.fs.put("Projects/Depot/project.json", AssemblyIo.toJson(l));
        AssemblyNode a = linkTo("../Depot/project.json");
        assertTrue(fx.stack.execute(new MakeLocal(a.id())).isEmpty());
        AssemblyNode copy = (AssemblyNode) a.children().get(0);
        assertEquals("../Depot/files/inner.lasm", ((AssemblyNode.Link) copy.source()).path());
    }

    @Test
    void aLinkThatLeavesTheSchematicsFolderIsRefusedNotCrashed() {
        AssemblyNode a = linkTo("../../../../outside.lasm");
        Refusal r = fx.refusal(new MakeLocal(a.id()));
        assertNotNull(r);
        assertTrue(r.reason().contains("outside"));
    }

    @Test
    void breakingUpALinkedAssemblyIsOneTransactionThatUndoesAsOne() throws IOException {
        writeLasm("Projects/Metro/files/tower.lasm", "files/t1.litematic", "files/t2.litematic");
        fx.fs.put("Projects/Metro/files/t1.litematic", "T1");
        fx.fs.put("Projects/Metro/files/t2.litematic", "T2");
        AssemblyNode a = linkTo("files/tower.lasm");
        a.setTransform(Fx.at(50, 0, 0));
        assertNotNull(fx.refusal(new BreakUp(a.id(), null)), "a linked assembly alone is refused: make it local first");
        String before = fx.state();
        fx.stack.begin("Break up inst");
        assertTrue(fx.stack.execute(new MakeLocal(a.id())).isEmpty());
        assertTrue(fx.stack.execute(new BreakUp(a.id(), null, false)).isEmpty());
        fx.stack.commit();
        FolderNode folder = (FolderNode) fx.find("inst");
        assertEquals(2, folder.children().size());
        assertEquals(new Vec3i(50, 0, 0), fx.world(folder.children().get(0)));
        assertEquals(new Vec3i(55, 0, 0), fx.world(folder.children().get(1)));
        assertNull(fx.scene.byId(a.id()), "the assembly is gone");
        assertTrue(fx.stack.undo());
        assertEquals(before, fx.state());
    }

    // ---- Realise array of assemblies, then array again ----

    @Test
    void anAssemblyArrayRealisesIntoAFolderOfAssembliesThatCanBeArrayedAgain() {
        AssemblyNode asm = twoPartAssembly("tower");
        asm.mods().add(array(3, 1.0));
        Vec3i size = new Vec3i(10, 3, 2);   // what the editor passes: the union of the parts
        fx.roundTrip(new RealiseArray(asm.id(), size));
        FolderNode folder = (FolderNode) fx.find("tower (realised)");
        assertEquals(3, folder.children().size());
        assertTrue(folder.children().stream().allMatch(c -> c instanceof AssemblyNode));
        assertTrue(asm.mods().isEmpty(), "the array is gone from the original");
        AssemblyNode copy = (AssemblyNode) folder.children().get(2);
        assertEquals(new Vec3i(120, 0, 0), fx.world(copy), "the second copy is two steps of 10 away");
        assertEquals(2, copy.children().size());

        // a realised copy takes an array again
        assertTrue(fx.stack.execute(new ModifierEdits.AddModifier(copy.id(), array(2, 1.0), -1)).isEmpty());
        AssemblyNode edited = (AssemblyNode) fx.scene.byId(copy.id());
        assertEquals(1, edited.mods().size());
        AssemblyBounds.Env env = new AssemblyBounds.Env() {
            @Override
            public Vec3i size(ObjectNode o, Path base) {
                return new Vec3i(4, 3, 2);
            }

            @Override
            public AssemblyBounds.Linked linked(AssemblyNode a, Path base) {
                return null;
            }
        };
        AssemblyBounds.Frame f = AssemblyBounds.frame(edited, null, env);
        assertEquals(new Vec3i(10, 3, 2), f.size());
        assertEquals(1, ArrayCells.cells(AssemblyBounds.arrays(edited.mods()), f.size()).size());
        assertEquals(1, f.cells().size());
    }

    @Test
    void aLinkedAssemblyArrayRealisesIntoLinkedInstances() throws IOException {
        writeLasm("Projects/Metro/files/tower.lasm", "files/t1.litematic");
        AssemblyNode a = linkTo("files/tower.lasm");
        a.mods().add(array(2, 1.0));
        fx.roundTrip(new RealiseArray(a.id(), new Vec3i(4, 3, 2)));
        FolderNode folder = (FolderNode) fx.find("inst (realised)");
        assertEquals(2, folder.children().size());
        for (Node c : folder.children()) {
            assertTrue(((AssemblyNode) c).isLinked(), "the copies are instances of the same file");
            assertEquals("files/tower.lasm", ((AssemblyNode.Link) ((AssemblyNode) c).source()).path());
        }
        assertEquals(new Vec3i(4, 0, 0), fx.world(folder.children().get(1)));
    }

    @Test
    void realisingALocalAssemblyWithFileCopiesGivesEachInstanceItsOwnFiles() {
        AssemblyNode asm = twoPartAssembly("tower");
        fx.roundTrip(new BreakUp(asm.id(), null, true));
        FolderNode folder = (FolderNode) fx.find("tower");
        ObjectNode p1 = (ObjectNode) folder.children().get(0);
        assertNotEquals("files/p1.litematic", p1.file());
        assertTrue(fx.fs.exists("Projects/Metro/" + p1.file()));
        assertTrue(fx.fs.exists("Projects/Metro/files/p1.litematic"), "the originals stay");
    }

    private static void assertNotEquals(String a, String b) {
        org.junit.jupiter.api.Assertions.assertNotEquals(a, b);
    }
}
