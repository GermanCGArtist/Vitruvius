package dev.vitruvius.editor.sync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

class ProjectMergeTest {
    private static JsonObject project(String nodes) {
        return JsonParser.parseString("{\"format\":1,\"id\":\"p\",\"name\":\"P\",\"world\":\"w\",\"dim\":\"d\",\"sync\":\"shared\",\"nodes\":" + nodes + "}")
                .getAsJsonObject();
    }

    private static String obj(String id, int x) {
        return "{\"id\":\"" + id + "\",\"type\":\"object\",\"name\":\"" + id + "\",\"pos\":[" + x + ",0,0]}";
    }

    private static String ids(JsonObject p) {
        StringBuilder sb = new StringBuilder();
        collect(p.getAsJsonArray("nodes"), sb);
        return sb.toString();
    }

    private static void collect(JsonArray a, StringBuilder sb) {
        for (var e : a) {
            JsonObject n = e.getAsJsonObject();
            sb.append(n.get("id").getAsString());
            if (n.has("children")) {
                sb.append('(');
                collect(n.getAsJsonArray("children"), sb);
                sb.append(')');
            }
            sb.append(' ');
        }
    }

    private static int posX(JsonObject p, String id) {
        for (var e : p.getAsJsonArray("nodes")) {
            JsonObject n = e.getAsJsonObject();
            if (n.get("id").getAsString().equals(id)) return n.getAsJsonArray("pos").get(0).getAsInt();
        }
        return Integer.MIN_VALUE;
    }

    @Test
    void editsOfDifferentObjectsBothSurvive() {
        JsonObject base = project("[" + obj("a", 0) + "," + obj("b", 0) + "]");
        JsonObject ours = project("[" + obj("a", 5) + "," + obj("b", 0) + "]");
        JsonObject theirs = project("[" + obj("a", 0) + "," + obj("b", 9) + "]");
        var r = ProjectMerge.merge(base, ours, theirs);
        assertEquals(5, posX(r.project(), "a"));
        assertEquals(9, posX(r.project(), "b"));
        assertEquals(0, r.conflicts());
        assertTrue(r.changed());
    }

    @Test
    void newObjectsFromBothSidesAreKept() {
        JsonObject base = project("[" + obj("a", 0) + "]");
        JsonObject ours = project("[" + obj("a", 0) + "," + obj("x", 1) + "]");
        JsonObject theirs = project("[" + obj("a", 0) + "," + obj("y", 2) + "]");
        assertEquals("a x y ", ids(ProjectMerge.merge(base, ours, theirs).project()));
    }

    @Test
    void aRemoteDeleteIsAppliedWhenWeDidNotTouchTheNode() {
        JsonObject base = project("[" + obj("a", 0) + "," + obj("b", 0) + "]");
        JsonObject ours = project("[" + obj("a", 0) + "," + obj("b", 0) + "]");
        JsonObject theirs = project("[" + obj("a", 0) + "]");
        assertEquals("a ", ids(ProjectMerge.merge(base, ours, theirs).project()));
    }

    @Test
    void anEditBeatsADelete() {
        JsonObject base = project("[" + obj("a", 0) + "]");
        JsonObject ours = project("[" + obj("a", 7) + "]");
        JsonObject theirs = project("[]");
        var r = ProjectMerge.merge(base, ours, theirs);
        assertEquals("a ", ids(r.project()));
        assertEquals(7, posX(r.project(), "a"));
        assertEquals(1, r.conflicts());
    }

    @Test
    void bothEditingOneNodeEndsTheSameOnBothClients() {
        JsonObject base = project("[" + obj("a", 0) + "]");
        JsonObject one = project("[" + obj("a", 3) + "]");
        JsonObject two = project("[" + obj("a", 8) + "]");
        var fromOne = ProjectMerge.merge(base, one, two);
        var fromTwo = ProjectMerge.merge(base, two, one);
        assertEquals(fromOne.project(), fromTwo.project());
        assertEquals(1, fromOne.conflicts());
    }

    @Test
    void mergingWithAnUnchangedRemoteChangesNothing() {
        JsonObject base = project("[" + obj("a", 0) + "]");
        JsonObject ours = project("[" + obj("a", 4) + "]");
        var r = ProjectMerge.merge(base, ours, base);
        assertFalse(r.changed());
        assertEquals(ours, r.project());
    }

    @Test
    void childrenFollowTheirParentAndAnOrphanGoesToTheRoot() {
        String folder = "{\"id\":\"f\",\"type\":\"folder\",\"name\":\"f\",\"children\":[" + obj("a", 0) + "]}";
        JsonObject base = project("[" + folder + "]");
        // they delete the folder; we moved nothing: the folder and its child go
        JsonObject gone = project("[]");
        assertEquals("", ids(ProjectMerge.merge(base, base, gone).project()));
        // we edit the child, they delete the folder: the child is kept, at the root
        String edited = "{\"id\":\"f\",\"type\":\"folder\",\"name\":\"f\",\"children\":[" + obj("a", 6) + "]}";
        JsonObject ours = project("[" + edited + "]");
        var r = ProjectMerge.merge(base, ours, gone);
        assertEquals("a ", ids(r.project()));
    }

    @Test
    void aMoveCycleIsBroken() {
        String fx = "{\"id\":\"x\",\"type\":\"folder\",\"name\":\"x\",\"children\":[]}";
        String fy = "{\"id\":\"y\",\"type\":\"folder\",\"name\":\"y\",\"children\":[]}";
        JsonObject base = project("[" + fx + "," + fy + "]");
        JsonObject ours = project("[{\"id\":\"x\",\"type\":\"folder\",\"name\":\"x\",\"children\":[" + fy + "]}]");
        JsonObject theirs = project("[{\"id\":\"y\",\"type\":\"folder\",\"name\":\"y\",\"children\":[" + fx + "]}]");
        var r = ProjectMerge.merge(base, ours, theirs);
        String text = ids(r.project());
        assertTrue(text.contains("x") && text.contains("y"), text);
        assertEquals(text, ids(ProjectMerge.merge(base, theirs, ours).project()));
    }

    @Test
    void worldAndSyncStayOurs() {
        JsonObject base = project("[" + obj("a", 0) + "]");
        JsonObject ours = project("[" + obj("a", 0) + "]");
        JsonObject theirs = project("[" + obj("a", 1) + "]");
        theirs.addProperty("world", "other");
        theirs.addProperty("sync", "local");
        var r = ProjectMerge.merge(base, ours, theirs);
        assertEquals("w", r.project().get("world").getAsString());
        assertEquals("shared", r.project().get("sync").getAsString());
        assertEquals(1, posX(r.project(), "a"));
    }
}
