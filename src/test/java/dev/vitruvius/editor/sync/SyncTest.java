package dev.vitruvius.editor.sync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import dev.vitruvius.editor.core.ObjectNode;
import dev.vitruvius.editor.core.ProjectNode;
import dev.vitruvius.editor.io.ProjectIo;
import dev.vitruvius.editor.sync.SyncPlan.Cell;
import dev.vitruvius.editor.sync.SyncPlan.Pushed;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SyncTest {
    // ------------------------------------------------------------------ manifest text

    @Test
    void namesCarryThePrefix() {
        assertEquals("[LA2] Metro", SyncManifest.nameFor("Metro"));
        assertTrue(SyncManifest.isManifest("[LA2] Metro"));
        assertFalse(SyncManifest.isManifest("Metro"));
        assertFalse(SyncManifest.isManifest(null));
        assertEquals("Metro line", SyncManifest.projectName("[LA2] Metro line"));
    }

    @Test
    void aSmallManifestIsPlainText() {
        String json = "{\"a\":1}";
        String d = SyncManifest.encode(json);
        assertEquals("la2:" + json, d);
        assertEquals(json, SyncManifest.decode(d));
    }

    @Test
    void aLargeManifestIsGzippedAndRoundTrips() {
        StringBuilder sb = new StringBuilder("{\"text\":\"");
        for (int i = 0; i < 40_000; i++) sb.append("abc ").append(i % 10);
        sb.append("\"}");
        String json = sb.toString();
        assertTrue(json.length() > SyncManifest.GZIP_ABOVE);
        String d = SyncManifest.encode(json);
        assertTrue(d.startsWith("la2z:"));
        assertTrue(d.length() < json.length() / 2, "repetitive text compresses");
        assertEquals(json, SyncManifest.decode(d));
        assertTrue(SyncManifest.fits(d));
    }

    @Test
    void anOversizedManifestDoesNotFit() {
        java.util.Random r = new java.util.Random(1);
        StringBuilder sb = new StringBuilder("{\"x\":\"");
        for (int i = 0; i < 200_000; i++) sb.append((char) ('a' + r.nextInt(26))).append((char) ('A' + r.nextInt(26))).append(r.nextInt(10));
        sb.append("\"}");
        assertFalse(SyncManifest.fits(SyncManifest.encode(sb.toString())), "random text barely compresses: over the NBT string limit");
    }

    @Test
    void badDescriptionsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> SyncManifest.decode("hello"));
        assertThrows(IllegalArgumentException.class, () -> SyncManifest.decode(null));
        assertThrows(IllegalArgumentException.class, () -> SyncManifest.decode("la2z:@@@not base64@@@"));
        assertThrows(IllegalArgumentException.class, () -> SyncManifest.decode("la2z:" + java.util.Base64.getEncoder().encodeToString("plain".getBytes())));
    }

    @Test
    void aProjectAndItsCellsRoundTrip() throws Exception {
        ProjectNode p = new ProjectNode("Metro");
        ObjectNode o = new ObjectNode("station", "files/station.litematic");
        p.addChild(o);
        JsonObject project = com.google.gson.JsonParser.parseString(ProjectIo.toJson(p)).getAsJsonObject();
        Map<String, String> cells = new LinkedHashMap<>();
        cells.put(o.id().toString(), UUID.randomUUID().toString());
        cells.put(UUID.randomUUID().toString(), UUID.randomUUID().toString());
        String description = SyncManifest.encode(SyncManifest.payloadJson(project, cells));
        SyncManifest.Payload back = SyncManifest.parse(SyncManifest.decode(description));
        assertEquals(cells, back.cells());
        ProjectNode p2 = ProjectIo.fromJson(back.project().toString());
        assertTrue(p.structurallyEquals(p2), "the scene graph survives");
        assertEquals("station", p2.children().get(0).name());
    }

    @Test
    void aBigProjectSurvivesTheGzipPath() throws Exception {
        ProjectNode p = new ProjectNode("Big");
        for (int i = 0; i < 700; i++) p.addChild(new ObjectNode("object number " + i, "files/object_" + i + ".litematic"));
        JsonObject project = com.google.gson.JsonParser.parseString(ProjectIo.toJson(p)).getAsJsonObject();
        String json = SyncManifest.payloadJson(project, Map.of());
        assertTrue(json.length() > SyncManifest.GZIP_ABOVE);
        String d = SyncManifest.encode(json);
        assertTrue(d.startsWith("la2z:"));
        ProjectNode back = ProjectIo.fromJson(SyncManifest.parse(SyncManifest.decode(d)).project().toString());
        assertTrue(p.structurallyEquals(back));
    }

    @Test
    void parseRefusesWhatIsNotAManifest() {
        assertThrows(IllegalArgumentException.class, () -> SyncManifest.parse("[1,2]"));
        assertThrows(IllegalArgumentException.class, () -> SyncManifest.parse("{\"v\":9,\"project\":{}}"));
        assertThrows(IllegalArgumentException.class, () -> SyncManifest.parse("{\"v\":1}"));
        assertThrows(IllegalArgumentException.class, () -> SyncManifest.parse("nope"));
    }

    // ------------------------------------------------------------------ the push plan

    private Cell cell(UUID slot, String sig) {
        return new Cell(slot, "cell", sig, "hash-" + slot);
    }

    @Test
    void nothingRecordedMeansEverythingIsShared() {
        UUID a = UUID.randomUUID(), b = UUID.randomUUID();
        SyncPlan.Plan plan = SyncPlan.diff(List.of(cell(a, "1"), cell(b, "2")), Map.of());
        assertEquals(2, plan.share().size());
        assertTrue(plan.remove().isEmpty());
        assertEquals(2, plan.changes());
        assertEquals(3, SyncPlan.pending(plan, "m", null), "two cells and the manifest");
    }

    @Test
    void anUnchangedProjectHasNothingPending() {
        UUID a = UUID.randomUUID();
        Map<String, Pushed> rec = Map.of(a.toString(), new Pushed("sync-a", "1"));
        SyncPlan.Plan plan = SyncPlan.diff(List.of(cell(a, "1")), rec);
        assertTrue(plan.share().isEmpty());
        assertEquals(1, plan.keep().size());
        assertEquals(0, SyncPlan.pending(plan, "m", new SyncPlan.Manifest("mid", "m", "p")));
    }

    @Test
    void aChangedCellIsReplacedAndItsOldIdRemoved() {
        UUID a = UUID.randomUUID();
        Map<String, Pushed> rec = Map.of(a.toString(), new Pushed("sync-a", "1"));
        SyncPlan.Plan plan = SyncPlan.diff(List.of(cell(a, "moved")), rec);
        assertEquals(1, plan.share().size());
        assertEquals(List.of("sync-a"), plan.remove());
        assertEquals(1, plan.changes(), "one change, not a removal plus a share");
        assertEquals(2, SyncPlan.pending(plan, "m", new SyncPlan.Manifest("mid", "m", "p")), "the cell and the manifest that lists its new id");
    }

    @Test
    void aGoneCellIsRemovedFromTheServer() {
        UUID a = UUID.randomUUID(), gone = UUID.randomUUID();
        Map<String, Pushed> rec = new LinkedHashMap<>();
        rec.put(a.toString(), new Pushed("sync-a", "1"));
        rec.put(gone.toString(), new Pushed("sync-gone", "9"));
        SyncPlan.Plan plan = SyncPlan.diff(List.of(cell(a, "1")), rec);
        assertTrue(plan.share().isEmpty());
        assertEquals(List.of("sync-gone"), plan.remove());
        assertEquals(1, plan.changes());
    }

    @Test
    void aChangedManifestAloneIsOnePendingChange() {
        UUID a = UUID.randomUUID();
        Map<String, Pushed> rec = Map.of(a.toString(), new Pushed("sync-a", "1"));
        SyncPlan.Plan plan = SyncPlan.diff(List.of(cell(a, "1")), rec);
        assertEquals(1, SyncPlan.pending(plan, "renamed project", new SyncPlan.Manifest("mid", "old", "p")));
    }

    @Test
    void theRecordRoundTripsThroughTheProjectExtra() {
        JsonObject extra = new JsonObject();
        assertNull(SyncPlan.recordedManifest(extra));
        assertTrue(SyncPlan.recordedCells(extra).isEmpty());
        Map<String, Pushed> cells = new LinkedHashMap<>();
        cells.put("slot1", new Pushed("id1", "sig1"));
        cells.put("slot2", new Pushed("id2", "sig2"));
        SyncPlan.write(extra, cells, new SyncPlan.Manifest("mid", "msig", "hash"), 123L);
        assertEquals(cells, SyncPlan.recordedCells(extra));
        SyncPlan.Manifest m = SyncPlan.recordedManifest(extra);
        assertNotNull(m);
        assertEquals("mid", m.id());
        assertEquals("hash", m.placement());
        // it lives under extra.syncmatica, so it saves with the project and an unaware 2.0 keeps it
        assertTrue(extra.has("syncmatica"));
    }

    @Test
    void theRecordSurvivesSavingTheProject() throws Exception {
        ProjectNode p = new ProjectNode("P");
        SyncPlan.write(p.extra(), Map.of("s", new Pushed("i", "g")), new SyncPlan.Manifest("m", "x", "h"), 1L);
        ProjectNode back = ProjectIo.fromJson(ProjectIo.toJson(p));
        assertEquals(Map.of("s", new Pushed("i", "g")), SyncPlan.recordedCells(back.extra()));
        assertEquals("m", SyncPlan.recordedManifest(back.extra()).id());
    }
}
