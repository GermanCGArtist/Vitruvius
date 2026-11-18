package dev.vitruvius.editor.sync;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * The manifest of a shared project (EDITOR-2.0 section 8): a placement named {@code [LA2] <project>} whose schematic is a
 * single air block and whose metadata description holds the project: its scene graph (the project.json text) and which
 * Syncmatica placement holds which cell. Pure.
 *
 * <p>The description text is {@code la2:} plus the JSON, or {@code la2z:} plus gzip and base64 when the JSON is longer
 * than {@link #GZIP_ABOVE} characters. NBT strings stop at 65535 bytes, so a manifest whose text is longer than
 * {@link #MAX_DESCRIPTION} cannot be written ({@link #fits}).
 */
public final class SyncManifest {
    private SyncManifest() {}

    public static final String PREFIX = "[LA2] ";
    public static final int GZIP_ABOVE = 30_000;
    public static final int MAX_DESCRIPTION = 60_000;
    public static final int VERSION = 1;

    private static final String PLAIN = "la2:", ZIPPED = "la2z:";

    public static String nameFor(String project) {
        return PREFIX + project;
    }

    /**
     * The placement name of one cell: {@code [<project>] <object>}. A server-side access mod (vitruvius-access) reads the
     * project out of it, as it does out of the manifest name. A ']' in the project name would end the bracket early, so it is written ')'.
     */
    public static String cellName(String project, String object) {
        return "[" + project.replace(']', ')') + "] " + object;
    }

    public static boolean isManifest(String placementName) {
        return placementName != null && placementName.startsWith(PREFIX);
    }

    /** The project name in a manifest placement name ("[LA2] Metro" gives "Metro"). */
    public static String projectName(String placementName) {
        return isManifest(placementName) ? placementName.substring(PREFIX.length()) : placementName;
    }

    /** The description text for a JSON text. */
    public static String encode(String json) {
        if (json.length() <= GZIP_ABOVE) return PLAIN + json;
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (GZIPOutputStream gz = new GZIPOutputStream(bytes)) {
            gz.write(json.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return ZIPPED + Base64.getEncoder().encodeToString(bytes.toByteArray());
    }

    /** Whether an encoded description can be stored. */
    public static boolean fits(String encoded) {
        return encoded.getBytes(StandardCharsets.UTF_8).length <= MAX_DESCRIPTION;
    }

    /** The JSON text inside a description. */
    public static String decode(String description) {
        if (description == null) throw new IllegalArgumentException("no description");
        if (description.startsWith(PLAIN)) return description.substring(PLAIN.length());
        if (!description.startsWith(ZIPPED)) throw new IllegalArgumentException("not a Vitruvius manifest");
        try {
            byte[] raw = Base64.getDecoder().decode(description.substring(ZIPPED.length()));
            try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(raw))) {
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
        } catch (IOException | IllegalArgumentException e) {
            throw new IllegalArgumentException("the manifest is damaged: " + e.getMessage(), e);
        }
    }

    /** What a manifest carries: the project.json text and the Syncmatica placement id of each cell (by cell key). */
    public record Payload(JsonObject project, Map<String, String> cells, long at, String by) {
        /** The id of the project inside (empty when the manifest has none). */
        public String projectId() {
            return project.has("id") && project.get("id").isJsonPrimitive() ? project.get("id").getAsString() : "";
        }
    }

    /** The JSON text of a payload. {@code project} is the project.json object (without the sync record). */
    public static String payloadJson(JsonObject project, Map<String, String> cells) {
        return payloadJson(project, cells, 0, "");
    }

    /** As above, with the time of the push (milliseconds) and the name of the player who pushed. */
    public static String payloadJson(JsonObject project, Map<String, String> cells, long at, String by) {
        JsonObject o = new JsonObject();
        o.addProperty("v", VERSION);
        o.addProperty("at", at);
        o.addProperty("by", by == null ? "" : by);
        o.add("project", project);
        JsonObject c = new JsonObject();
        for (var e : cells.entrySet()) c.addProperty(e.getKey(), e.getValue());
        o.add("cells", c);
        return o.toString();
    }

    public static Payload parse(String json) {
        JsonElement e;
        try {
            e = JsonParser.parseString(json);
        } catch (RuntimeException ex) {
            throw new IllegalArgumentException("the manifest is not JSON: " + ex.getMessage(), ex);
        }
        if (!e.isJsonObject()) throw new IllegalArgumentException("the manifest is not an object");
        JsonObject o = e.getAsJsonObject();
        int v = o.has("v") && o.get("v").isJsonPrimitive() ? o.get("v").getAsInt() : 0;
        if (v != VERSION) throw new IllegalArgumentException("unknown manifest version " + v);
        if (!o.has("project") || !o.get("project").isJsonObject()) throw new IllegalArgumentException("the manifest has no project");
        Map<String, String> cells = new LinkedHashMap<>();
        if (o.has("cells") && o.get("cells").isJsonObject()) {
            for (var en : o.getAsJsonObject("cells").entrySet()) if (en.getValue().isJsonPrimitive()) cells.put(en.getKey(), en.getValue().getAsString());
        }
        long at = o.has("at") && o.get("at").isJsonPrimitive() ? o.get("at").getAsLong() : 0;
        String by = o.has("by") && o.get("by").isJsonPrimitive() ? o.get("by").getAsString() : "";
        return new Payload(o.getAsJsonObject("project"), cells, at, by);
    }
}
