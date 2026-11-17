package dev.vitruvius.shape.mc;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.vitruvius.shape.Axis;
import dev.vitruvius.shape.BlockEntry;
import dev.vitruvius.shape.BlockSlot;
import dev.vitruvius.shape.GeneratorSettings;
import dev.vitruvius.shape.PatternMode;
import dev.vitruvius.shape.ShapeSpec;
import dev.vitruvius.shape.ShapeType;
import net.fabricmc.loader.api.FabricLoader;

import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Last settings and named presets in config/vitruvius/shapes.json: {"last": settings, "ghost": bool, "folder": "sub/dir", "presets": {name: settings}}.
 * The records are mapped to JSON by hand and read back tolerantly: a missing or broken field falls back to its
 * default, so an old or hand-edited file never loses the rest.
 */
public final class ShapePresets {
    private ShapePresets() {}

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static boolean loaded;
    private static GeneratorSettings last = GeneratorSettings.defaults();
    private static boolean ghost = true;
    private static String folder = "";
    private static final Map<String, GeneratorSettings> PRESETS = new LinkedHashMap<>();

    private static Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve("vitruvius").resolve("shapes.json");
    }

    private static void load() {
        if (loaded) return;
        loaded = true;
        Path f = file();
        if (!Files.exists(f)) return;
        try (Reader r = Files.newBufferedReader(f)) {
            JsonElement root = GSON.fromJson(r, JsonElement.class);
            if (root == null || !root.isJsonObject()) return;
            JsonObject o = root.getAsJsonObject();
            if (o.get("last") instanceof JsonObject l) last = readSettings(l);
            ghost = bool(o, "ghost", true);
            folder = str(o, "folder", "");
            if (o.get("presets") instanceof JsonObject p) {
                for (var e : p.entrySet()) if (e.getValue() instanceof JsonObject s) PRESETS.put(e.getKey(), readSettings(s));
            }
        } catch (Exception ex) {
            System.out.println("[Vitruvius] cannot read " + f + ": " + ex);
        }
    }

    private static void save() {
        Path f = file();
        JsonObject root = new JsonObject();
        root.add("last", writeSettings(last));
        root.addProperty("ghost", ghost);
        root.addProperty("folder", folder);
        JsonObject presets = new JsonObject();
        for (var e : PRESETS.entrySet()) presets.add(e.getKey(), writeSettings(e.getValue()));
        root.add("presets", presets);
        try {
            Files.createDirectories(f.getParent());
            try (Writer w = Files.newBufferedWriter(f)) {
                GSON.toJson(root, w);
            }
        } catch (Exception ex) {
            System.out.println("[Vitruvius] cannot write " + f + ": " + ex);
        }
    }

    /** The settings of the last session, or GeneratorSettings.defaults(). */
    public static GeneratorSettings last() {
        load();
        return last;
    }

    public static void setLast(GeneratorSettings settings) {
        load();
        last = settings;
        save();
    }

    /** Whether the in-world ghost is switched on (default true). */
    public static boolean ghost() {
        load();
        return ghost;
    }

    public static void setGhost(boolean on) {
        load();
        ghost = on;
        save();
    }

    /** The save folder relative to the schematics folder, "/" separated; "" = the schematics folder itself. */
    public static String folder() {
        load();
        return folder;
    }

    public static void setFolder(String relative) {
        load();
        folder = relative;
        save();
    }

    /** Preset names, sorted case-insensitively. */
    public static List<String> names() {
        load();
        List<String> names = new ArrayList<>(PRESETS.keySet());
        names.sort(String.CASE_INSENSITIVE_ORDER);
        return names;
    }

    /** The preset, or null. */
    public static GeneratorSettings get(String name) {
        load();
        return PRESETS.get(name);
    }

    /** Saves (or replaces) a preset and writes the file. */
    public static void put(String name, GeneratorSettings settings) {
        load();
        PRESETS.put(name, settings);
        save();
    }

    public static void remove(String name) {
        load();
        if (PRESETS.remove(name) != null) save();
    }

    // --- JSON mapping ---

    private static JsonObject writeSettings(GeneratorSettings s) {
        JsonObject o = new JsonObject();
        ShapeSpec sp = s.spec();
        JsonObject spec = new JsonObject();
        spec.addProperty("type", sp.type().name());
        spec.addProperty("sizeX", sp.sizeX());
        spec.addProperty("sizeY", sp.sizeY());
        spec.addProperty("sizeZ", sp.sizeZ());
        spec.addProperty("axis", sp.axis().name());
        spec.addProperty("thickness", sp.thickness());
        spec.addProperty("param1", sp.param1());
        spec.addProperty("param2", sp.param2());
        spec.addProperty("anchorCenter", sp.anchorCenter());
        o.add("spec", spec);
        o.add("outline", writeSlot(s.outline()));
        o.add("fill", writeSlot(s.fill()));
        o.addProperty("name", s.name());
        return o;
    }

    private static JsonObject writeSlot(BlockSlot b) {
        JsonObject o = new JsonObject();
        o.addProperty("mode", b.mode().name());
        JsonArray entries = new JsonArray();
        for (BlockEntry e : b.entries()) {
            JsonObject entry = new JsonObject();
            entry.addProperty("block", e.blockState());
            entry.addProperty("weight", e.weight());
            entries.add(entry);
        }
        o.add("entries", entries);
        o.addProperty("cellX", b.cellX());
        o.addProperty("cellY", b.cellY());
        o.addProperty("cellZ", b.cellZ());
        o.addProperty("axis", b.axis().name());
        o.addProperty("width", b.width());
        o.addProperty("seed", b.seed());
        o.addProperty("dither", b.dither());
        return o;
    }

    private static GeneratorSettings readSettings(JsonObject o) {
        GeneratorSettings d = GeneratorSettings.defaults();
        return new GeneratorSettings(
                o.get("spec") instanceof JsonObject s ? readSpec(s, d.spec()) : d.spec(),
                o.get("outline") instanceof JsonObject s ? readSlot(s, d.outline()) : d.outline(),
                o.get("fill") instanceof JsonObject s ? readSlot(s, d.fill()) : d.fill(),
                str(o, "name", d.name()));
    }

    private static ShapeSpec readSpec(JsonObject o, ShapeSpec d) {
        return new ShapeSpec(
                enumOf(ShapeType.class, o, "type", d.type()),
                num(o, "sizeX", d.sizeX()), num(o, "sizeY", d.sizeY()), num(o, "sizeZ", d.sizeZ()),
                enumOf(Axis.class, o, "axis", d.axis()),
                num(o, "thickness", d.thickness()), num(o, "param1", d.param1()), num(o, "param2", d.param2()),
                bool(o, "anchorCenter", d.anchorCenter())).clamped();
    }

    private static BlockSlot readSlot(JsonObject o, BlockSlot d) {
        List<BlockEntry> entries = new ArrayList<>();
        if (o.get("entries") instanceof JsonArray arr) {
            for (JsonElement e : arr) {
                if (e instanceof JsonObject eo) entries.add(new BlockEntry(str(eo, "block", ""), num(eo, "weight", 1)));
            }
        } else {
            entries = d.entries();
        }
        return new BlockSlot(enumOf(PatternMode.class, o, "mode", d.mode()), entries,
                num(o, "cellX", d.cellX()), num(o, "cellY", d.cellY()), num(o, "cellZ", d.cellZ()),
                enumOf(Axis.class, o, "axis", d.axis()), num(o, "width", d.width()),
                lng(o, "seed", d.seed()), bool(o, "dither", d.dither()));
    }

    private static int num(JsonObject o, String key, int def) {
        try {
            return o.has(key) ? o.get(key).getAsInt() : def;
        } catch (RuntimeException e) {
            return def;
        }
    }

    private static long lng(JsonObject o, String key, long def) {
        try {
            return o.has(key) ? o.get(key).getAsLong() : def;
        } catch (RuntimeException e) {
            return def;
        }
    }

    private static boolean bool(JsonObject o, String key, boolean def) {
        try {
            return o.has(key) ? o.get(key).getAsBoolean() : def;
        } catch (RuntimeException e) {
            return def;
        }
    }

    private static String str(JsonObject o, String key, String def) {
        try {
            return o.has(key) ? o.get(key).getAsString() : def;
        } catch (RuntimeException e) {
            return def;
        }
    }

    private static <E extends Enum<E>> E enumOf(Class<E> type, JsonObject o, String key, E def) {
        try {
            return o.has(key) ? Enum.valueOf(type, o.get(key).getAsString()) : def;
        } catch (RuntimeException e) {
            return def;
        }
    }
}
