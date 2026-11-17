package dev.vitruvius;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dev.vitruvius.api.ListEntry;
import dev.vitruvius.api.VitruviusApi;
import dev.vitruvius.shape.mc.ShapePreview;
import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;

import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * The folders of the placement list. Folders are global (shared by all worlds) and can be nested; a placement sits in
 * at most one folder, keyed by its hash id like the arrays. A hidden folder switches the rendering of everything
 * inside it off; the placements it switched off itself are remembered, so ones the user had switched off stay off
 * when the folder is shown again. Saved in config/vitruvius/folders.json.
 *
 * The list also holds entries of other mods (ListEntry, see VitruviusApi). A row item is either a
 * SchematicPlacement or a ListEntry; both are keyed by a string id (the placement's hash id, the entry's id()) in the
 * same maps, so everything here works on both.
 */
public final class Folders {
    private Folders() {}

    static final class Folder {
        String name = "";
        String parent;   // folder id, null = top level
        boolean open = true;
        boolean hidden;
        int color = -1;   // index into COLORS, -1 = none
    }

    private static final class Data {
        Map<String, Folder> folders = new LinkedHashMap<>();
        Map<String, String> placements = new LinkedHashMap<>();   // placement hash id -> folder id
        Set<String> hiddenByFolder = new LinkedHashSet<>();
        Map<String, Integer> placementColors = new LinkedHashMap<>();   // placement hash id -> color index
    }

    /** A folder row in the placement list. */
    public static final class Node {
        public final String id;
        public final String name;
        public final int depth;
        public final int count;
        public final boolean open;
        public final boolean hidden;
        public final int color;
        /** A runtime folder that mirrors the editor's tree (a project, folder or assembly); see {@link #isEditorFolder}. */
        public final boolean editor;

        Node(String id, Folder f, int depth, int count) {
            this.id = id;
            this.name = f.name;
            this.depth = depth;
            this.count = count;
            this.open = f.open;
            this.hidden = f.hidden;
            this.color = f.color;
            this.editor = isEditorFolder(id);
        }
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static Data data;
    private static final Map<Object, Integer> DEPTH = new IdentityHashMap<>();

    /** The key of a list row item in the folder maps: the placement's hash id or the entry's id; null for anything else. */
    public static String idOf(Object item) {
        if (item instanceof SchematicPlacement p) return p.getHashId().toString();
        if (item instanceof ListEntry e) return e.id();
        return null;
    }

    public static String nameOf(Object item) {
        if (item instanceof SchematicPlacement p) return p.getName();
        if (item instanceof ListEntry e) return e.name();
        return "";
    }

    /** Array copies never get folders or colors of their own. */
    private static boolean isCopy(Object item) {
        return item instanceof SchematicPlacement p && ArraySync.isCopy(p);
    }

    private static Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve("vitruvius").resolve("folders.json");
    }

    private static Data data() {
        if (data == null) {
            data = new Data();
            Path f = file();
            if (Files.exists(f)) {
                try (Reader r = Files.newBufferedReader(f)) {
                    Data d = GSON.fromJson(r, Data.class);
                    if (d != null) {
                        if (d.folders != null) data.folders = d.folders;
                        if (d.placements != null) data.placements = d.placements;
                        if (d.hiddenByFolder != null) data.hiddenByFolder = d.hiddenByFolder;
                        if (d.placementColors != null) data.placementColors = d.placementColors;
                    }
                } catch (Exception ex) {
                    System.out.println("[Vitruvius] cannot read " + f + ": " + ex);
                }
            }
        }
        return data;
    }

    private static void save() {
        Path f = file();
        try {
            Files.createDirectories(f.getParent());
            try (Writer w = Files.newBufferedWriter(f)) {
                GSON.toJson(data(), w);
            }
        } catch (Exception ex) {
            System.out.println("[Vitruvius] cannot write " + f + ": " + ex);
        }
    }

    /** The folder's parent, or null if it is top level (or the parent no longer exists). */
    private static String parentId(Folder f) {
        return f.parent != null && folder(f.parent) != null ? f.parent : null;
    }

    /** Where the item with this id sits: its folder id, null = top level. */
    public static String folderOf(String itemId) {
        String ed = editorFolderOf(itemId);
        if (ed != null) return ed;
        String id = data().placements.get(itemId);
        return id != null && data().folders.containsKey(id) ? id : null;
    }

    /** Where the placement sits: its folder id, null = top level. */
    public static String folderOf(Object item) {
        String id = idOf(item);
        return id == null ? null : folderOf(id);
    }

    /**
     * The rows of the list: per level the folders (by name) first, then the items (placements and entries, by name);
     * only the contents of open folders. Also fills the depth the item rows are indented with.
     */
    public static List<Object> rows(Collection<?> items) {
        refreshEditor();
        DEPTH.clear();
        Map<String, List<Object>> byFolder = new LinkedHashMap<>();
        for (Object p : items) byFolder.computeIfAbsent(folderOf(p), k -> new ArrayList<>()).add(p);
        List<Object> out = new ArrayList<>();
        addLevel(null, 0, byFolder, out, new HashSet<>());
        return out;
    }

    private static void addLevel(String folder, int depth, Map<String, List<Object>> byFolder, List<Object> out, Set<String> seen) {
        for (String id : childFolders(folder)) {
            if (!seen.add(id)) continue;
            Folder f = folder(id);
            out.add(new Node(id, f, depth, count(id, byFolder, new HashSet<>())));
            if (f.open) addLevel(id, depth + 1, byFolder, out, seen);
        }
        List<Object> own = new ArrayList<>(byFolder.getOrDefault(folder, List.of()));
        own.sort(Comparator.comparing(p -> nameOf(p).toLowerCase()));
        for (Object p : own) {
            DEPTH.put(p, depth);
            out.add(p);
        }
    }

    private static int count(String folder, Map<String, List<Object>> byFolder, Set<String> seen) {
        if (!seen.add(folder)) return 0;
        int n = byFolder.getOrDefault(folder, List.of()).size();
        for (String c : childFolders(folder)) n += count(c, byFolder, seen);
        return n;
    }

    private static List<String> childFolders(String parent) {
        return childFolders(parent, true);
    }

    private static List<String> childFolders(String parent, boolean withEditor) {
        List<String> ids = new ArrayList<>();
        for (var e : data().folders.entrySet()) if (Objects.equals(parentId(e.getValue()), parent)) ids.add(e.getKey());
        if (withEditor) for (var e : editorFolders.entrySet()) if (Objects.equals(parentId(e.getValue()), parent)) ids.add(e.getKey());
        ids.sort(Comparator.comparing(id -> folder(id).name.toLowerCase()));
        return ids;
    }

    /** The name of the folder, or null if it does not exist. */
    public static String folderName(String id) {
        Folder f = folder(id);
        return f == null ? null : f.name;
    }

    /** The entries (of other mods) in the folder and all its subfolders, in list order: subfolders (by name) first, then own entries by name. */
    public static List<ListEntry> entriesIn(String folder, Collection<ListEntry> all) {
        Map<String, List<ListEntry>> byFolder = new LinkedHashMap<>();
        for (ListEntry e : all) byFolder.computeIfAbsent(folderOf(e.id()), k -> new ArrayList<>()).add(e);
        List<ListEntry> out = new ArrayList<>();
        collectEntries(folder, byFolder, out, new HashSet<>());
        return out;
    }

    private static void collectEntries(String folder, Map<String, List<ListEntry>> byFolder, List<ListEntry> out, Set<String> seen) {
        if (!seen.add(folder)) return;
        for (String c : childFolders(folder)) collectEntries(c, byFolder, out, seen);
        List<ListEntry> own = new ArrayList<>(byFolder.getOrDefault(folder, List.of()));
        own.sort(Comparator.comparing(e -> e.name().toLowerCase()));
        out.addAll(own);
    }

    /** Every 1.x folder as a tree, open or not (for the Move screen; editor folders are not targets). */
    public static List<Node> tree() {
        List<Node> out = new ArrayList<>();
        addTree(null, 0, out, new HashSet<>());
        return out;
    }

    private static void addTree(String parent, int depth, List<Node> out, Set<String> seen) {
        for (String id : childFolders(parent, false)) {
            if (!seen.add(id)) continue;
            out.add(new Node(id, data().folders.get(id), depth, 0));
            addTree(id, depth + 1, out, seen);
        }
    }

    /** How many levels the item's row is indented. */
    public static int depthOf(Object p) {
        return DEPTH.getOrDefault(p, 0);
    }

    public static String create(String name, String parent) {
        Folder f = new Folder();
        f.name = name;
        f.parent = parent != null && data().folders.containsKey(parent) ? parent : null;
        String id = UUID.randomUUID().toString();
        data().folders.put(id, f);
        if (f.parent != null) data().folders.get(f.parent).open = true;
        save();
        return id;
    }

    public static void rename(String id, String name) {
        Folder f = data().folders.get(id);
        if (f == null) return;
        f.name = name;
        save();
    }

    public static void toggleOpen(String id) {
        if (isEditorFolder(id)) {
            if (!editorClosed.remove(id)) editorClosed.add(id);
            Folder f = editorFolders.get(id);
            if (f != null) f.open = !editorClosed.contains(id);
            return;
        }
        Folder f = data().folders.get(id);
        if (f == null) return;
        f.open = !f.open;
        save();
    }

    public static void toggleHidden(String id) {
        Folder f = data().folders.get(id);
        if (f == null) return;
        f.hidden = !f.hidden;
        save();
    }

    /** Deletes the folder only: its subfolders and placements move up to its parent. */
    public static void delete(String id) {
        Folder f = data().folders.remove(id);
        if (f == null) return;
        String up = parentId(f);
        for (Folder c : data().folders.values()) if (id.equals(c.parent)) c.parent = up;
        for (var e : new ArrayList<>(data().placements.entrySet())) {
            if (!id.equals(e.getValue())) continue;
            if (up == null) data().placements.remove(e.getKey());
            else data().placements.put(e.getKey(), up);
        }
        save();
    }

    /** Puts the item into the folder (null = top level). Array copies are never put in a folder. */
    public static void move(Object p, String folder) {
        String id = idOf(p);
        if (id == null || isCopy(p) || editorFolderOf(id) != null) return;
        if (folder == null || !data().folders.containsKey(folder)) data().placements.remove(id);
        else data().placements.put(id, folder);
        save();
    }

    // ------------------------------------------------------------------ editor projects (2.0)

    /** Ids of the runtime folders that mirror the editor's tree start with this. */
    public static final String EDITOR_PREFIX = "editor:";

    /** The editor's projects, folders and assemblies as folders (rebuilt with every list build, never saved). */
    private static Map<String, Folder> editorFolders = Map.of();
    /** Placement hash id to the editor folder it sits in (from the reconciler's last pass). */
    private static Map<String, String> editorItems = Map.of();
    /** Editor folders the player closed in the list (runtime only). */
    private static final Set<String> editorClosed = new HashSet<>();

    public static boolean isEditorFolder(String id) {
        return id != null && id.startsWith(EDITOR_PREFIX);
    }

    private static Folder folder(String id) {
        if (id == null) return null;
        return isEditorFolder(id) ? editorFolders.get(id) : data().folders.get(id);
    }

    /**
     * The editor folder a placement sits in: placements of objects in a project mirror the editor's tree; Loose keeps
     * the 1.x folders. Null when the placement is not in a project.
     */
    private static String editorFolderOf(String itemId) {
        String f = editorItems.get(itemId);
        if (f != null) return f;
        dev.vitruvius.editor.core.ObjectNode o = dev.vitruvius.editor.mc.Reconciler.ownerOfBase(itemId);
        if (o == null) return null;
        String c = containerId(o.parent());
        return c != null && editorFolders.containsKey(c) ? c : null;
    }

    /** The id of the nearest project, folder or assembly at or above {@code n}, or null (Loose or nothing). */
    private static String containerId(dev.vitruvius.editor.core.Node n) {
        for (var x = n; x != null; x = x.parent()) {
            if (x instanceof dev.vitruvius.editor.core.LooseNode) return null;
            if (x instanceof dev.vitruvius.editor.core.ProjectNode || x instanceof dev.vitruvius.editor.core.FolderNode
                    || x instanceof dev.vitruvius.editor.core.AssemblyNode) {
                return EDITOR_PREFIX + x.id();
            }
        }
        return null;
    }

    private static void refreshEditor() {
        dev.vitruvius.editor.core.Scene scene = dev.vitruvius.editor.mc.Reconciler.sceneIfActive();
        if (scene == null) {
            editorFolders = Map.of();
            editorItems = Map.of();
            return;
        }
        Map<String, Folder> folders = new LinkedHashMap<>();
        for (dev.vitruvius.editor.core.ProjectNode p : scene.projects()) addEditorFolder(p, null, folders);
        editorFolders = folders;
        Map<String, String> items = new java.util.HashMap<>();
        var core = dev.vitruvius.editor.mc.Reconciler.core();
        if (core != null) {
            for (var c : core.cellRefs()) {
                String pid = c.placementId();
                if (pid == null || c.project() == null) continue;
                // an object of the scene sits in its own folder; a part of a linked assembly in the assembly instance
                boolean inScene = scene.byId(c.object().id()) == c.object();
                String fid = containerId(inScene ? c.object().parent() : c.owner());
                if (fid != null && folders.containsKey(fid)) items.putIfAbsent(pid, fid);
            }
        }
        editorItems = items;
    }

    private static void addEditorFolder(dev.vitruvius.editor.core.Node n, String parent, Map<String, Folder> out) {
        String id = EDITOR_PREFIX + n.id();
        Folder f = new Folder();
        f.name = n.name();
        f.parent = parent;
        f.open = !editorClosed.contains(id);
        f.hidden = n.hidden();
        out.put(id, f);
        if (n instanceof dev.vitruvius.editor.core.AssemblyNode a && a.isLinked()) return;   // its parts are not scene nodes
        for (var c : n.children()) {
            if (c instanceof dev.vitruvius.editor.core.FolderNode || c instanceof dev.vitruvius.editor.core.AssemblyNode) {
                addEditorFolder(c, id, out);
            }
        }
    }

    /** Minecraft's 16 chat colors (§0-§f) as RGB; a color is an index into this, -1 = none. */
    public static final int[] COLORS = {
            0x000000, 0x0000AA, 0x00AA00, 0x00AAAA, 0xAA0000, 0xAA00AA, 0xFFAA00, 0xAAAAAA,
            0x555555, 0x5555FF, 0x55FF55, 0x55FFFF, 0xFF5555, 0xFF55FF, 0xFFFF55, 0xFFFFFF};

    public static void setFolderColor(String id, int color) {
        Folder f = data().folders.get(id);
        if (f == null) return;
        f.color = color;
        save();
    }

    /** The item's own color, -1 = none. */
    public static int ownColor(Object p) {
        String id = idOf(p);
        Integer c = id == null ? null : data().placementColors.get(id);
        return c == null || c < 0 || c >= COLORS.length ? -1 : c;
    }

    public static void setPlacementColors(Collection<?> items, int color) {
        for (Object p : items) {
            String id = idOf(p);
            if (id == null) continue;
            if (color < 0) data().placementColors.remove(id);
            else data().placementColors.put(id, color);
        }
        save();
    }

    /** The color the row shows: its own, else that of the nearest colored folder above it, else -1. */
    public static int effectiveColor(Object p) {
        String id = idOf(p);
        return id == null ? -1 : effectiveColorOfId(id);
    }

    public static int effectiveColorOfId(String id) {
        Integer c = data().placementColors.get(id);
        if (c != null && c >= 0 && c < COLORS.length) return c;
        Set<String> seen = new HashSet<>();
        String folder = folderOf(id);
        while (folder != null && seen.add(folder)) {
            Folder f = folder(folder);
            if (f == null) return -1;
            if (f.color >= 0 && f.color < COLORS.length) return f.color;
            folder = parentId(f);
        }
        return -1;
    }

    /** True if the folder is {@code ancestor} itself or sits somewhere below it. */
    public static boolean isInside(String folder, String ancestor) {
        Set<String> seen = new HashSet<>();
        while (folder != null && seen.add(folder)) {
            if (folder.equals(ancestor)) return true;
            Folder f = folder(folder);
            if (f == null) return false;
            folder = parentId(f);
        }
        return false;
    }

    /** Moves the folder (with everything in it) into the parent (null = top level); false if that would put it into itself. */
    public static boolean moveFolder(String id, String parent) {
        if (isEditorFolder(id) || isEditorFolder(parent)) return false;
        Folder f = data().folders.get(id);
        if (f == null) return false;
        if (parent != null && (!data().folders.containsKey(parent) || isInside(parent, id))) return false;
        f.parent = parent;
        if (parent != null) data().folders.get(parent).open = true;
        save();
        return true;
    }

    /** Puts all the items into the folder (null = top level), opens that folder and saves once. */
    public static void moveAll(Collection<?> items, String folder) {
        if (isEditorFolder(folder)) return;   // the editor decides what is in its folders
        boolean top = folder == null || !data().folders.containsKey(folder);
        for (Object p : items) {
            String id = idOf(p);
            if (id == null || isCopy(p) || editorFolderOf(id) != null) continue;
            if (top) data().placements.remove(id);
            else data().placements.put(id, folder);
        }
        if (!top) data().folders.get(folder).open = true;
        save();
    }

    private static boolean hidden(String folder) {
        Set<String> seen = new HashSet<>();
        // an editor folder never hides by switching rendering: the editor removes the placements of hidden nodes itself
        while (folder != null && !isEditorFolder(folder) && seen.add(folder)) {
            Folder f = data().folders.get(folder);
            if (f == null) return false;
            if (f.hidden) return true;
            folder = parentId(f);
        }
        return false;
    }

    /** Every tick: switches the rendering of placements and entries in hidden folders off, and back on when the folder is shown. */
    public static void tick(Minecraft mc) {
        if (mc.level == null) return;
        Data d = data();
        if (d.folders.isEmpty() && d.hiddenByFolder.isEmpty()) return;
        boolean changed = false;
        List<Object> items = new ArrayList<>(DataManager.getSchematicPlacementManager().getAllSchematicsPlacements());
        items.addAll(VitruviusApi.allEntries());
        for (Object item : items) {
            if (isCopy(item) || (item instanceof SchematicPlacement p && ShapePreview.isPreview(p))) continue;
            String id = idOf(item);
            if (id == null) continue;
            boolean on = item instanceof SchematicPlacement p ? p.isRenderingEnabled() : ((ListEntry) item).isRenderingEnabled();
            if (hidden(folderOf(id))) {
                if (on) {
                    setRendering(item, false);
                    d.hiddenByFolder.add(id);
                    changed = true;
                }
            } else if (d.hiddenByFolder.contains(id)) {
                setRendering(item, true);
                d.hiddenByFolder.remove(id);
                changed = true;
            }
        }
        if (changed) save();
    }

    private static void setRendering(Object item, boolean on) {
        if (item instanceof SchematicPlacement p) p.setRenderSchematic(on);
        else if (item instanceof ListEntry e) e.setRenderingEnabled(on);
    }
}
