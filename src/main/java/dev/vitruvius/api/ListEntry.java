package dev.vitruvius.api;

import fi.dy.masa.malilib.gui.widgets.WidgetListEntryBase;

import java.util.List;
import java.util.Locale;

/**
 * One row of another mod in the placement list. It can sit in folders, be colored, multi-selected and dragged like a
 * placement row. Folder data is stored under id() in config/vitruvius/folders.json.
 */
public interface ListEntry {
    /** Stable and unique, "modid:...". */
    String id();

    /** Used for sorting (within a folder) and search. */
    String name();

    /**
     * The row widget. x and width are already adjusted for the folder indentation. Use VitruviusApi.colorOf(id())
     * to tint the row like placement rows are tinted.
     */
    WidgetListEntryBase<?> createWidget(int x, int y, int width, int height, boolean isOdd, int listIndex);

    /** The texts the search bar matches against (lower case). */
    default List<String> searchStrings() {
        return List.of(name().toLowerCase(Locale.ROOT));
    }

    /** Folder hide/show uses these two, like setRenderSchematic on a placement. */
    boolean isRenderingEnabled();

    void setRenderingEnabled(boolean enabled);

    /**
     * The entry's terrain cutaway ("slice") state as a short label such as "ON", "WIREFRAME" or "OFF", or null when it
     * has none. The editor shows it as a button on the row.
     */
    default String cutawayLabel() {
        return null;
    }

    /** Switches to the next cutaway state; called when the editor's cutaway button is clicked. */
    default void cycleCutaway() {}

    /** False when the editor already shows this entry as a scene object, so the read-only 1.x row is left out there. */
    default boolean shownInEditor() {
        return true;
    }
}
