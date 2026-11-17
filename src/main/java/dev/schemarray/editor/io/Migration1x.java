package dev.schemarray.editor.io;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.schemarray.editor.core.ArrayModifier;
import dev.schemarray.editor.core.FolderNode;
import dev.schemarray.editor.core.Ids;
import dev.schemarray.editor.core.LooseNode;
import dev.schemarray.editor.core.Mirror;
import dev.schemarray.editor.core.Node;
import dev.schemarray.editor.core.ObjectNode;
import dev.schemarray.editor.core.Rot;
import dev.schemarray.editor.core.Transform;
import dev.schemarray.editor.core.Vec3i;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * Turns the 1.x {@code config/schemarray/folders.json} and {@code arrays.json} into a LooseNode tree.
 *
 * <ul>
 *   <li>A 1.x folder becomes a FolderNode (name, open, hidden; nesting kept; the colour index goes to
 *       {@code extra.color}).
 *   <li>A placement becomes an ObjectNode in the folder it sat in. What the config files do not hold (file, origin,
 *       rotation, mirror, name) comes from the {@code lookup}, which the Minecraft side fills from the live
 *       Litematica placements. A placement the lookup does not know is skipped (listed in the result).
 *   <li>An array entry becomes the ArrayModifiers of its original; pre-1.6 entries (counts/gaps per axis) are
 *       converted like {@code ArrayStore.migrate}. The copy placements become {@code placementIds}, and the original
 *       placement's own id goes in under {@link ObjectNode#BASE_CELL}.
 *   <li>Copy placements are not objects of their own.
 * </ul>
 * Ids are derived from the 1.x ids, so migrating the same files twice gives the same ids. The hash ids themselves are
 * never used as node ids.
 */
public final class Migration1x {
    private Migration1x() {}

    /** What the Minecraft side knows about one placement. {@code name} may be null (the file name is used). */
    public record PlacementInfo(String name, String file, Vec3i origin, Rot rot, Mirror mirror) {}

    /** The migrated tree and the ids the lookup could not resolve (in the order they were met). */
    public record Result(LooseNode loose, List<String> skipped) {}

    /**
     * @param foldersJson   the 1.x folders.json (a missing file means no folders)
     * @param arraysJson    the 1.x arrays.json (a missing file means no arrays)
     * @param placementIds  the hash ids of all placements Litematica has; they come first, in this order
     * @param lookup        hash id to placement info, or null if there is no such placement
     */
    public static Result migrate(Path foldersJson, Path arraysJson, Collection<String> placementIds,
                                 Function<String, PlacementInfo> lookup) throws IOException {
        JsonObject folders = readObject(foldersJson);
        JsonObject arrays = readObject(arraysJson);
        return migrate(folders, arrays, placementIds, lookup);
    }

    static Result migrate(JsonObject folders, JsonObject arrays, Collection<String> placementIds,
                          Function<String, PlacementInfo> lookup) throws FormatException {
        LooseNode loose = new LooseNode(Ids.derived("la1:loose"));

        // ---- folders ----
        Map<String, JsonObject> folderDefs = new LinkedHashMap<>();
        JsonObject fo = obj(folders, "folders");
        for (Map.Entry<String, JsonElement> e : fo.entrySet()) {
            if (e.getValue().isJsonObject()) folderDefs.put(e.getKey(), e.getValue().getAsJsonObject());
        }
        Map<String, String> parentOf = new HashMap<>();
        for (var e : folderDefs.entrySet()) {
            String p = str(e.getValue(), "parent", null);
            parentOf.put(e.getKey(), p != null && !p.equals(e.getKey()) && folderDefs.containsKey(p) ? p : null);
        }
        for (String id : folderDefs.keySet()) {   // a loop of parents: cut it at this folder
            Set<String> seen = new HashSet<>();
            for (String x = id; x != null; x = parentOf.get(x)) {
                if (!seen.add(x)) { parentOf.put(id, null); break; }
            }
        }
        Map<String, FolderNode> folderNodes = new LinkedHashMap<>();
        for (var e : folderDefs.entrySet()) {
            JsonObject d = e.getValue();
            FolderNode f = new FolderNode(Ids.derived("la1:folder:" + e.getKey()), str(d, "name", ""));
            f.setOpen(bool(d, "open", true));
            f.setHidden(bool(d, "hidden", false));
            int color = integer(d, "color", -1);
            if (color >= 0) f.extra().addProperty("color", color);
            folderNodes.put(e.getKey(), f);
        }
        // 1.x lists the child folders sorted by name (case-insensitive)
        List<String> folderOrder = new ArrayList<>(folderDefs.keySet());
        folderOrder.sort(Comparator.comparing((String id) -> str(folderDefs.get(id), "name", "").toLowerCase(Locale.ROOT)).thenComparing(id -> id));
        for (String id : folderOrder) {
            String p = parentOf.get(id);
            (p == null ? loose : folderNodes.get(p)).addChild(folderNodes.get(id));
        }

        // ---- arrays ----
        Map<String, JsonObject> arrayDefs = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> e : arrays.entrySet()) {
            if (e.getValue().isJsonObject()) arrayDefs.put(e.getKey(), e.getValue().getAsJsonObject());
        }
        Set<String> copyIds = new HashSet<>();
        Map<String, List<ArrayModifier>> modsOf = new HashMap<>();
        Map<String, Map<String, String>> copiesOf = new HashMap<>();
        for (var e : arrayDefs.entrySet()) {
            List<ArrayModifier> mods = new ArrayList<>();
            Map<String, String> copies = new LinkedHashMap<>();
            readArrayEntry(e.getValue(), mods, copies);
            modsOf.put(e.getKey(), mods);
            copiesOf.put(e.getKey(), copies);
            copyIds.addAll(copies.values());
        }

        // ---- placements ----
        JsonObject placementFolder = obj(folders, "placements");
        JsonObject placementColors = obj(folders, "placementColors");
        Set<String> ids = new LinkedHashSet<>(placementIds);
        ids.addAll(placementFolder.keySet());
        ids.addAll(arrayDefs.keySet());
        ids.removeAll(copyIds);

        List<String> skipped = new ArrayList<>();
        for (String id : ids) {
            PlacementInfo info = lookup.apply(id);
            if (info == null) {
                skipped.add(id);
                continue;
            }
            String file = info.file() == null ? "" : info.file();
            ObjectNode o = new ObjectNode(Ids.derived("la1:placement:" + id), info.name() != null ? info.name() : baseName(file));
            o.setFile(file);
            o.setTransform(new Transform(info.origin(), info.rot(), info.mirror()).canonical());
            List<ArrayModifier> mods = modsOf.getOrDefault(id, List.of());
            o.setMods(new ArrayList<>(mods));
            Map<String, String> ids2 = new LinkedHashMap<>();
            ids2.put(ObjectNode.BASE_CELL, id);
            ids2.putAll(copiesOf.getOrDefault(id, Map.of()));
            o.setPlacementIds(ids2);
            int color = integer(placementColors, id, -1);
            if (color >= 0) o.extra().addProperty("color", color);
            String folder = str(placementFolder, id, null);
            FolderNode target = folder == null ? null : folderNodes.get(folder);
            (target == null ? (Node) loose : target).addChild(o);
        }
        return new Result(loose, skipped);
    }

    /** Reads one arrays.json entry into modifiers and copy ids; converts the pre-1.6 counts/gaps form. */
    private static void readArrayEntry(JsonObject e, List<ArrayModifier> mods, Map<String, String> copies) {
        Map<String, String> raw = new LinkedHashMap<>();
        JsonObject c = obj(e, "copies");
        for (var en : c.entrySet()) if (en.getValue().isJsonPrimitive()) raw.put(en.getKey(), en.getValue().getAsString());

        JsonElement counts = e.get("counts");
        if (counts != null && counts.isJsonArray()) {
            // legacy (before 1.6.0): counts and gaps per axis; same conversion as ArrayStore.migrate
            int[] cn = ints(counts.getAsJsonArray()), gp = e.get("gaps") != null && e.get("gaps").isJsonArray() ? ints(e.get("gaps").getAsJsonArray()) : new int[3];
            if (cn.length < 3 || gp.length < 3) {
                copies.putAll(raw);
                return;
            }
            List<Integer> axes = new ArrayList<>();
            for (int a = 0; a < 3; a++) {
                if (Math.abs(cn[a]) <= 1) continue;
                int sign = Integer.signum(cn[a]);
                ArrayModifier m = new ArrayModifier();
                m.count = Math.abs(cn[a]);
                m.useRelative = true;
                m.relative = new double[3];
                m.relative[a] = sign;
                m.useConstant = gp[a] != 0;
                m.constant = new int[3];
                m.constant[a] = gp[a] * sign;
                mods.add(m);
                axes.add(a);
            }
            for (var en : raw.entrySet()) {
                try {
                    String[] k = en.getKey().split(",");
                    StringBuilder nk = new StringBuilder();
                    for (int a : axes) {
                        if (nk.length() > 0) nk.append(',');
                        nk.append(Integer.parseInt(k[a].trim()));
                    }
                    if (!axes.isEmpty()) copies.put(nk.toString(), en.getValue());
                } catch (RuntimeException ex) {
                    // unreadable key: forgotten, as in 1.x
                }
            }
            return;
        }

        JsonElement ms = e.get("mods");
        if (ms != null && ms.isJsonArray()) {
            for (JsonElement me : ms.getAsJsonArray()) {
                if (!me.isJsonObject()) continue;
                JsonObject o = me.getAsJsonObject();
                ArrayModifier m = new ArrayModifier();   // field defaults = the 1.x defaults for a missing field
                m.enabled = bool(o, "enabled", true);
                m.count = Math.max(1, integer(o, "count", 2));
                m.useRelative = bool(o, "useRelative", true);
                JsonElement rel = o.get("relative");
                if (rel != null && rel.isJsonArray() && rel.getAsJsonArray().size() >= 3) {
                    for (int k = 0; k < 3; k++) m.relative[k] = rel.getAsJsonArray().get(k).getAsDouble();
                }
                m.useConstant = bool(o, "useConstant", false);
                JsonElement con = o.get("constant");
                if (con != null && con.isJsonArray() && con.getAsJsonArray().size() >= 3) {
                    for (int k = 0; k < 3; k++) m.constant[k] = con.getAsJsonArray().get(k).getAsInt();
                }
                mods.add(m);
            }
        }
        copies.putAll(raw);
    }

    private static int[] ints(JsonArray a) {
        int[] out = new int[a.size()];
        for (int i = 0; i < out.length; i++) out[i] = a.get(i).getAsInt();
        return out;
    }

    private static String baseName(String file) {
        String s = file.replace('\\', '/');
        s = s.substring(s.lastIndexOf('/') + 1);
        int dot = s.lastIndexOf('.');
        return dot > 0 ? s.substring(0, dot) : s;
    }

    private static JsonObject readObject(Path file) throws IOException {
        if (file == null || !Files.exists(file)) return new JsonObject();
        JsonElement e;
        try {
            e = JsonText.parse(Files.readString(file, StandardCharsets.UTF_8));
        } catch (FormatException ex) {
            throw new FormatException(file + ": " + ex.getMessage(), ex);
        }
        if (e.isJsonNull()) return new JsonObject();   // an empty file
        if (!e.isJsonObject()) throw new FormatException(file + ": expected a JSON object");
        return e.getAsJsonObject();
    }

    private static JsonObject obj(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e != null && e.isJsonObject() ? e.getAsJsonObject() : new JsonObject();
    }

    private static String str(JsonObject o, String key, String def) {
        JsonElement e = o.get(key);
        return e != null && e.isJsonPrimitive() ? e.getAsString() : def;
    }

    private static boolean bool(JsonObject o, String key, boolean def) {
        JsonElement e = o.get(key);
        return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isBoolean() ? e.getAsBoolean() : def;
    }

    private static int integer(JsonObject o, String key, int def) {
        JsonElement e = o.get(key);
        try {
            return e != null && e.isJsonPrimitive() ? e.getAsInt() : def;
        } catch (RuntimeException ex) {
            return def;
        }
    }
}
