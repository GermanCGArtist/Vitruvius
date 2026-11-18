package dev.vitruvius.editor.sync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import dev.vitruvius.editor.sync.SyncPlan.Manifest;
import dev.vitruvius.editor.sync.SyncPlan.Pushed;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SyncRecordTest {
    @Test
    void appliedManifestsAreRememberedAndBounded() {
        JsonObject extra = new JsonObject();
        for (int i = 0; i < 60; i++) SyncPlan.markApplied(extra, "m" + i, 1000 + i);
        var applied = SyncPlan.applied(extra);
        assertEquals(48, applied.size());
        assertTrue(applied.contains("m59"));
        assertFalse(applied.contains("m0"));
        assertEquals(1059, SyncPlan.syncedAt(extra));
    }

    @Test
    void aPushKeepsWhatLiveSharingRecorded() {
        JsonObject extra = new JsonObject();
        SyncPlan.markApplied(extra, "m1", 500);
        SyncPlan.setAuto(extra, false);
        SyncPlan.write(extra, Map.of("cell", new Pushed("id", "sig")), new Manifest("man", "msig", "pl"), 900);
        assertTrue(SyncPlan.applied(extra).contains("m1"));
        assertFalse(SyncPlan.auto(extra));
        assertEquals(900, SyncPlan.syncedAt(extra));
        assertEquals("sig", SyncPlan.recordedCells(extra).get("cell").sig());
    }

    @Test
    void autoIsOnByDefault() {
        assertTrue(SyncPlan.auto(new JsonObject()));
    }

    @Test
    void markingTheManifestSyncedKeepsItsIdAndChangesTheSignature() {
        JsonObject extra = new JsonObject();
        SyncPlan.write(extra, Map.of(), new Manifest("man", "old", "pl"), 1);
        SyncPlan.markManifestSynced(extra, "new");
        Manifest m = SyncPlan.recordedManifest(extra);
        assertEquals("man", m.id());
        assertEquals("new", m.sig());
        assertEquals("pl", m.placement());
    }

    @Test
    void theManifestCarriesTheTimeAndTheWriter() {
        JsonObject project = new JsonObject();
        project.addProperty("id", "p1");
        var payload = SyncManifest.parse(SyncManifest.payloadJson(project, Map.of("a", "b"), 1234, "Alex"));
        assertEquals(1234, payload.at());
        assertEquals("Alex", payload.by());
        assertEquals("p1", payload.projectId());
        var old = SyncManifest.parse(SyncManifest.payloadJson(project, Map.of()));
        assertEquals(0, old.at());
    }

    @Test
    void twoManifestsOfOneProjectMergeIntoOneScene() {
        // A moves a, B adds b: merging B's manifest into A (base = what both had) gives a moved and b added
        var base = JsonParserHelper.project("[" + JsonParserHelper.obj("a", 0) + "]");
        var a = JsonParserHelper.project("[" + JsonParserHelper.obj("a", 5) + "]");
        var b = JsonParserHelper.project("[" + JsonParserHelper.obj("a", 0) + "," + JsonParserHelper.obj("b", 1) + "]");
        var merged = ProjectMerge.merge(base, a, b);
        assertTrue(ProjectMerge.equivalent(merged.project(), ProjectMerge.merge(base, b, a).project()));
    }

    private static final class JsonParserHelper {
        static JsonObject project(String nodes) {
            return com.google.gson.JsonParser.parseString("{\"format\":1,\"id\":\"p\",\"name\":\"P\",\"nodes\":" + nodes + "}").getAsJsonObject();
        }

        static String obj(String id, int x) {
            return "{\"id\":\"" + id + "\",\"type\":\"object\",\"name\":\"" + id + "\",\"pos\":[" + x + ",0,0]}";
        }
    }
}
