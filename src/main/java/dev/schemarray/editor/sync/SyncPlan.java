package dev.schemarray.editor.sync;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * What a push has to do (EDITOR-2.0 section 8), and the record of what earlier pushes did ({@code extra.syncmatica} of
 * the project). Pure.
 *
 * <p>A cell is one visible placed copy, identified by its slot. Its signature says which file (and its modification time)
 * and which transform it has; when the signature differs from the recorded one the placement on the server is replaced
 * (the old id is removed, a new one shared). Placements this project pushed before that no cell wants any more are removed.
 */
public final class SyncPlan {
    private SyncPlan() {}

    /** One cell that should be on the server: its slot, the placement name, its signature and its Litematica placement id. */
    public record Cell(UUID slot, String name, String sig, String placementId) {
        public String key() {
            return slot.toString();
        }
    }

    /** What an earlier push recorded for one cell: the Syncmatica placement id and the signature it was pushed with. */
    public record Pushed(String id, String sig) {}

    /** The manifest an earlier push recorded: its Syncmatica id, the signature of its content and the Litematica placement id. */
    public record Manifest(String id, String sig, String placement) {}

    /**
     * @param share   the cells to share now (new or changed)
     * @param remove  Syncmatica ids to remove (cells that are gone or changed)
     * @param keep    the unchanged cells with what was recorded, by key
     * @param changes the number of cells that are new, changed or gone
     * @param replaced cell key to the old Syncmatica id of a changed cell (removed once its replacement is shared)
     * @param gone    Syncmatica ids of cells no cell wants any more
     */
    public record Plan(List<Cell> share, List<String> remove, Map<String, Pushed> keep, int changes, Map<String, String> replaced, List<String> gone) {}

    public static Plan diff(List<Cell> desired, Map<String, Pushed> recorded) {
        List<Cell> share = new ArrayList<>();
        List<String> remove = new ArrayList<>();
        Map<String, Pushed> keep = new LinkedHashMap<>();
        Map<String, String> replaced = new LinkedHashMap<>();
        List<String> gone = new ArrayList<>();
        Map<String, Cell> want = new LinkedHashMap<>();
        for (Cell c : desired) want.put(c.key(), c);
        int changes = 0;
        for (Cell c : want.values()) {
            Pushed p = recorded.get(c.key());
            if (p == null) {
                share.add(c);
                changes++;
            } else if (!p.sig().equals(c.sig())) {
                share.add(c);
                remove.add(p.id());
                replaced.put(c.key(), p.id());
                changes++;
            } else {
                keep.put(c.key(), p);
            }
        }
        for (var e : recorded.entrySet()) {
            if (!want.containsKey(e.getKey())) {
                remove.add(e.getValue().id());
                gone.add(e.getValue().id());
                changes++;
            }
        }
        return new Plan(share, remove, keep, changes, replaced, gone);
    }

    /**
     * The number the toolbar shows: the cells to push plus one when the manifest has to be pushed (it has never been,
     * its content changed, or some cell changes).
     */
    public static int pending(Plan plan, String manifestSig, Manifest recorded) {
        boolean manifest = recorded == null || !recorded.sig().equals(manifestSig) || plan.changes() > 0;
        return plan.changes() + (manifest ? 1 : 0);
    }

    // ------------------------------------------------------------------------------------------ the record in the project

    public static final String KEY = "syncmatica";

    /** The recorded cells of a project ({@code extra.syncmatica.placements}). */
    public static Map<String, Pushed> recordedCells(JsonObject extra) {
        Map<String, Pushed> out = new LinkedHashMap<>();
        JsonObject rec = record(extra);
        if (rec == null || !rec.has("placements") || !rec.get("placements").isJsonObject()) return out;
        for (var e : rec.getAsJsonObject("placements").entrySet()) {
            if (!e.getValue().isJsonObject()) continue;
            JsonObject o = e.getValue().getAsJsonObject();
            if (o.has("id") && o.has("sig")) out.put(e.getKey(), new Pushed(o.get("id").getAsString(), o.get("sig").getAsString()));
        }
        return out;
    }

    public static Manifest recordedManifest(JsonObject extra) {
        JsonObject rec = record(extra);
        if (rec == null || !rec.has("manifest") || !rec.get("manifest").isJsonObject()) return null;
        JsonObject m = rec.getAsJsonObject("manifest");
        if (!m.has("id") || !m.has("sig")) return null;
        return new Manifest(m.get("id").getAsString(), m.get("sig").getAsString(), m.has("placement") ? m.get("placement").getAsString() : "");
    }

    /** Writes the record into {@code extra} (replacing the old one). */
    public static void write(JsonObject extra, Map<String, Pushed> cells, Manifest manifest, long pushedAt) {
        JsonObject rec = new JsonObject();
        JsonObject pl = new JsonObject();
        for (var e : cells.entrySet()) {
            JsonObject o = new JsonObject();
            o.addProperty("id", e.getValue().id());
            o.addProperty("sig", e.getValue().sig());
            pl.add(e.getKey(), o);
        }
        rec.add("placements", pl);
        if (manifest != null) {
            JsonObject m = new JsonObject();
            m.addProperty("id", manifest.id());
            m.addProperty("sig", manifest.sig());
            m.addProperty("placement", manifest.placement());
            rec.add("manifest", m);
        }
        rec.addProperty("pushedAt", pushedAt);
        extra.add(KEY, rec);
    }

    private static JsonObject record(JsonObject extra) {
        JsonElement e = extra == null ? null : extra.get(KEY);
        return e != null && e.isJsonObject() ? e.getAsJsonObject() : null;
    }
}
