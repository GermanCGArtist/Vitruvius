package dev.vitruvius.editor.io;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.vitruvius.editor.core.FolderNode;
import dev.vitruvius.editor.core.Ids;
import dev.vitruvius.editor.core.LooseNode;
import dev.vitruvius.editor.core.Mirror;
import dev.vitruvius.editor.core.Node;
import dev.vitruvius.editor.core.ObjectNode;
import dev.vitruvius.editor.core.Rot;
import dev.vitruvius.editor.core.Transform;
import dev.vitruvius.editor.core.Vec3i;

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
 * Turns {@code config/vitruvius/folders.json} (the placement folders) and the live placements into a LooseNode tree,
 * the first time a world is entered.
 *
 * <ul>
 *   <li>A 1.x folder becomes a FolderNode (name, open, hidden; nesting kept; the colour index goes to
 *       {@code extra.color}).
 *   <li>A placement becomes an ObjectNode in the folder it sat in. What the config files do not hold (file, origin,
 *       rotation, mirror, name) comes from the {@code lookup}, which the Minecraft side fills from the live
 *       Litematica placements. A placement the lookup does not know is skipped (listed in the result).
 * </ul>
 * The arrays of 1.x ({@code arrays.json}) are not read: their copy placements come in as ordinary objects. Ids are
 * derived from the 1.x ids, so migrating the same files twice gives the same ids. The hash ids themselves are
 * never used as node ids.
 */
public final class Migration1x {
    private Migration1x() {}

    /** What the Minecraft side knows about one placement. {@code name} may be null (the file name is used). */
    public record PlacementInfo(String name, String file, Vec3i origin, Rot rot, Mirror mirror) {}

    /** The migrated tree and the ids the lookup could not resolve (in the order they were met). */
    public record Result(LooseNode loose, List<String> skipped) {}

    /**
     * @param foldersJson   folders.json (a missing file means no folders)
     * @param placementIds  the hash ids of all placements Litematica has; they come first, in this order
     * @param lookup        hash id to placement info, or null if there is no such placement
     */
    public static Result migrate(Path foldersJson, Collection<String> placementIds,
                                 Function<String, PlacementInfo> lookup) throws IOException {
        return migrate(readObject(foldersJson), placementIds, lookup);
    }

    static Result migrate(JsonObject folders, Collection<String> placementIds,
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


        // ---- placements ----
        JsonObject placementFolder = obj(folders, "placements");
        JsonObject placementColors = obj(folders, "placementColors");
        Set<String> ids = new LinkedHashSet<>(placementIds);
        ids.addAll(placementFolder.keySet());

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
            Map<String, String> ids2 = new LinkedHashMap<>();
            ids2.put(ObjectNode.BASE_CELL, id);
            o.setPlacementIds(ids2);
            int color = integer(placementColors, id, -1);
            if (color >= 0) o.extra().addProperty("color", color);
            String folder = str(placementFolder, id, null);
            FolderNode target = folder == null ? null : folderNodes.get(folder);
            (target == null ? (Node) loose : target).addChild(o);
        }
        return new Result(loose, skipped);
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
