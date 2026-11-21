package dev.vitruvius.api;

import java.util.List;

/**
 * Another way to turn the Shape generator's shape into something, next to the schematic (Heavymatica: a heavymatic).
 * The editor's Shape generator window lists every output in its Output drop-down. While one is chosen the window
 * calls {@link #preview} whenever the shape, its position or the option changes, so the result can be shown live in
 * the world, and {@link #create} when the user clicks the create button. Register with
 * {@link VitruviusApi#registerShapeOutput}. Client thread only.
 */
public interface ShapeOutput {
    /** The name in the Output drop-down and on the create button, e.g. "Heavymatic". */
    String label();

    /** Hover text of the create button, or null. */
    default String hover() {
        return null;
    }

    /** The choices of the drop-down next to it (Heavymatica: the roles); one entry at least. */
    List<String> options();

    /**
     * Shows the shape live (the ghost of this output). {@code ctx == null} removes the ghost. Returns a short status
     * line for the window ("12 boxes", or why nothing can be made), or null.
     */
    String preview(ShapeActionContext ctx, int option);

    /** Whether the result can go into a list folder; the window then shows a folder picker and passes its choice to create. */
    default boolean usesListFolders() {
        return false;
    }

    /** Like create, with the list folder id chosen in the window (null = top level). */
    default String create(ShapeActionContext ctx, int option, String folderId) {
        return create(ctx, option);
    }

    /** Makes the real thing from the shape and removes the ghost. Returns an error message, or null on success. */
    String create(ShapeActionContext ctx, int option);
}
