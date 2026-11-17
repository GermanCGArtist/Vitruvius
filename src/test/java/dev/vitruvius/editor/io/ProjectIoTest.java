package dev.vitruvius.editor.io;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.vitruvius.editor.core.ArrayModifier;
import dev.vitruvius.editor.core.AssemblyNode;
import dev.vitruvius.editor.core.BooleanModifier;
import dev.vitruvius.editor.core.ExternalNode;
import dev.vitruvius.editor.core.FolderNode;
import dev.vitruvius.editor.core.Mirror;
import dev.vitruvius.editor.core.Node;
import dev.vitruvius.editor.core.ObjectNode;
import dev.vitruvius.editor.core.ProjectNode;
import dev.vitruvius.editor.core.Rot;
import dev.vitruvius.editor.core.Transform;
import dev.vitruvius.editor.core.Vec3i;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProjectIoTest {
    static UUID id(int n) {
        return new UUID(0, n);
    }

    /** A project with every kind of node, modifier and field. */
    static ProjectNode sample() {
        ProjectNode p = new ProjectNode(id(1), "Castle äö \"q\" <x>");
        p.setWorld("My World");
        p.setDim("minecraft:the_nether");
        p.setOrigin(new Vec3i(-100, 64, 2000));
        p.setSync(ProjectNode.Sync.SHARED);

        FolderNode walls = new FolderNode(id(2), "Walls");
        walls.setOpen(false);
        walls.setHidden(true);
        ObjectNode wall = new ObjectNode(id(3), "wall");
        wall.setFile("files/walls/wall.litematic");
        wall.setTransform(new Transform(new Vec3i(5, 0, -7), Rot.CCW_90, Mirror.FRONT_BACK));
        wall.setFront(2);
        ArrayModifier am = new ArrayModifier();
        am.count = 4;
        am.relative = new double[]{1, 0, 0.5};
        am.useConstant = true;
        am.constant = new int[]{2, 0, -1};
        ArrayModifier am2 = new ArrayModifier();
        am2.enabled = false;
        am2.useRelative = false;
        wall.mods().add(am);
        wall.mods().add(am2);
        BooleanModifier bm = new BooleanModifier(BooleanModifier.Op.INTERSECT, id(9));
        bm.enabled = false;
        wall.mods().add(bm);
        wall.mods().add(new BooleanModifier());   // no target yet
        wall.placementIds().put("base", "aaaa");
        wall.placementIds().put("1,0", "bbbb");
        wall.setWire(true);
        wall.setHidden(true);
        walls.addChild(wall);

        AssemblyNode gate = new AssemblyNode(id(4), "gate");
        gate.setTransform(new Transform(new Vec3i(0, 1, 0), Rot.CW_180, Mirror.NONE));
        gate.setFront(1);
        gate.mods().add(new ArrayModifier());
        ObjectNode door = new ObjectNode(id(5), "door");
        door.setFile("files/door.litematic");
        gate.addChild(door);
        AssemblyNode sub = new AssemblyNode(id(6), "sub");
        sub.addChild(new ObjectNode(id(7), "pin"));
        gate.addChild(sub);

        AssemblyNode linked = new AssemblyNode(id(8), "linked tower");
        linked.setSource(new AssemblyNode.Link("files/assemblies/tower.lasm"));
        linked.addChild(new ObjectNode(id(20), "never saved"));   // comes from the .lasm at load time

        JsonObject data = JsonParser.parseString("{\"min\":[0,0,0],\"max\":[16.5,8,16],\"color\":\"#ff00ff\",\"nested\":{\"a\":[1,2,{\"b\":null}]},\"big\":12345678901,\"exp\":1.5e3}").getAsJsonObject();
        ExternalNode area = new ExternalNode(id(10), "area", "heavymatica:area");
        area.setTransform(new Transform(new Vec3i(1, 2, 3), Rot.CW_90, Mirror.NONE));
        area.setData(data);

        p.addChild(walls);
        p.addChild(gate);
        p.addChild(linked);
        p.addChild(area);
        p.addChild(new ObjectNode(id(11), "plain"));
        return p;
    }

    /** What sample() looks like after saving and loading: the linked assembly has lost its parts. */
    static ProjectNode sampleAfterSave() {
        ProjectNode p = sample();
        for (Node n : p.children()) if (n instanceof AssemblyNode a && a.isLinked()) a.clearChildren();
        return p;
    }

    @Test
    void stringRoundTripIsEqualAndStable() throws IOException {
        ProjectNode p = sample();
        String text = ProjectIo.toJson(p);
        ProjectNode back = ProjectIo.fromJson(text);
        assertTrue(sampleAfterSave().structurallyEquals(back));
        assertEquals(text, ProjectIo.toJson(back), "second write is byte-identical");
        assertEquals(text, ProjectIo.toJson(ProjectIo.fromJson(ProjectIo.toJson(back))));
        assertTrue(text.endsWith("}\n"));
        assertEquals(text, ProjectIo.toJson(p), "writing twice gives the same bytes");
    }

    @Test
    void fileRoundTripIsByteIdentical(@TempDir Path dir) throws IOException {
        Path f = dir.resolve("Projects").resolve("Castle").resolve("project.json");
        ProjectIo.write(sample(), f);
        byte[] first = Files.readAllBytes(f);
        ProjectNode back = ProjectIo.read(f);
        assertEquals(f.toAbsolutePath().getParent().toString(), back.path());
        assertTrue(sampleAfterSave().structurallyEquals(back));
        Path g = dir.resolve("copy.json");
        ProjectIo.write(back, g);
        assertEquals(new String(first, StandardCharsets.UTF_8), Files.readString(g));
        assertTrue(java.util.Arrays.equals(first, Files.readAllBytes(g)));
        try (var s = Files.list(f.getParent())) {
            assertEquals(1, s.count(), "no temp file left behind");
        }
    }

    @Test
    void goldenFormat() throws IOException {
        ProjectNode p = new ProjectNode(id(1), "demo");
        p.setWorld("My World");
        p.setOrigin(new Vec3i(0, 64, 0));
        ObjectNode o = new ObjectNode(id(2), "tower");
        o.setFile("files/tower.litematic");
        o.setTransform(new Transform(new Vec3i(1, 2, 3), Rot.CW_90, Mirror.NONE));
        o.setFront(1);
        p.addChild(o);
        String expected = """
                {
                  "format": 1,
                  "id": "00000000-0000-0000-0000-000000000001",
                  "name": "demo",
                  "world": "My World",
                  "dim": "minecraft:overworld",
                  "origin": [0, 64, 0],
                  "sync": "local",
                  "hidden": false,
                  "nodes": [
                    {
                      "id": "00000000-0000-0000-0000-000000000002",
                      "type": "object",
                      "name": "tower",
                      "hidden": false,
                      "file": "files/tower.litematic",
                      "transform": {
                        "pos": [1, 2, 3],
                        "rot": 1,
                        "mirror": "none"
                      },
                      "front": 1,
                      "wire": false,
                      "mods": [],
                      "placementIds": {}
                    }
                  ]
                }
                """;
        assertEquals(expected, ProjectIo.toJson(p));
        assertTrue(expected.equals(ProjectIo.toJson(ProjectIo.fromJson(expected))));
    }

    @Test
    void linkedAssemblyChildrenAreNotSaved() throws IOException {
        String text = ProjectIo.toJson(sample());
        assertFalse(text.contains("never saved"));
        assertTrue(text.contains("files/assemblies/tower.lasm"));
        ProjectNode back = ProjectIo.fromJson(text);
        AssemblyNode a = (AssemblyNode) back.children().get(2);
        assertTrue(a.isLinked());
        assertEquals(new AssemblyNode.Link("files/assemblies/tower.lasm"), a.source());
        assertTrue(a.children().isEmpty());
    }

    @Test
    void externalDataStaysRaw() throws IOException {
        ProjectNode back = ProjectIo.fromJson(ProjectIo.toJson(sample()));
        ExternalNode x = (ExternalNode) back.children().get(3);
        assertEquals("heavymatica:area", x.typeId());
        String again = ProjectIo.toJson(back);
        assertTrue(again.contains("12345678901"));
        assertTrue(again.contains("1.5e3"), "number text is kept as read");
        assertTrue(again.contains("16.5"));
        assertEquals(sample().children().get(3).structurallyEquals(x), true);
    }

    @Test
    void unknownFieldsSurviveEverywhere() throws IOException {
        String text = """
                {
                  "format": 1,
                  "id": "00000000-0000-0000-0000-000000000001",
                  "name": "demo",
                  "futureProjectField": { "a": [1, 2] },
                  "nodes": [
                    { "id": "00000000-0000-0000-0000-000000000002", "type": "folder", "name": "f", "tag": "red",
                      "children": [
                        { "id": "00000000-0000-0000-0000-000000000003", "type": "object", "name": "o", "file": "x.litematic", "lockedBy": "bob" }
                      ] },
                    { "id": "00000000-0000-0000-0000-000000000004", "type": "assembly", "name": "a", "notes": "hi" },
                    { "id": "00000000-0000-0000-0000-000000000005", "type": "external", "typeId": "t:x", "extraThing": 5 }
                  ]
                }
                """;
        ProjectNode p = ProjectIo.fromJson(text);
        assertEquals(1, p.extra().size());
        assertEquals("red", p.children().get(0).extra().get("tag").getAsString());
        assertEquals("bob", p.children().get(0).children().get(0).extra().get("lockedBy").getAsString());
        String out = ProjectIo.toJson(p);
        JsonObject o = JsonParser.parseString(out).getAsJsonObject();
        assertTrue(o.has("futureProjectField"));
        assertEquals(5, o.getAsJsonArray("nodes").get(2).getAsJsonObject().get("extraThing").getAsInt());
        assertEquals("hi", o.getAsJsonArray("nodes").get(1).getAsJsonObject().get("notes").getAsString());
        assertTrue(p.structurallyEquals(ProjectIo.fromJson(out)));
        assertEquals(out, ProjectIo.toJson(ProjectIo.fromJson(out)));
    }

    @Test
    void missingFieldsGetDefaults() throws IOException {
        ProjectNode p = ProjectIo.fromJson("{\"nodes\":[{\"type\":\"object\"},{\"type\":\"folder\"}]}");
        assertEquals("minecraft:overworld", p.dim());
        assertEquals(Vec3i.ZERO, p.origin());
        ObjectNode o = (ObjectNode) p.children().get(0);
        assertEquals(Transform.IDENTITY, o.transform());
        assertEquals(0, o.front());
        assertTrue(((FolderNode) p.children().get(1)).open());
        assertFalse(p.children().get(0).id().equals(p.children().get(1).id()), "missing ids are generated");
    }

    @Test
    void newerFormatIsRefused() {
        FormatException e = assertThrows(FormatException.class, () -> ProjectIo.fromJson("{\"format\": 2, \"nodes\": []}"));
        assertTrue(e.getMessage().contains("format 2"));
    }

    @Test
    void badFilesGiveAFormatException() {
        assertThrows(FormatException.class, () -> ProjectIo.fromJson("{ not json"));
        assertThrows(FormatException.class, () -> ProjectIo.fromJson("[]"));
        assertThrows(FormatException.class, () -> ProjectIo.fromJson("{\"nodes\":[{\"type\":\"banana\"}]}"));
        assertThrows(FormatException.class, () -> ProjectIo.fromJson("{\"nodes\":[{\"type\":\"object\",\"transform\":{\"rot\":7}}]}"));
        assertThrows(FormatException.class, () -> ProjectIo.fromJson("{\"nodes\":[{\"type\":\"object\",\"transform\":{\"mirror\":\"sideways\"}}]}"));
        assertThrows(FormatException.class, () -> ProjectIo.fromJson("{\"nodes\":[{\"type\":\"object\",\"mods\":[{\"type\":\"wobble\"}]}]}"));
        assertThrows(FormatException.class, () -> ProjectIo.fromJson("{\"nodes\":[{\"type\":\"object\",\"id\":\"nope\"}]}"));
        assertThrows(FormatException.class, () -> ProjectIo.fromJson("{\"nodes\":[{\"type\":\"external\"}]}"));
        assertThrows(FormatException.class, () -> ProjectIo.fromJson("{\"nodes\":[{\"type\":\"object\",\"file\":5.5}]}".replace("5.5", "{}")));
    }

    @Test
    void repeatedIdsAreRefused() {
        String a = "00000000-0000-0000-0000-00000000000a";
        assertThrows(FormatException.class, () -> ProjectIo.fromJson(
                "{\"nodes\":[{\"type\":\"object\",\"id\":\"" + a + "\"},{\"type\":\"folder\",\"id\":\"" + a + "\"}]}"));
        assertThrows(FormatException.class, () -> ProjectIo.fromJson(
                "{\"id\":\"" + a + "\",\"nodes\":[{\"type\":\"object\",\"id\":\"" + a + "\"}]}"));
    }

    @Test
    void readingAMissingFileThrowsIoException(@TempDir Path dir) {
        assertThrows(IOException.class, () -> ProjectIo.read(dir.resolve("nope.json")));
    }

    @Test
    void leftRightMirrorSurvivesAsWritten() throws IOException {
        ProjectNode p = new ProjectNode(id(1), "p");
        ObjectNode o = new ObjectNode(id(2), "o");
        o.setTransform(new Transform(Vec3i.ZERO, Rot.NONE, Mirror.LEFT_RIGHT));
        p.addChild(o);
        ObjectNode back = (ObjectNode) ProjectIo.fromJson(ProjectIo.toJson(p)).children().get(0);
        assertEquals(Mirror.LEFT_RIGHT, back.transform().mirror());
    }
}
