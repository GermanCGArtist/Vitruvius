package dev.vitruvius.editor.sync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ShareCoreTest {
    @TempDir
    Path root;

    private static JsonObject project(String world, String... objs) {
        return JsonParser.parseString("{\"format\":1,\"id\":\"p\",\"name\":\"P\",\"world\":\"" + world + "\",\"dim\":\"d\",\"nodes\":["
                + String.join(",", objs) + "]}").getAsJsonObject();
    }

    private static String obj(String id, int x) {
        return "{\"id\":\"" + id + "\",\"type\":\"object\",\"name\":\"" + id + "\",\"pos\":[" + x + ",0,0]}";
    }

    private static int posX(JsonObject p, String id) {
        for (var e : p.getAsJsonArray("nodes")) {
            JsonObject n = e.getAsJsonObject();
            if (n.get("id").getAsString().equals(id)) return n.getAsJsonArray("pos").get(0).getAsInt();
        }
        return Integer.MIN_VALUE;
    }

    @Test
    void serverPaths() {
        assertEquals("Projects/Castle/project.vproj", ShareCore.repoPath("Castle"));
        assertEquals("Castle", ShareCore.folderOf("Projects/Castle/project.vproj"));
        assertNull(ShareCore.folderOf("Projects/Castle/files/a.litematic"));
        assertNull(ShareCore.folderOf("Projects/a/b/project.vproj"));
        assertNull(ShareCore.folderOf("Other/Castle/project.vproj"));
        Path drive = root.resolve("servers/abc/root");
        assertEquals("Projects/Castle/project.vproj", ShareCore.repoPathOf(drive, drive.resolve("Projects/Castle")));
        assertNull(ShareCore.repoPathOf(drive, root.resolve("local/Projects/Castle")), "not on this drive");
        assertNull(ShareCore.repoPathOf(drive, drive.resolve("Projects/a/b")));
    }

    @Test
    void pendingCountsChangedNodesAndIgnoresWhatStaysLocal() {
        JsonObject base = project("w", obj("a", 0), obj("b", 0));
        assertEquals(0, ShareCore.pending(base, project("other-world", obj("a", 0), obj("b", 0))), "the world is per computer");
        assertEquals(1, ShareCore.pending(base, project("w", obj("a", 3), obj("b", 0))));
        assertEquals(3, ShareCore.pending(base, project("w", obj("a", 3), obj("c", 0))), "a moved, b gone, c new");
        assertEquals(3, ShareCore.pending(null, project("w", obj("a", 0), obj("b", 0))), "nothing agreed yet: the project and both nodes");
        assertEquals(1, ShareCore.pending(base, project("w", obj("b", 0), obj("a", 0))), "a reorder alone is one change");
    }

    @Test
    void twoPlayersPushingAtOnceBothKeepTheirEdits() {
        // A and B both have version S. A moves a and pushes X; B moves b and pushes Y, also made from S.
        JsonObject s = project("w", obj("a", 0), obj("b", 0));
        JsonObject x = project("w", obj("a", 5), obj("b", 0));
        JsonObject y = project("w", obj("a", 0), obj("b", 9));
        // A gets Y. Merging against Y's parent S keeps A's move; against A's own last push X it would look like B undid it.
        ShareCore.Merge atA = ShareCore.incoming(s, x, y);
        assertEquals(5, posX(atA.project(), "a"));
        assertEquals(9, posX(atA.project(), "b"));
        assertTrue(atA.pushBack(), "the server (Y) lacks A's move: push the merge");
        assertEquals(1, atA.changed());
        // B gets the merge (made from Y) and simply takes it
        ShareCore.Merge atB = ShareCore.incoming(y, y, atA.project());
        assertFalse(atB.pushBack(), "nothing of B's is missing: no push back, the two converge");
        assertTrue(ProjectMerge.equivalent(atB.project(), atA.project()));
    }

    @Test
    void anUnchangedCopyFastForwards() {
        JsonObject s = project("mine", obj("a", 0));
        JsonObject theirs = project("theirs", obj("a", 7), obj("n", 1));
        ShareCore.Merge m = ShareCore.incoming(s, s, theirs);
        assertFalse(m.pushBack());
        assertEquals(7, posX(m.project(), "a"));
        assertEquals("mine", m.project().get("world").getAsString(), "world and dimension stay this computer's");
        assertEquals(2, m.changed());
    }

    @Test
    void theBaseFileRoundTripsWithoutLocalFields() {
        Path dir = root.resolve("Projects/P");
        ShareCore.writeBase(dir, project("w", obj("a", 1)));
        JsonObject back = ShareCore.readBase(dir);
        assertFalse(back.has("world"));
        assertEquals(1, posX(back, "a"));
        assertTrue(Files.isRegularFile(dir.resolve(".cache/share-base.json")), "hidden: not shared, not in versions");
    }

    @Test
    void aBundleIsTheProjectFileAndFilesAndWritesBack() throws IOException {
        Path dir = root.resolve("Projects/P");
        Files.createDirectories(dir.resolve("files/sub"));
        Files.createDirectories(dir.resolve(".trash"));
        Files.writeString(dir.resolve("project.vproj"), "{}");
        Files.writeString(dir.resolve("files/a.litematic"), "A");
        Files.writeString(dir.resolve("files/sub/b.lasm"), "B");
        Files.writeString(dir.resolve("files/sub/b.lasm.tmp"), "half");
        Files.writeString(dir.resolve(".trash/old.litematic"), "old");
        Map<String, byte[]> b = ShareCore.bundle(dir);
        assertEquals(List.of("project.vproj", "files/a.litematic", "files/sub/b.lasm"), List.copyOf(b.keySet()));

        Path copy = root.resolve("server/Projects/P");
        ShareCore.writeBundle(copy, b);
        assertEquals("B", Files.readString(copy.resolve("files/sub/b.lasm")));
        assertEquals(b.keySet(), ShareCore.bundle(copy).keySet());
    }
}
