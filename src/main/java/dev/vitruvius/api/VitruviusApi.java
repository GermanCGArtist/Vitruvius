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
    private static final List<ShapeAction> SHAPE_ACTIONS = new CopyOnWriteArrayList<>();
    private static final List<FolderAction> FOLDER_ACTIONS = new CopyOnWriteArrayList<>();

    /** Adds the provider's entries to the placement list (mixed with the placements, in folders, sorted by name). */
    public static void registerListEntries(ListEntryProvider provider) {
        if (provider != null && !PROVIDERS.contains(provider)) PROVIDERS.add(provider);
    }

    /** Adds one button to the Shape Generator screen; it gets the generated shape when clicked. */
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

    public static List<ShapeAction> shapeActions() {
        return SHAPE_ACTIONS;
    }

    public static List<FolderAction> folderActions() {
        return FOLDER_ACTIONS;
    }
}
