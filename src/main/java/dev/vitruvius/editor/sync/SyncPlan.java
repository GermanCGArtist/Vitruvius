package dev.vitruvius.editor.sync;

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

    /** Writes the record into {@code extra} (replacing the old one, but keeping what live sharing recorded). */
    public static void write(JsonObject extra, Map<String, Pushed> cells, Manifest manifest, long pushedAt) {
        JsonObject old = record(extra);
        JsonObject rec = new JsonObject();
        if (old != null) for (String k : new String[]{"applied", "syncedAt", "auto"}) if (old.has(k)) rec.add(k, old.get(k).deepCopy());
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

    // ------------------------------------------------------------------------------------------ live sharing record

    private static final int KEEP_APPLIED = 48;

    /** Ids of the other players' manifests this project has merged (or decided to ignore). */
    public static java.util.Set<String> applied(JsonObject extra) {
        java.util.Set<String> out = new java.util.LinkedHashSet<>();
        JsonObject rec = record(extra);
        if (rec != null && rec.has("applied") && rec.get("applied").isJsonArray()) {
            for (JsonElement e : rec.getAsJsonArray("applied")) if (e.isJsonPrimitive()) out.add(e.getAsString());
        }
        return out;
    }

    /** Remembers a merged manifest and the time (its push time) the project is in step with. */
    public static void markApplied(JsonObject extra, String manifestId, long at) {
        JsonObject rec = record(extra);
        if (rec == null) {
            rec = new JsonObject();
            extra.add(KEY, rec);
        }
        java.util.List<String> ids = new ArrayList<>(applied(extra));
        ids.remove(manifestId);
        ids.add(manifestId);
        while (ids.size() > KEEP_APPLIED) ids.remove(0);
        com.google.gson.JsonArray a = new com.google.gson.JsonArray();
        for (String id : ids) a.add(id);
        rec.add("applied", a);
        if (at > syncedAt(extra)) rec.addProperty("syncedAt", at);
    }

    /** The newest push time this project is known to contain: its own last push or the newest merged manifest. */
    public static long syncedAt(JsonObject extra) {
        JsonObject rec = record(extra);
        if (rec == null) return 0;
        long a = rec.has("syncedAt") ? rec.get("syncedAt").getAsLong() : 0;
        long b = rec.has("pushedAt") ? rec.get("pushedAt").getAsLong() : 0;
        return Math.max(a, b);
    }

    /** Whether edits are pushed by themselves (default true). */
    public static boolean auto(JsonObject extra) {
        JsonObject rec = record(extra);
        return rec == null || !rec.has("auto") || rec.get("auto").getAsBoolean();
    }

    public static void setAuto(JsonObject extra, boolean on) {
        JsonObject rec = record(extra);
        if (rec == null) {
            rec = new JsonObject();
            extra.add(KEY, rec);
        }
        rec.addProperty("auto", on);
    }

    /** Records that the manifest content with this signature is already in step with the server (nothing to push). */
    public static void markManifestSynced(JsonObject extra, String sig) {
        Manifest m = recordedManifest(extra);
        JsonObject rec = record(extra);
        if (rec == null) {
            rec = new JsonObject();
            extra.add(KEY, rec);
        }
        JsonObject o = new JsonObject();
        o.addProperty("id", m == null ? "" : m.id());
        o.addProperty("sig", sig);
        o.addProperty("placement", m == null ? "" : m.placement());
        rec.add("manifest", o);
    }

    private static JsonObject record(JsonObject extra) {
        JsonElement e = extra == null ? null : extra.get(KEY);
        return e != null && e.isJsonObject() ? e.getAsJsonObject() : null;
    }
}
