package dev.schemarray;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.fabricmc.loader.api.FabricLoader;

import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The arrays, keyed by the hash id of the original placement (Litematica keeps that id in its own placement file, so
 * it survives restarts). Each array is a stack of Blender-style modifiers and remembers the ids of its copy
 * placements per cell, so they are found again. Saved in config/schemarray/arrays.json.
 */
public final class ArrayStore {
    private ArrayStore() {}

    /** One Blender-style array modifier. Offsets are along the schematic's own axes. */
    public static final class Mod {
        public boolean enabled = true;
        public int count = 2;                        // >= 1; 1 = no copies from this modifier
        public boolean useRelative = true;
        public double[] relative = {1, 0, 0};        // fraction of the current bounding box size, per axis
        public boolean useConstant = false;
        public int[] constant = {0, 0, 0};           // blocks, per axis

        public Mod copy() {
            Mod m = new Mod();
            m.enabled = enabled;
            m.count = count;
            m.useRelative = useRelative;
            m.relative = relative.clone();
            m.useConstant = useConstant;
            m.constant = constant.clone();
            return m;
        }
    }

    static final class Entry {
        List<Mod> mods = new ArrayList<>();
        Map<String, String> copies = new LinkedHashMap<>();   // "i,j,k" (one index per enabled modifier) -> copy placement hash id
        int[] counts;   // legacy (before 1.6.0): read once by migrate(), then nulled
        int[] gaps;     // legacy

        boolean active() {
            long n = ArraySync.copyCountOf(mods);
            return n >= 1 && n <= ArraySync.MAX_COPIES;
        }
    }

    /** Converts a pre-1.6.0 entry (counts/gaps per axis) to modifiers, keeping its copy placements under their new keys. */
    private static void migrate(Entry e) {
        int[] counts = e.counts, gaps = e.gaps == null ? new int[3] : e.gaps;
        e.counts = null;
        e.gaps = null;
        if (counts.length < 3 || gaps.length < 3) return;
        List<Integer> axes = new ArrayList<>();
        e.mods = new ArrayList<>();
        for (int a = 0; a < 3; a++) {
            if (Math.abs(counts[a]) <= 1) continue;
            int sign = Integer.signum(counts[a]);
            Mod m = new Mod();
            m.count = Math.abs(counts[a]);
            m.useRelative = true;
            m.relative = new double[3];
            m.relative[a] = sign;
            m.useConstant = gaps[a] != 0;
            m.constant = new int[3];
            m.constant[a] = gaps[a] * sign;
            e.mods.add(m);
            axes.add(a);
        }
        // old cell "x,y,z" -> new cell: the indices of the non-trivial axes, in order
        Map<String, String> remapped = new LinkedHashMap<>();
        for (var c : e.copies.entrySet()) {
            try {
                String[] k = c.getKey().split(",");
                StringBuilder nk = new StringBuilder();
                for (int a : axes) {
                    if (nk.length() > 0) nk.append(',');
                    nk.append(Integer.parseInt(k[a].trim()));
                }
                remapped.put(nk.toString(), c.getValue());
            } catch (RuntimeException ex) {
                // unreadable key: forgotten
            }
        }
        e.copies = remapped;
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static Map<String, Entry> arrays;
    private static Map<String, String> copyToParent;

    private static Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve("schemarray").resolve("arrays.json");
    }

    static Map<String, Entry> all() {
        if (arrays == null) {
            arrays = new LinkedHashMap<>();
            boolean legacy = false;
            Path f = file();
            if (Files.exists(f)) {
                try (Reader r = Files.newBufferedReader(f)) {
                    Map<String, Entry> m = GSON.fromJson(r, new TypeToken<LinkedHashMap<String, Entry>>() {}.getType());
                    if (m != null) arrays.putAll(m);
                } catch (Exception ex) {
                    System.out.println("[SchematicArray] cannot read " + f + ": " + ex);
                }
                arrays.values().removeIf(java.util.Objects::isNull);
                for (Entry e : arrays.values()) {
                    if (e.mods == null) e.mods = new ArrayList<>();
                    if (e.copies == null) e.copies = new LinkedHashMap<>();
                    if (e.counts != null) { migrate(e); legacy = true; }
                    for (Mod md : e.mods) {
                        if (md.relative == null || md.relative.length < 3) md.relative = new double[]{1, 0, 0};
                        if (md.constant == null || md.constant.length < 3) md.constant = new int[3];
                        if (md.count < 1) md.count = 1;
                    }
                }
            }
            reindex();
            if (legacy) save();
        }
        return arrays;
    }

    static void reindex() {
        copyToParent = new HashMap<>();
        for (var e : arrays.entrySet()) for (String c : e.getValue().copies.values()) copyToParent.put(c, e.getKey());
    }

    /** The original placement's id if this id is one of our copies, else null. */
    static String parentOf(UUID id) {
        all();
        return copyToParent.get(id.toString());
    }

    static Entry get(UUID parent) {
        return all().get(parent.toString());
    }

    static Entry getOrCreate(UUID parent) {
        return all().computeIfAbsent(parent.toString(), k -> new Entry());
    }

    static void remove(String parent) {
        all().remove(parent);
        reindex();
    }

    static void save() {
        reindex();
        Path f = file();
        try {
            Files.createDirectories(f.getParent());
            try (Writer w = Files.newBufferedWriter(f)) {
                GSON.toJson(arrays, w);
            }
        } catch (Exception ex) {
            System.out.println("[SchematicArray] cannot write " + f + ": " + ex);
        }
    }
}
