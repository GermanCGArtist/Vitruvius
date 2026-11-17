package dev.vitruvius.editor.io;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vitruvius.editor.core.ArrayModifier;
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
        // E, the other mod's entry and the array of 9999 are not known to the lookup; the copy of A is also listed, as Litematica would
        return Migration1x.migrate(res("folders.json"), res("arrays.json"), List.of(F, B, A, C, D, E, A_COPY), LOOKUP);
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
    void arraysBecomeModifiersAndCopyIds() throws Exception {
        Node loose = run().loose();
        ObjectNode tower = (ObjectNode) child(child(child(loose, "Castle"), "towers"), "Tower");
        assertEquals(3, tower.mods().size());
        ArrayModifier m0 = (ArrayModifier) tower.mods().get(0);
        assertTrue(m0.enabled);
        assertEquals(3, m0.count);
        assertTrue(m0.useRelative && m0.useConstant);
        assertArrayEquals1(new double[]{1, 0, 0}, m0.relative);
        assertEquals(2, m0.constant[0]);
        ArrayModifier m1 = (ArrayModifier) tower.mods().get(1);
        assertFalse(m1.enabled);
        assertFalse(m1.useRelative);
        assertEquals(5, m1.constant[1]);
        ArrayModifier m2 = (ArrayModifier) tower.mods().get(2);   // only "count" was saved: the 1.x defaults
        assertTrue(m2.enabled && m2.useRelative && !m2.useConstant);
        assertEquals(2, m2.count);
        assertEquals(1.0, m2.relative[0]);
        // the original placement under "base", then the copies under their 1.x cell keys
        assertEquals(List.of("base", "1,0,0", "2,0,0", "0,0,1"), new ArrayList<>(tower.placementIds().keySet()));
        assertEquals(A, tower.placementIds().get("base"));
        assertEquals(A_COPY, tower.placementIds().get("1,0,0"));
    }

    private static void assertArrayEquals1(double[] a, double[] b) {
        org.junit.jupiter.api.Assertions.assertArrayEquals(a, b);
    }

    @Test
    void preSixteenCountsAndGapsAreConverted() throws Exception {
        Node loose = run().loose();
        ObjectNode legacy = (ObjectNode) child(child(loose, "Orphan"), "Legacy");
        // counts [3,1,-2], gaps [1,0,4]: x gets 3 copies stepping +1 box and +1 block, z gets 2 stepping -1 box and -4 blocks
        assertEquals(2, legacy.mods().size());
        ArrayModifier x = (ArrayModifier) legacy.mods().get(0);
        assertEquals(3, x.count);
        assertArrayEquals1(new double[]{1, 0, 0}, x.relative);
        assertTrue(x.useConstant);
        assertEquals(1, x.constant[0]);
        ArrayModifier z = (ArrayModifier) legacy.mods().get(1);
        assertEquals(2, z.count);
        assertArrayEquals1(new double[]{0, 0, -1}, z.relative);
        assertEquals(-4, z.constant[2]);
        // the old "x,y,z" keys are now one index per modifier; the unreadable key is gone
        assertEquals(Map.of("base", D,
                "1,0", "d4d4d4d4-0000-0000-0000-000000000001",
                "2,-1", "d4d4d4d4-0000-0000-0000-000000000002",
                "0,-1", "d4d4d4d4-0000-0000-0000-000000000003"), legacy.placementIds());
    }

    @Test
    void unknownPlacementsAreSkippedAndCopiesAreNotObjects() throws Exception {
        Migration1x.Result r = run();
        assertTrue(r.skipped().containsAll(List.of(E, "my-mod:entry-1", "99999999-9999-9999-9999-999999999999")), r.skipped().toString());
        assertEquals(3, r.skipped().size());
        long objects = r.loose().subtree().stream().filter(n -> n instanceof ObjectNode).count();
        assertEquals(5, objects);   // A B C D F; no object for the copy A_COPY
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
        Migration1x.Result r = Migration1x.migrate(dir.resolve("none1.json"), dir.resolve("none2.json"), List.of(B, F), LOOKUP);
        assertEquals(List.of("Wall", "Free"), r.loose().children().stream().map(Node::name).toList());
        assertTrue(r.skipped().isEmpty());
    }

    @Test
    void emptyAndBrokenFiles(@TempDir Path dir) throws Exception {
        Path empty = dir.resolve("empty.json");
        Files.writeString(empty, "");
        Path braces = dir.resolve("braces.json");
        Files.writeString(braces, "{}");
        assertTrue(Migration1x.migrate(empty, braces, List.of(), LOOKUP).loose().children().isEmpty());
        Path bad = dir.resolve("bad.json");
        Files.writeString(bad, "{ nope");
        assertThrows(FormatException.class, () -> Migration1x.migrate(bad, braces, List.of(), LOOKUP));
        Path list = dir.resolve("list.json");
        Files.writeString(list, "[1]");
        assertThrows(IOException.class, () -> Migration1x.migrate(braces, list, List.of(), LOOKUP));
    }

    @Test
    void tooShortLegacyArraysKeepNoModifiers(@TempDir Path dir) throws Exception {
        Path arrays = dir.resolve("arrays.json");
        Files.writeString(arrays, "{\"" + B + "\":{\"counts\":[2,2],\"gaps\":[0,0],\"copies\":{\"1,0,0\":\"c1\"}}}");
        Migration1x.Result r = Migration1x.migrate(dir.resolve("x.json"), arrays, List.of(B), LOOKUP);
        ObjectNode wall = (ObjectNode) r.loose().children().get(0);
        assertTrue(wall.mods().isEmpty());
        assertEquals(Map.of("base", B, "1,0,0", "c1"), wall.placementIds());
    }
}
