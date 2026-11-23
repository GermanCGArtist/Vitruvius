package dev.vitruvius.api;

import dev.vitruvius.DragDrop;
import dev.vitruvius.Folders;
import dev.vitruvius.ListRefresh;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/** The extension point of Vitruvius for other mods. Call everything from the client thread. */
public final class VitruviusApi {
    private VitruviusApi() {}

    public static final int API_VERSION = 2;

    /** A button on the Shape Generator screen next to the save controls. */
    public record ShapeAction(String label, String hover, Consumer<ShapeActionContext> action) {}

    /** A button on every folder row of the placement list, left of Visible/Hidden. */
    public record FolderAction(String label, String hover, Consumer<FolderActionContext> action) {}

    private static final List<ListEntryProvider> PROVIDERS = new CopyOnWriteArrayList<>();
    private static final List<ShapeOutput> SHAPE_OUTPUTS = new CopyOnWriteArrayList<>();
    private static final List<ShapeAction> SHAPE_ACTIONS = new CopyOnWriteArrayList<>();
    private static final List<FolderAction> FOLDER_ACTIONS = new CopyOnWriteArrayList<>();

    /**
     * An entry in the editor's right-click menu, shown when the selection (or what is inside a selected folder,
     * project or assembly) holds objects of {@code typeId}. The action gets every placed copy of those objects:
     * array copies and copies made by arrayed or linked assemblies included, hidden ones left out.
     */
    public record SelectionAction(String typeId, String label, String hover, Consumer<List<SceneObjectView>> action) {}

    private static final List<SelectionAction> SELECTION_ACTIONS = new CopyOnWriteArrayList<>();

    /** Adds the provider's entries to the placement list (mixed with the placements, in folders, sorted by name). */
    public static void registerListEntries(ListEntryProvider provider) {
        if (provider != null && !PROVIDERS.contains(provider)) PROVIDERS.add(provider);
    }

    /**
     * Used to add a button to the old Shape Generator screen, which is gone. Kept so mods that still call it load;
     * the button is not shown anywhere. Use {@link #registerShapeOutput} for the editor's Shape generator.
     */
    @Deprecated
    public static void registerShapeAction(String buttonLabel, String hoverText, Consumer<ShapeActionContext> action) {
        if (buttonLabel != null && action != null) SHAPE_ACTIONS.add(new ShapeAction(buttonLabel, hoverText, action));
    }

    /** Adds one button to every folder row; it gets the folder and the ids of the entries inside (subfolders included). */
    public static void registerFolderAction(String buttonLabel, String hoverText, Consumer<FolderActionContext> action) {
        if (buttonLabel != null && action != null) FOLDER_ACTIONS.add(new FolderAction(buttonLabel, hoverText, action));
    }

    /**
     * (API 2) Registers a kind of scene object (see {@link SceneObjectType}): it shows up in the editor's Add menu and
     * its objects live in projects, assemblies and Loose. Call from the client thread at startup.
     */
    public static void registerObjectType(SceneObjectType type) {
        ObjectTypes.register(type);
    }

    /**
     * (API 2) Every placed copy of the objects of one type in the editor's open scene (world transform and a copy of
     * the data), for exports. Empty when no editor session is running.
     */
    public static List<SceneObjectView> objectsOfType(String typeId) {
        return ObjectTypes.viewsOf(typeId);
    }

    // ---- (API 3) keeping scene objects of your own type in step with your own state; see ExternalBridge ----

    /** True while the editor is running, so the calls below do something. */
    public static boolean editorReady() {
        return dev.vitruvius.editor.ExternalBridge.ready();
    }

    /** Adds an object of your type under Loose at a position; returns its id, or null. Not an undo step. */
    public static java.util.UUID addObject(String typeId, String name, com.google.gson.JsonObject data, int x, int y, int z) {
        return dev.vitruvius.editor.ExternalBridge.add(typeId, name, data, x, y, z);
    }

    public static boolean objectExists(java.util.UUID id) {
        return dev.vitruvius.editor.ExternalBridge.exists(id);
    }

    /** Sets an object's pose (rotSteps clockwise quarter turns; mirror 0 none, 1 left-right, 2 front-back). Not an undo step. */
    public static boolean setObjectPose(java.util.UUID id, int x, int y, int z, int rotSteps, int mirror) {
        return dev.vitruvius.editor.ExternalBridge.setPose(id, x, y, z, rotSteps, mirror);
    }

    public static boolean setObjectData(java.util.UUID id, com.google.gson.JsonObject data) {
        return dev.vitruvius.editor.ExternalBridge.setData(id, data);
    }

    public static boolean removeObject(java.util.UUID id) {
        return dev.vitruvius.editor.ExternalBridge.remove(id);
    }

    /** Rebuilds the placement list on the next tick (call after your entries changed). */
    public static void refreshList() {
        ListRefresh.later();
    }

    /** The folder id the entry (or placement hash id) sits in; null = top level. */
    public static String folderOf(String entryId) {
        return entryId == null ? null : Folders.folderOf(entryId);
    }

    /** The row color of the entry (own color, else its folder's) as ARGB, 0 = none. */
    public static int colorOf(String entryId) {
        if (entryId == null) return 0;
        int c = Folders.effectiveColorOfId(entryId);
        return c < 0 ? 0 : 0xFF000000 | Folders.COLORS[c];
    }

    /** The ids of the entries that are selected in the list right now. */
    public static Set<String> selectedEntryIds() {
        Set<String> out = new LinkedHashSet<>();
        for (Object o : DragDrop.selection()) if (o instanceof ListEntry e) out.add(e.id());
        return out;
    }

    // ---- used by Vitruvius itself ----

    /** All entries of all providers; a failing provider is skipped. */
    public static List<ListEntry> allEntries() {
        List<ListEntry> out = new ArrayList<>();
        for (ListEntryProvider p : PROVIDERS) {
            try {
                var entries = p.entries();
                if (entries != null) for (ListEntry e : entries) if (e != null && e.id() != null) out.add(e);
            } catch (Throwable t) {
                System.out.println("[Vitruvius] list entry provider failed: " + t);
            }
        }
        return out;
    }

    /** One 1.x list folder, for pickers: its id, name and how deep it sits. */
    public record FolderInfo(String id, String name, int depth) {}

    /** Every 1.x list folder as a flattened tree (not the editor's project folders). */
    public static List<FolderInfo> listFolders() {
        List<FolderInfo> out = new ArrayList<>();
        for (Folders.Node n : Folders.tree()) out.add(new FolderInfo(n.id, n.name, n.depth));
        return out;
    }

    /** Makes a list folder (parent null = top level) and returns its id. */
    public static String createFolder(String name, String parentId) {
        return Folders.create(name, parentId);
    }

    /** Puts a list entry (or placement hash id) into a folder; null = top level. */
    public static void moveEntryToFolder(String entryId, String folderId) {
        Folders.moveId(entryId, folderId);
        ListRefresh.later();
    }

    /** A file type that the editor's Add > Schematic picker lists next to .litematic; picking one calls place. */
    public record AssetType(String extension, String label, java.util.function.BiConsumer<java.nio.file.Path, net.minecraft.core.BlockPos> place) {}

    private static final List<AssetType> ASSET_TYPES = new CopyOnWriteArrayList<>();

    /** Lists files ending in {@code extension} (".heavymatic") in the Add schematic picker; place gets the file and the camera target. */
    public static void registerAssetType(String extension, String label, java.util.function.BiConsumer<java.nio.file.Path, net.minecraft.core.BlockPos> place) {
        if (extension != null && place != null) ASSET_TYPES.add(new AssetType(extension.toLowerCase(java.util.Locale.ROOT), label == null ? extension : label, place));
    }

    public static List<AssetType> assetTypes() {
        return ASSET_TYPES;
    }

    /** Adds an output to the editor's Shape generator window (live preview and a create button). */
    public static void registerShapeOutput(ShapeOutput output) {
        if (output != null && !SHAPE_OUTPUTS.contains(output)) SHAPE_OUTPUTS.add(output);
    }

    public static List<ShapeOutput> shapeOutputs() {
        return SHAPE_OUTPUTS;
    }

    @Deprecated
    public static List<ShapeAction> shapeActions() {
        return SHAPE_ACTIONS;
    }

    public static List<FolderAction> folderActions() {
        return FOLDER_ACTIONS;
    }

    /** Adds an entry to the editor's right-click menu for selections holding objects of a type (see {@link SelectionAction}). */
    public static void registerSelectionAction(String typeId, String label, String hover, Consumer<List<SceneObjectView>> action) {
        if (typeId == null || label == null || action == null) return;
        SELECTION_ACTIONS.add(new SelectionAction(typeId, label, hover == null ? "" : hover, action));
    }

    public static List<SelectionAction> selectionActions() {
        return SELECTION_ACTIONS;
    }

    /**
     * Every placed copy of the objects of a type that are selected in the editor or sit inside a selected folder,
     * project or assembly (array copies included, hidden ones too, flagged). Empty without an editor session.
     */
    public static List<SceneObjectView> selectedObjectsOfType(String typeId) {
        dev.vitruvius.editor.EditorSession s = dev.vitruvius.editor.EditorMode.isActive() ? dev.vitruvius.editor.EditorSession.get() : null;
        if (s == null) return List.of();
        return ObjectTypes.viewsUnder(typeId, s.selection.ids());
    }
}
