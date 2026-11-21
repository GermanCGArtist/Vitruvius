package dev.vitruvius.editor.io;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vitruvius.editor.core.FolderNode;
import dev.vitruvius.editor.core.Ids;
import dev.vitruvius.editor.core.Mirror;
import dev.vitruvius.editor.core.Node;
import dev.vitruvius.editor.core.ObjectNode;
import dev.vitruvius.editor.core.ProjectNode;
import dev.vitruvius.editor.core.Rot;
import dev.vitruvius.editor.core.Transform;
import dev.vitruvius.editor.core.Vec3i;
import dev.vitruvius.editor.io.Migration1x.PlacementInfo;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class Migration1xTest {
    static final String A = "11111111-1111-1111-1111-111111111111";
    static final String B = "22222222-2222-2222-2222-222222222222";
    static final String C = "33333333-3333-3333-3333-333333333333";
    static final String D = "44444444-4444-4444-4444-444444444444";
    static final String E = "55555555-5555-5555-5555-555555555555";
    static final String F = "66666666-6666-6666-6666-666666666666";
    static final String A_COPY = "a1a1a1a1-0000-0000-0000-000000000001";

    static Path res(String name) throws URISyntaxException {
        return Path.of(Migration1xTest.class.getResource("/migration/" + name).toURI());
    }

    static final Map<String, PlacementInfo> INFO = Map.of(
            A, new PlacementInfo("Tower", "tower.litematic", new Vec3i(10, 64, -5), Rot.CW_90, Mirror.LEFT_RIGHT),
            B, new PlacementInfo("Wall", "walls/wall.litematic", new Vec3i(0, 64, 0), Rot.NONE, Mirror.NONE),
            C, new PlacementInfo(null, "sub/keep.litematic", new Vec3i(1, 2, 3), Rot.CCW_90, Mirror.FRONT_BACK),
            D, new PlacementInfo("Legacy", "legacy.litematic", new Vec3i(-8, 70, 8), Rot.CW_180, Mirror.NONE),
            F, new PlacementInfo("Free", "free.litematic", Vec3i.ZERO, Rot.NONE, Mirror.NONE));

    static final Function<String, PlacementInfo> LOOKUP = INFO::get;

    static Migration1x.Result run() throws Exception {
        // E, the other mod's entry and the old 1.x array copy of A are not known to the lookup
        return Migration1x.migrate(res("folders.json"), List.of(F, B, A, C, D, E, A_COPY), LOOKUP);
    }

    static Node child(Node n, String name) {
        for (Node c : n.children()) if (c.name().equals(name)) return c;
        throw new AssertionError("no child " + name + " in " + n.children());
    }

    @Test
    void foldersBecomeFolderNodes() throws Exception {
        Node loose = run().loose();
        List<String> top = loose.children().stream().map(Node::name).toList();
        // child folders sorted by name like the 1.x list (case-insensitive), then the placements outside any folder
        assertEquals(List.of("Castle", "LoopA", "Orphan", "Free", "keep"), top);
        FolderNode castle = (FolderNode) child(loose, "Castle");
        assertTrue(castle.open());
        assertFalse(castle.hidden());
        assertEquals(2, castle.extra().get("color").getAsInt());
        FolderNode towers = (FolderNode) child(castle, "towers");
        assertFalse(towers.open());
        assertTrue(towers.hidden());
        assertFalse(towers.extra().has("color"));
        assertEquals(List.of("towers", "Wall"), castle.children().stream().map(Node::name).toList());
    }

    @Test
    void parentLoopsAndMissingParentsAreCut() throws Exception {
        Node loose = run().loose();
        FolderNode loopA = (FolderNode) child(loose, "LoopA");
        assertEquals("LoopB", loopA.children().get(0).name());
        assertEquals("Orphan", child(loose, "Orphan").name());   // its parent "gone" does not exist: top level
    }

    @Test
    void placementsBecomeObjectsInTheirFolder() throws Exception {
        Node loose = run().loose();
        ObjectNode tower = (ObjectNode) child(child(child(loose, "Castle"), "towers"), "Tower");
        assertEquals("tower.litematic", tower.file());
        // LEFT_RIGHT at CW_90 is stored canonical: FRONT_BACK with a half turn more
        assertEquals(new Transform(new Vec3i(10, 64, -5), Rot.CCW_90, Mirror.FRONT_BACK), tower.transform());
        assertEquals(3, tower.extra().get("color").getAsInt());
        assertEquals(0, tower.front());
        assertFalse(tower.wire());

        ObjectNode wall = (ObjectNode) child(child(loose, "Castle"), "Wall");
        assertEquals("walls/wall.litematic", wall.file());
        assertTrue(wall.mods().isEmpty());
        assertEquals(Map.of("base", B), wall.placementIds());

        // in a folder that does not exist: top level; no name given: the file name
        ObjectNode keep = (ObjectNode) child(loose, "keep");
        assertEquals("sub/keep.litematic", keep.file());
        // listed by Litematica only, in no folder
        assertEquals("Free", child(loose, "Free").name());
        assertEquals(D, ((ObjectNode) child(child(loose, "Orphan"), "Legacy")).placementIds().get("base"));
    }

    @Test
    void unknownPlacementsAreSkipped() throws Exception {
        Migration1x.Result r = run();
        assertTrue(r.skipped().containsAll(List.of(E, "my-mod:entry-1", A_COPY)), r.skipped().toString());
        assertEquals(3, r.skipped().size());
        long objects = r.loose().subtree().stream().filter(n -> n instanceof ObjectNode).count();
        assertEquals(5, objects);   // A B C D F
    }

    @Test
    void idsAreDerivedAndStable() throws Exception {
        Migration1x.Result a = run(), b = run();
        assertTrue(a.loose().structurallyEquals(b.loose()));
        ObjectNode tower = (ObjectNode) child(child(child(a.loose(), "Castle"), "towers"), "Tower");
        assertNotEquals(A, tower.id().toString(), "hash ids are never node ids");
        assertEquals(Ids.derived("la1:placement:" + A), tower.id());
        assertNotEquals(child(a.loose(), "Castle").id(), tower.id());
    }

    @Test
    void theResultSavesAndLoadsAsAProject() throws Exception {
        ProjectNode p = new ProjectNode("converted");
        for (Node n : new ArrayList<>(run().loose().children())) p.addChild(n);
        String text = ProjectIo.toJson(p);
        ProjectNode back = ProjectIo.fromJson(text);
        assertTrue(p.structurallyEquals(back));
        assertEquals(text, ProjectIo.toJson(back));
    }

    @Test
    void missingFilesGiveOnlyTheListedPlacements(@TempDir Path dir) throws Exception {
        Migration1x.Result r = Migration1x.migrate(dir.resolve("none1.json"), List.of(B, F), LOOKUP);
        assertEquals(List.of("Wall", "Free"), r.loose().children().stream().map(Node::name).toList());
        assertTrue(r.skipped().isEmpty());
    }

    @Test
    void emptyAndBrokenFiles(@TempDir Path dir) throws Exception {
        Path empty = dir.resolve("empty.json");
        Files.writeString(empty, "");
        Path braces = dir.resolve("braces.json");
        Files.writeString(braces, "{}");
        assertTrue(Migration1x.migrate(empty, List.of(), LOOKUP).loose().children().isEmpty());
        assertTrue(Migration1x.migrate(braces, List.of(), LOOKUP).loose().children().isEmpty());
        Path bad = dir.resolve("bad.json");
        Files.writeString(bad, "{ nope");
        assertThrows(FormatException.class, () -> Migration1x.migrate(bad, List.of(), LOOKUP));
        Path list = dir.resolve("list.json");
        Files.writeString(list, "[1]");
        assertThrows(IOException.class, () -> Migration1x.migrate(list, List.of(), LOOKUP));
    }

}
