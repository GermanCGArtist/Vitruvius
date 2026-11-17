package dev.schemarray.editor.mc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.schemarray.editor.core.ArrayModifier;
import dev.schemarray.editor.core.AssemblyNode;
import dev.schemarray.editor.core.FolderNode;
import dev.schemarray.editor.core.Mirror;
import dev.schemarray.editor.core.ObjectNode;
import dev.schemarray.editor.core.ProjectNode;
import dev.schemarray.editor.core.Rot;
import dev.schemarray.editor.core.Scene;
import dev.schemarray.editor.core.Transform;
import dev.schemarray.editor.core.Vec3i;
import dev.schemarray.editor.io.AssemblyIo;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The planning logic of the reconciler, against a fake placement list (no Minecraft). */
class ReconcileCoreTest {
    @TempDir
    Path dir;

    final FakePort port = new FakePort();
    final Vec3i size = new Vec3i(4, 3, 2);
    final List<ExternalChange> changes = new ArrayList<>();
    ReconcileCore core;
    Scene scene;
    ProjectNode proj;
    int tick;

    @BeforeEach
    void setUp() {
        core = new ReconcileCore(port, f -> size, new LasmCache(), dir);
        core.setListener(new DriftListener() {
            @Override
            public void onExternalChange(ExternalChange c) {
                changes.add(c);
            }
        });
        scene = new Scene();
        proj = new ProjectNode("P");
        proj.setPath(dir.toString());
        proj.setWorld("w");
        proj.setDim("d");
        scene.openProject(proj);
    }

    ReconcileCore.Result run() {
        return core.reconcile(scene, "w", "d", ++tick);
    }

    static ObjectNode obj(String name) {
        return new ObjectNode(name, "files/" + name + ".litematic");
    }

    static Transform at(int x, int y, int z) {
        return new Transform(new Vec3i(x, y, z), Rot.NONE, Mirror.NONE);
    }

    static ArrayModifier array(int count, double rx) {
        ArrayModifier m = new ArrayModifier();
        m.count = count;
        m.relative = new double[]{rx, 0, 0};
        return m;
    }

    Set<Vec3i> origins() {
        Set<Vec3i> out = new HashSet<>();
        for (PlacementState s : port.placements.values()) out.add(s.origin());
        return out;
    }

    // ------------------------------------------------------------------------------------------ cells

    @Test
    void baseAndArrayCellsGetPlacementsWithTheOldTransformRule() {
        ObjectNode o = obj("a");
        o.setTransform(new Transform(new Vec3i(10, 0, 0), Rot.CW_90, Mirror.NONE));
        o.mods().add(array(3, 1));   // step = 1 * size.x = 4 along the object's own x
        proj.addChild(o);
        ReconcileCore.Result r = run();
        assertEquals(3, r.created());
        assertEquals(3, port.placements.size());
        assertEquals(Set.of(new Vec3i(10, 0, 0), new Vec3i(10, 0, 4), new Vec3i(10, 0, 8)), origins());   // CW_90: (x,y,z) -> (-z,y,x)
        for (PlacementState s : port.placements.values()) {
            assertEquals(Rot.CW_90, s.rot());
            assertEquals(Mirror.NONE, s.mirror());
        }
        assertEquals(Set.of(ObjectNode.BASE_CELL, "1", "2"), o.placementIds().keySet());
        PlacementState base = port.read(o.placementIds().get(ObjectNode.BASE_CELL));
        assertEquals("a", base.name());
        assertEquals(dir.resolve("files").resolve("a.litematic"), base.file());
        assertEquals("a #1", port.read(o.placementIds().get("1")).name());
    }

    @Test
    void mirroredCellsFollowMirrorFirstThenTurn() {
        ObjectNode o = obj("m");
        o.setTransform(new Transform(Vec3i.ZERO, Rot.NONE, Mirror.FRONT_BACK));   // x -> -x
        o.mods().add(array(2, 1));
        proj.addChild(o);
        run();
        assertEquals(Set.of(Vec3i.ZERO, new Vec3i(-4, 0, 0)), origins());
    }

    @Test
    void secondPassChangesNothing() {
        ObjectNode o = obj("a");
        o.mods().add(array(3, 1));
        proj.addChild(o);
        run();
        ReconcileCore.Result r = run();
        assertEquals(0, r.created() + r.updated() + r.removed() + r.adopted() + r.deferred());
    }

    @Test
    void budgetLimitsCreationsPerTick() {
        ObjectNode o = obj("big");
        o.mods().add(array(101, 1));   // base + 100 copies
        proj.addChild(o);
        ReconcileCore.Result r1 = run();
        assertEquals(64, r1.created());
        assertEquals(37, r1.deferred());
        ReconcileCore.Result r2 = run();
        assertEquals(37, r2.created());
        assertEquals(0, r2.deferred());
        assertEquals(101, port.placements.size());
        assertEquals(101, new HashSet<>(o.placementIds().values()).size());
    }

    @Test
    void movingTheModelMovesThePlacementsAndKeepsTheirIds() {
        ObjectNode o = obj("a");
        o.mods().add(array(2, 1));
        proj.addChild(o);
        run();
        var ids = new java.util.LinkedHashMap<>(o.placementIds());
        o.setTransform(at(5, 6, 7));
        ReconcileCore.Result r = run();
        assertEquals(0, r.created());
        assertEquals(2, r.updated());
        assertEquals(ids, o.placementIds());
        assertEquals(Set.of(new Vec3i(5, 6, 7), new Vec3i(9, 6, 7)), origins());
        assertTrue(changes.isEmpty(), "an editor move is not drift");
    }

    @Test
    void shrinkingTheArrayRemovesTheSurplusPlacements() {
        ObjectNode o = obj("a");
        ArrayModifier m = array(4, 1);
        o.mods().add(m);
        proj.addChild(o);
        run();
        assertEquals(4, port.placements.size());
        m.count = 2;
        ReconcileCore.Result r = run();
        assertEquals(2, r.removed());
        assertEquals(2, port.placements.size());
        assertEquals(Set.of(ObjectNode.BASE_CELL, "1"), o.placementIds().keySet());
        o.mods().clear();
        run();
        assertEquals(1, port.placements.size());
    }

    // ------------------------------------------------------------------------------------------ visibility, worlds, ownership

    @Test
    void hiddenNodesAndNodesUnderHiddenAncestorsLoseTheirPlacements() {
        FolderNode f = new FolderNode("f");
        ObjectNode a = obj("a"), b = obj("b");
        f.addChild(a);
        proj.addChild(f);
        proj.addChild(b);
        run();
        assertEquals(2, port.placements.size());
        String idA = a.placementIds().get(ObjectNode.BASE_CELL);

        f.setHidden(true);
        run();
        assertEquals(1, port.placements.size());
        assertFalse(port.placements.containsKey(idA));

        f.setHidden(false);
        run();
        assertEquals(2, port.placements.size());
        assertTrue(port.placements.containsKey(idA), "the same id comes back");

        proj.setHidden(true);
        run();
        assertTrue(port.placements.isEmpty());
    }

    @Test
    void aProjectOfAnotherWorldOrDimensionHasNoPlacements() {
        proj.addChild(obj("a"));
        run();
        assertEquals(1, port.placements.size());
        core.reconcile(scene, "w", "other:dim", ++tick);
        assertTrue(port.placements.isEmpty());
        core.reconcile(scene, "w", "d", ++tick);
        assertEquals(1, port.placements.size());
        core.reconcile(scene, "elsewhere", "d", ++tick);
        assertTrue(port.placements.isEmpty());
    }

    @Test
    void unmanagedPlacementsAreNeverTouched() {
        String foreign = port.addForeign(dir.resolve("x.litematic"), new Vec3i(1, 2, 3));
        ObjectNode o = obj("a");
        proj.addChild(o);
        run();
        proj.removeChild(o);
        run();
        run();
        assertEquals(Set.of(foreign), port.placements.keySet());
        assertEquals(new Vec3i(1, 2, 3), port.read(foreign).origin());
        assertEquals(0, port.updated);
    }

    @Test
    void aNodeThatLeavesTheModelTakesItsPlacementsAway() {
        ObjectNode o = obj("a");
        o.mods().add(array(3, 1));
        proj.addChild(o);
        run();
        assertEquals(3, port.placements.size());
        proj.removeChild(o);
        run();
        assertTrue(port.placements.isEmpty());
    }

    @Test
    void releaseRemovesAProjectsPlacements() {
        ObjectNode o = obj("a");
        proj.addChild(o);
        run();
        core.release(proj);
        assertTrue(port.placements.isEmpty());
    }

    @Test
    void aPlacementOfAnotherFileIsReplacedKeepingItsId() {
        ObjectNode o = obj("a");
        proj.addChild(o);
        run();
        String id = o.placementIds().get(ObjectNode.BASE_CELL);
        port.placements.put(id, new PlacementState(dir.resolve("other.litematic"), Vec3i.ZERO, Rot.NONE, Mirror.NONE, true, true, "a"));
        run();
        assertEquals(2, port.created);
        assertEquals(dir.resolve("files").resolve("a.litematic"), port.read(id).file());
    }

    @Test
    void anObjectWithAnUnreadableFileIsLeftAlone() {
        core = new ReconcileCore(port, f -> null, new LasmCache(), dir);
        ObjectNode o = obj("a");
        proj.addChild(o);
        run();
        assertTrue(port.placements.isEmpty());
    }

    @Test
    void projectObjectsCannotEscapeTheProjectFolder() {
        ObjectNode o = new ObjectNode("evil", "../outside.litematic");
        proj.addChild(o);
        run();
        assertTrue(port.placements.isEmpty());
        ObjectNode abs = new ObjectNode("abs", dir.resolve("x.litematic").toAbsolutePath().toString());
        proj.addChild(abs);
        run();
        assertTrue(port.placements.isEmpty());
    }

    @Test
    void copiesFollowTheBasePlacementsEnabledAndRenderState() {
        ObjectNode o = obj("a");
        o.mods().add(array(2, 1));
        proj.addChild(o);
        run();
        String base = o.placementIds().get(ObjectNode.BASE_CELL), copy = o.placementIds().get("1");
        PlacementState b = port.read(base);
        port.placements.put(base, new PlacementState(b.file(), b.origin(), b.rot(), b.mirror(), true, false, b.name()));   // render off in Litematica
        run();
        assertFalse(port.read(base).render(), "the base keeps what the player set");
        assertFalse(port.read(copy).render(), "the copy follows it");
    }

    @Test
    void wireObjectsAreNotRendered() {
        ObjectNode o = obj("w");
        o.setWire(true);
        proj.addChild(o);
        run();
        assertFalse(port.read(o.placementIds().get(ObjectNode.BASE_CELL)).render());
    }

    // ------------------------------------------------------------------------------------------ rebuilds

    @Test
    void rebuildsAreBatchedAfterTheCreations() {
        ObjectNode o = obj("a");
        o.mods().add(array(3, 1));
        proj.addChild(o);
        run();   // tick 1: created
        for (int i = 0; i < 19; i++) run();   // ticks 2..20
        assertTrue(port.rebuilt.isEmpty());
        run();   // tick 21
        assertEquals(3, port.rebuilt.size());
        assertEquals(new HashSet<>(o.placementIds().values()), new HashSet<>(port.rebuilt));
        run();
        assertEquals(3, port.rebuilt.size(), "once");
    }

    @Test
    void aScheduledFullRebuildMarksEverythingManaged() {
        String foreign = port.addForeign(dir.resolve("x.litematic"), Vec3i.ZERO);
        ObjectNode o = obj("a");
        proj.addChild(o);
        core.scheduleFullRebuild(30);
        for (int i = 0; i < 29; i++) run();
        port.rebuilt.clear();
        run();   // tick 30
        assertEquals(List.of(o.placementIds().get(ObjectNode.BASE_CELL)), port.rebuilt);
        assertFalse(port.rebuilt.contains(foreign));
    }

    @Test
    void subRegionStatesAreCopiedToTheCopies() {
        ObjectNode o = obj("a");
        o.mods().add(array(2, 1));
        proj.addChild(o);
        for (int i = 0; i < 5; i++) run();
        assertFalse(port.subRegionCopies.isEmpty());
        assertTrue(port.subRegionCopies.get(0).startsWith(o.placementIds().get(ObjectNode.BASE_CELL) + ">"));
    }

    // ------------------------------------------------------------------------------------------ drift

    @Test
    void aMoveOutsideTheEditorIsAdoptedIntoTheModel() {
        ObjectNode o = obj("a");
        o.setTransform(at(1, 2, 3));
        o.mods().add(array(2, 1));
        proj.addChild(o);
        run();
        String base = o.placementIds().get(ObjectNode.BASE_CELL);
        Transform before = o.transform();

        port.moveExternally(base, new Vec3i(20, 5, 20), Rot.CW_180, Mirror.NONE);
        ReconcileCore.Result r = run();
        assertEquals(1, r.adopted());
        assertEquals(new Transform(new Vec3i(20, 5, 20), Rot.CW_180, Mirror.NONE), o.transform());
        assertEquals(1, changes.size());
        assertEquals(o, changes.get(0).node());
        assertEquals(before, changes.get(0).before());
        assertEquals(o.transform(), changes.get(0).after());
        assertEquals(new Vec3i(20, 5, 20), port.read(base).origin(), "the placement is not put back");
        // the copy followed the new base: CW_180 turns the step (4,0,0) to (-4,0,0)
        assertEquals(new Vec3i(16, 5, 20), port.read(o.placementIds().get("1")).origin());

        ReconcileCore.Result again = run();
        assertEquals(0, again.adopted());
        assertEquals(1, changes.size());
    }

    @Test
    void aMirrorChangeIsDriftToo() {
        ObjectNode o = obj("a");
        proj.addChild(o);
        run();
        port.moveExternally(o.placementIds().get(ObjectNode.BASE_CELL), Vec3i.ZERO, Rot.NONE, Mirror.LEFT_RIGHT);
        run();
        assertEquals(1, changes.size());
        assertEquals(new Transform(Vec3i.ZERO, Rot.NONE, Mirror.LEFT_RIGHT).canonical(), o.transform());
    }

    @Test
    void aMoveInsideAnAssemblyBecomesALocalTransform() {
        AssemblyNode asm = new AssemblyNode("asm");
        asm.setTransform(new Transform(new Vec3i(100, 0, 0), Rot.CW_90, Mirror.NONE));
        ObjectNode o = obj("a");
        o.setTransform(at(1, 0, 0));
        asm.addChild(o);
        proj.addChild(asm);
        run();
        PlacementState s = port.read(o.placementIds().get(ObjectNode.BASE_CELL));
        assertEquals(new Vec3i(100, 0, 1), s.origin());
        assertEquals(Rot.CW_90, s.rot());

        port.moveExternally(o.placementIds().get(ObjectNode.BASE_CELL), new Vec3i(50, 0, 50), Rot.NONE, Mirror.NONE);
        run();
        assertEquals(1, changes.size());
        assertEquals(new Transform(new Vec3i(50, 0, 50), Rot.NONE, Mirror.NONE), scene.worldTransform(o));
        assertEquals(new Vec3i(50, 0, 50), port.read(o.placementIds().get(ObjectNode.BASE_CELL)).origin());
    }

    @Test
    void anEditorMoveAndAnExternalMoveAreToldApart() {
        ObjectNode o = obj("a");
        proj.addChild(o);
        run();
        o.setTransform(at(7, 0, 0));
        run();
        assertTrue(changes.isEmpty());
        port.moveExternally(o.placementIds().get(ObjectNode.BASE_CELL), new Vec3i(8, 0, 0), Rot.NONE, Mirror.NONE);
        run();
        assertEquals(1, changes.size());
        o.setTransform(at(9, 0, 0));
        run();
        assertEquals(1, changes.size());
        assertEquals(new Vec3i(9, 0, 0), port.read(o.placementIds().get(ObjectNode.BASE_CELL)).origin());
    }

    @Test
    void afterAJoinTheModelWinsInAProjectAndThePlacementWinsInLoose() {
        UUID p1 = UUID.randomUUID(), p2 = UUID.randomUUID();
        Path file = dir.resolve("files").resolve("a.litematic");

        ObjectNode projectObj = obj("a");
        projectObj.setTransform(at(1, 1, 1));
        projectObj.placementIds().put(ObjectNode.BASE_CELL, p1.toString());
        proj.addChild(projectObj);
        port.placements.put(p1.toString(), new PlacementState(file, new Vec3i(9, 9, 9), Rot.NONE, Mirror.NONE, true, true, "a"));

        ObjectNode looseObj = new ObjectNode("l", dir.resolve("l.litematic").toString());
        looseObj.setTransform(at(2, 2, 2));
        looseObj.placementIds().put(ObjectNode.BASE_CELL, p2.toString());
        scene.loose().addChild(looseObj);
        port.placements.put(p2.toString(), new PlacementState(dir.resolve("l.litematic"), new Vec3i(8, 8, 8), Rot.NONE, Mirror.NONE, true, true, "l"));

        run();
        assertEquals(at(1, 1, 1), projectObj.transform());
        assertEquals(new Vec3i(1, 1, 1), port.read(p1.toString()).origin());
        assertEquals(at(8, 8, 8), looseObj.transform());
        assertEquals(new Vec3i(8, 8, 8), port.read(p2.toString()).origin());
        assertEquals(1, changes.size());
        assertEquals(looseObj, changes.get(0).node());
    }

    @Test
    void aLooseObjectWhosePlacementIsMissingIsMarkedNotDeleted() {
        ObjectNode o = new ObjectNode("l", dir.resolve("l.litematic").toString());
        o.mods().add(array(2, 1));
        scene.loose().addChild(o);
        run();
        assertEquals(2, port.placements.size());
        String base = o.placementIds().get(ObjectNode.BASE_CELL);
        var ids = new java.util.LinkedHashMap<>(o.placementIds());
        PlacementState saved = port.placements.remove(base);
        int created = port.created;
        run();
        assertEquals(List.of(o), scene.loose().children(), "never deleted automatically");
        assertTrue(o.missing());
        assertEquals(ids, o.placementIds(), "the model is not changed");
        assertEquals(created, port.created, "and not recreated");
        assertEquals(1, port.placements.size(), "the copy is left alone");
        port.placements.put(base, saved);
        run();
        assertFalse(o.missing());
        assertEquals(List.of(o), scene.loose().children());
    }

    @Test
    void aLoosePlacementThatAppearsOnlyAfter200TicksKeepsItsNode() {
        UUID id = UUID.randomUUID();
        ObjectNode o = new ObjectNode("l", dir.resolve("l.litematic").toString());
        o.setTransform(at(4, 5, 6));
        o.placementIds().put(ObjectNode.BASE_CELL, id.toString());
        scene.loose().addChild(o);
        for (int i = 0; i < 200; i++) run();   // Litematica has not loaded it yet
        assertEquals(List.of(o), scene.loose().children());
        assertTrue(o.missing());
        assertEquals(at(4, 5, 6), o.transform());
        assertEquals(id.toString(), o.placementIds().get(ObjectNode.BASE_CELL));
        assertTrue(port.placements.isEmpty());

        port.placements.put(id.toString(), new PlacementState(dir.resolve("l.litematic"), new Vec3i(4, 5, 6), Rot.NONE, Mirror.NONE, true, true, "l"));
        run();
        assertFalse(o.missing());
        assertEquals(List.of(o), scene.loose().children());
        assertEquals(1, port.placements.size());
        assertEquals(0, port.created);
        assertTrue(changes.isEmpty());
    }

    @Test
    void theEditorCodeHasNoPathThatRemovesSchematicsFromTheHolder() throws IOException {
        for (String name : List.of("LitematicaAdapter", "SchematicInfo", "Reconciler", "ReconcileCore")) {
            String src = Files.readString(Path.of("src/main/java/dev/schemarray/editor/mc/" + name + ".java"));
            String code = src.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("//[^\n]*", "");
            assertFalse(code.contains("removeSchematic("), name);
            assertFalse(code.contains("removeAllPlacementsOfSchematic"), name);
            assertFalse(code.contains("clearLoadedSchematics"), name);
        }
    }


    @Test
    void aProjectObjectWhosePlacementWasDeletedIsMadeAgain() {
        ObjectNode o = obj("a");
        proj.addChild(o);
        run();
        String id = o.placementIds().get(ObjectNode.BASE_CELL);
        port.placements.remove(id);
        run();
        assertEquals(List.of(o), proj.children());
        assertNotNull(port.read(id), "same id again");
    }

    // ------------------------------------------------------------------------------------------ linked assemblies

    private Path writeLasm(int partX) throws IOException {
        ObjectNode part = obj("part");
        part.setTransform(at(partX, 0, 0));
        AssemblyIo.Lasm l = new AssemblyIo.Lasm("tower");
        l.nodes.add(part);
        Path f = dir.resolve("files").resolve("tower.lasm");
        AssemblyIo.write(l, f);
        return f;
    }

    private AssemblyNode link(String name, Transform t) {
        AssemblyNode a = new AssemblyNode(name);
        a.setSource(new AssemblyNode.Link("files/tower.lasm"));
        a.setTransform(t);
        proj.addChild(a);
        return a;
    }

    @Test
    void linkedAssembliesGetOnePlacementPerInstanceAndPart() throws IOException {
        writeLasm(2);
        AssemblyNode a1 = link("t1", Transform.IDENTITY);
        AssemblyNode a2 = link("t2", new Transform(new Vec3i(50, 0, 0), Rot.CW_180, Mirror.NONE));
        run();
        assertEquals(2, port.placements.size(), "the same .lasm linked twice gives two sets of placements");
        assertEquals(Set.of(new Vec3i(2, 0, 0), new Vec3i(48, 0, 0)), origins());
        assertTrue(a1.children().isEmpty() && a2.children().isEmpty(), "linked parts are not put into the model");
        assertEquals(2, new HashSet<>(port.placements.keySet()).size());

        ReconcileCore.Result r = run();
        assertEquals(0, r.created() + r.updated() + r.removed());

        a1.setHidden(true);
        run();
        assertEquals(Set.of(new Vec3i(48, 0, 0)), origins());
        a1.setHidden(false);
        run();
        assertEquals(2, port.placements.size());

        proj.removeChild(a2);
        run();
        assertEquals(Set.of(new Vec3i(2, 0, 0)), origins());
    }

    @Test
    void aLinkedMoveOutsideTheEditorIsPutBack() throws IOException {
        writeLasm(2);
        link("t1", Transform.IDENTITY);
        run();
        String id = port.placements.keySet().iterator().next();
        port.moveExternally(id, new Vec3i(99, 0, 0), Rot.NONE, Mirror.NONE);
        run();
        assertTrue(changes.isEmpty());
        assertEquals(new Vec3i(2, 0, 0), port.read(id).origin());
    }

    @Test
    void theLasmIsReadAgainWhenItChanges() throws IOException {
        Path f = writeLasm(2);
        link("t1", Transform.IDENTITY);
        run();
        assertEquals(Set.of(new Vec3i(2, 0, 0)), origins());
        writeLasm(3);
        Files.setLastModifiedTime(f, FileTime.fromMillis(Files.getLastModifiedTime(f).toMillis() + 10_000));
        run();
        assertEquals(Set.of(new Vec3i(3, 0, 0)), origins());
        assertEquals(1, port.placements.size());
    }

    @Test
    void aMissingLasmGivesNoPlacementsAndNoCrash() {
        link("t1", Transform.IDENTITY);
        run();
        assertTrue(port.placements.isEmpty());
    }

    @Test
    void linkedPartsInsideAssemblyTransformsComposeFromTheRoot() throws IOException {
        writeLasm(2);
        AssemblyNode outer = new AssemblyNode("outer");
        outer.setTransform(at(0, 10, 0));
        AssemblyNode inner = new AssemblyNode("inner");
        inner.setSource(new AssemblyNode.Link("files/tower.lasm"));
        inner.setTransform(at(5, 0, 0));
        outer.addChild(inner);
        proj.addChild(outer);
        run();
        assertEquals(Set.of(new Vec3i(7, 10, 0)), origins());
        assertTrue(inner.children().isEmpty());
    }

    // ------------------------------------------------------------------------------------------ local view

    @Test
    void localViewKeepsOnlyTheListedOwnersPlacedAndComesBack() {
        ObjectNode a = obj("a"), b = obj("b");
        proj.addChild(a);
        proj.addChild(b);
        run();
        assertEquals(2, port.placements.size());
        String idA = a.placementIds().get(ObjectNode.BASE_CELL);

        core.setLocalView(Set.of(a.id()));
        run();
        assertEquals(1, port.placements.size());
        assertTrue(port.placements.containsKey(idA));
        assertFalse(b.hidden(), "local view is not saved state: the node itself is untouched");

        core.setLocalView(null);
        run();
        assertEquals(2, port.placements.size());
    }

    // ------------------------------------------------------------------------------------------ arrays on assemblies (M3)

    private AssemblyNode arrayedAssembly(int count) {
        AssemblyNode asm = new AssemblyNode("asm");
        ObjectNode p1 = obj("p1"), p2 = obj("p2");
        p2.setTransform(at(10, 0, 0));
        asm.addChild(p1);
        asm.addChild(p2);
        asm.mods().add(array(count, 1.0));
        proj.addChild(asm);
        return asm;
    }

    @Test
    void anAssemblyArrayRepeatsEveryPartForEachCellWithTheUnionOfThePartsAsSize() {
        AssemblyNode asm = arrayedAssembly(3);
        run();
        // parts span x 0..14 (p2 at 10, 4 wide), so the step is 14
        assertEquals(Set.of(new Vec3i(0, 0, 0), new Vec3i(10, 0, 0), new Vec3i(14, 0, 0), new Vec3i(24, 0, 0), new Vec3i(28, 0, 0), new Vec3i(38, 0, 0)), origins());
        assertEquals(6, port.placements.size());
        int before = port.created;
        run();
        assertEquals(before, port.created, "a second pass creates nothing");

        ((ArrayModifier) asm.mods().get(0)).count = 2;
        run();
        assertEquals(4, port.placements.size(), "the cells that are gone lose their placements");
        assertEquals(Set.of(new Vec3i(0, 0, 0), new Vec3i(10, 0, 0), new Vec3i(14, 0, 0), new Vec3i(24, 0, 0)), origins());
    }

    @Test
    void theBaseCellsKeepTheirSavedPlacementIdsAndTheCellsAreNotSaved() {
        AssemblyNode asm = arrayedAssembly(2);
        run();
        ObjectNode p1 = (ObjectNode) asm.children().get(0);
        assertEquals(Set.of(ObjectNode.BASE_CELL), p1.placementIds().keySet(), "the copies of an assembly cell are runtime only");
    }

    @Test
    void anArrayOfAnAssemblyFollowsItsTransform() {
        AssemblyNode asm = arrayedAssembly(2);
        asm.setTransform(new Transform(new Vec3i(100, 0, 0), Rot.CW_90, Mirror.NONE));
        run();
        // CW_90: (x,y,z) -> (-z,y,x); the cell offset (14,0,0) becomes (0,0,14)
        assertTrue(origins().contains(new Vec3i(100, 0, 0)));
        assertTrue(origins().contains(new Vec3i(100, 0, 14)));
        assertTrue(origins().contains(new Vec3i(100, 0, 10)));
        assertTrue(origins().contains(new Vec3i(100, 0, 24)));
        for (PlacementState s : port.placements.values()) assertEquals(Rot.CW_90, s.rot());
    }

    @Test
    void arraysNestInsideAnAssemblyArray() {
        AssemblyNode asm = new AssemblyNode("asm");
        ObjectNode part = obj("part");
        part.mods().add(array(2, 1.0));   // 4 and 8 wide with its own copy
        asm.addChild(part);
        asm.mods().add(array(2, 1.0));
        proj.addChild(asm);
        run();
        assertEquals(Set.of(new Vec3i(0, 0, 0), new Vec3i(4, 0, 0), new Vec3i(8, 0, 0), new Vec3i(12, 0, 0)), origins());
    }

    @Test
    void aHiddenAssemblyHasNoCellPlacementsAndTheyComeBack() {
        AssemblyNode asm = arrayedAssembly(2);
        run();
        assertEquals(4, port.placements.size());
        asm.setHidden(true);
        run();
        assertEquals(0, port.placements.size());
        asm.setHidden(false);
        run();
        assertEquals(4, port.placements.size());
    }

    @Test
    void aLinkedAssemblyWithAnArrayRepeatsItsParts() throws IOException {
        writeLasm(2);
        AssemblyNode a = link("t1", at(0, 0, 0));
        a.mods().add(array(2, 1.0));
        AssemblyNode b = link("t2", new Transform(new Vec3i(50, 0, 0), Rot.CW_180, Mirror.NONE));
        b.mods().add(array(2, 1.0));
        run();
        // t1: the part sits at x=2 (4 wide), the step is 4. t2 is turned: the cell offset (4,0,0) becomes (-4,0,0).
        assertEquals(Set.of(new Vec3i(2, 0, 0), new Vec3i(6, 0, 0), new Vec3i(48, 0, 0), new Vec3i(44, 0, 0)), origins());
        assertTrue(a.children().isEmpty());
    }

    @Test
    void aLinkedAssemblyWithAnArrayFollowsTheFileWhenItChanges() throws IOException {
        Path f = writeLasm(2);
        AssemblyNode a = link("t1", at(0, 0, 0));
        a.mods().add(array(2, 1.0));
        run();
        assertEquals(Set.of(new Vec3i(2, 0, 0), new Vec3i(6, 0, 0)), origins());
        writeLasm(3);
        Files.setLastModifiedTime(f, FileTime.fromMillis(System.currentTimeMillis() + 5000));
        run();
        assertEquals(Set.of(new Vec3i(3, 0, 0), new Vec3i(7, 0, 0)), origins());
    }

    @Test
    void localViewFiltersTheCellsOfAnAssemblyToo() {
        AssemblyNode asm = arrayedAssembly(2);
        ObjectNode other = obj("other");
        other.setTransform(at(0, 0, 50));
        proj.addChild(other);
        run();
        assertEquals(5, port.placements.size());
        core.setLocalView(Set.of(other.id()));
        run();
        assertEquals(1, port.placements.size(), "the assembly and its copies are hidden together");
        core.setLocalView(Set.of(asm.id()));
        run();
        assertEquals(4, port.placements.size());
    }

    @Test
    void aLinkedPartWithAnAbsentSizeIsLeftOut() throws IOException {
        core = new ReconcileCore(port, f -> f.getFileName().toString().equals("part.litematic") ? null : size, new LasmCache(), dir);
        writeLasm(2);
        AssemblyNode a = link("t1", at(0, 0, 0));
        a.mods().add(array(2, 1.0));
        run();
        assertEquals(0, port.placements.size(), "nothing has a size: no cells, and no crash");
    }

    // ------------------------------------------------------------------------------------------ runtime placements across a restart

    private ReconcileCore restarted(java.util.Map<String, java.util.Map<String, String>> saved) {
        ReconcileCore c = new ReconcileCore(port, f -> size, new LasmCache(), dir);
        c.restoreRuntime(saved);
        return c;
    }

    @Test
    void aRestartAdoptsTheSavedRuntimePlacementsWithoutDuplicatesOrRecreation() throws IOException {
        writeLasm(2);
        link("t1", at(0, 0, 0)).mods().add(array(2, 1.0));
        arrayedAssembly(3);
        run();
        int placements = port.placements.size(), created = port.created;
        assertTrue(placements > 4);
        var saved = core.runtimeIds();
        assertFalse(saved.isEmpty());

        core = restarted(saved);        // a new game: the placements are still in Litematica, the runtime map is empty
        run();
        run();
        assertEquals(placements, port.placements.size(), "no duplicates");
        assertEquals(created, port.created, "nothing is created again");
        assertEquals(0, port.removed);
    }

    @Test
    void aSavedRuntimePlacementWhoseKeyIsNotWantedAnyMoreIsRemovedAndOthersAreLeftAlone() throws IOException {
        writeLasm(2);
        AssemblyNode a = link("t1", at(0, 0, 0));
        a.mods().add(array(3, 1.0));
        run();
        int before = port.placements.size();
        var saved = core.runtimeIds();
        String foreign = port.create(dir.resolve("files/x.litematic"), new Vec3i(9, 9, 9), Rot.NONE, Mirror.NONE, "player's own", true, true, null);

        ((ArrayModifier) a.mods().get(0)).count = 2;     // the edit happened while the game was closed
        core = restarted(saved);
        run();
        assertEquals(before - 1 + 1, port.placements.size() , "one cell gone, the foreign placement untouched");
        assertTrue(port.placements.containsKey(foreign));
        assertEquals(Set.of(new Vec3i(2, 0, 0), new Vec3i(6, 0, 0), new Vec3i(9, 9, 9)), origins());
    }

    @Test
    void leftoversOfAKeyThatNoNodeUsesAreRemoved() throws IOException {
        writeLasm(2);
        AssemblyNode a = link("t1", at(0, 0, 0));
        run();
        var saved = core.runtimeIds();
        proj.removeChild(a);                              // deleted while the game was closed
        core = restarted(saved);
        run();
        assertEquals(0, port.placements.size());
    }

    // ------------------------------------------------------------------------------------------ derived files, wire cutters

    @Test
    void eachCellAsksItsOwnDerivedFileAndTheOthersKeepTheOriginal() {
        ObjectNode o = obj("a");
        o.mods().add(array(2, 1));
        proj.addChild(o);
        run();
        String baseId = o.placementIds().get(ObjectNode.BASE_CELL);
        Path derived = dir.resolve(".cache/derived/x-1.litematic");
        core.setDerived(slot -> slot.equals(o.id()) ? derived : null);   // only the base cell has a mask
        run();
        assertEquals(derived, port.read(o.placementIds().get(ObjectNode.BASE_CELL)).file());
        assertEquals(baseId, o.placementIds().get(ObjectNode.BASE_CELL), "the placement id is kept");
        assertEquals(dir.resolve("files").resolve("a.litematic"), port.read(o.placementIds().get("1")).file());
        core.setDerived(slot -> null);   // the mask is gone: back to the original
        run();
        assertEquals(dir.resolve("files").resolve("a.litematic"), port.read(o.placementIds().get(ObjectNode.BASE_CELL)).file());
    }

    @Test
    void anArrayCellSwitchesToItsDerivedFile() {
        ObjectNode o = obj("a");
        o.mods().add(array(2, 1));
        proj.addChild(o);
        run();
        Path derived = dir.resolve(".cache/derived/x-cell.litematic");
        var cell = core.cellRefs().stream().filter(c -> c.cell().equals("1")).findFirst().orElseThrow();
        core.setDerived(slot -> slot.equals(cell.slot()) ? derived : null);
        run();
        assertEquals(derived, port.read(o.placementIds().get("1")).file());
        assertEquals(dir.resolve("files").resolve("a.litematic"), port.read(o.placementIds().get(ObjectNode.BASE_CELL)).file());
    }

    @Test
    void everyVisibleCellIsListedAndWireObjectsAreNot() {
        ObjectNode o = obj("a");
        o.mods().add(array(2, 1));
        ObjectNode w = obj("c");
        w.setWire(true);
        proj.addChild(o);
        proj.addChild(w);
        run();
        assertEquals(2, core.cellRefs().size());
        assertEquals(o.id(), core.cellRefs().get(0).slot(), "the base cell's slot is the object id");
        assertTrue(core.cellRefs().stream().allMatch(c -> c.object() == o));
    }

    @Test
    void aWireObjectKeepsItsPlacementHiddenAndShowsItAgainWhenUnwired() {
        ObjectNode o = obj("c");
        proj.addChild(o);
        run();
        String id = o.placementIds().get(ObjectNode.BASE_CELL);
        assertTrue(port.read(id).render());
        o.setWire(true);
        run();
        assertFalse(port.read(id).render());
        run();
        assertFalse(port.read(id).render(), "stays hidden");
        o.setWire(false);
        run();
        assertTrue(port.read(id).render());
    }
}
