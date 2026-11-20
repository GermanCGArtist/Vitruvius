package dev.vitruvius.api;

import com.google.gson.JsonObject;
import dev.vitruvius.editor.core.Box;
import dev.vitruvius.editor.core.Transform;
import dev.vitruvius.editor.core.Vec3i;

import java.util.List;

/**
 * A kind of scene object another mod owns (Heavymatica areas, for example). The editor stores it as an external node
 * in a project, an assembly or Loose: it keeps a transform and the opaque {@code data} JSON, and asks this type for
 * everything else. External nodes move, rotate, array, hide and undo like objects, but never trim blocks, take part
 * in a Boolean or in the front order.
 *
 * <p>Register one instance with {@link VitruviusApi#registerObjectType} (client thread, at startup). A project
 * that holds a type nobody registered still loads: the node shows grey as "missing mod" and keeps its data.
 */
public interface SceneObjectType {
    /** Stable and unique, "modid:name", e.g. "heavymatica:area". It is saved in project files. */
    String id();

    /** The name in the Add menu. */
    String displayName();

    /** A new object's default data. */
    JsonObject create();

    /** The local bounds in blocks (the box before the node's transform applies). It must have volume. */
    Box bounds(JsonObject data);

    /**
     * Draws the object in the world. The editor calls this every frame for every visible object of this type; draw
     * with {@code d}. {@code world} is the node's world transform (and each array copy's); the object's box in the
     * world is {@code bounds(data).transformed(world)}.
     */
    void renderOutline(JsonObject data, Transform world, OverlayDrawer d, boolean selected);

    /**
     * Draws your fields in the Properties window with the widgets of {@code ui}. {@code data} is a working copy:
     * change it freely; when {@code ui.changed()} is true afterwards the editor stores the new data as one undo step.
     */
    void drawProperties(JsonObject data, PropertyUi ui);

    /** Heavymatica: false. Reserved; an external node never changes schematic blocks either way. */
    default boolean affectsBlocks() {
        return false;
    }

    /**
     * Called when the user deletes an object of this type (also when it goes with a folder, and again on Redo). Undo
     * puts the object back without calling anything, so keep what you need to restore it. Closing a project does not
     * call this: the objects are only put away.
     */
    default void onRemoved(JsonObject data) {}

    // ---- edit mode (optional): the editor lets the user drag the sides of the object's boxes ----

    /**
     * What an edit returns: the new data, and {@code shift}, how far the type moved its local origin. Every local
     * coordinate afterwards is the old one minus {@code shift} (a type that keeps its bounds starting at 0,0,0 gets a
     * shift when a box grows toward negative coordinates). The editor moves the node by the same amount, so nothing
     * else in the world moves. Use {@link Vec3i#ZERO} when the origin stays.
     */
    record EditResult(JsonObject data, Vec3i shift) {}

    /**
     * The boxes the user can drag in edit mode (E), in the object's local blocks, in a stable order (the index is what
     * the other edit methods get). An empty list (the default) means the type has no edit mode.
     */
    default List<Box> editBoxes(JsonObject data) {
        return List.of();
    }

    /** The color (0xRRGGBB) of the handles of box {@code index}. */
    default int editColor(JsonObject data, int index) {
        return 0xf2b35e;
    }

    /**
     * The user dragged a side of box {@code index}: it is {@code box} now (local blocks, at least 1 x 1 x 1). Return
     * the new data, or null to refuse. Do not change {@code data}; return a copy.
     */
    default EditResult resizeBox(JsonObject data, int index, Box box) {
        return null;
    }

    /**
     * The user pulled a new box out of a side of box {@code from} (Shift + drag): add {@code box} (local blocks) and
     * return the new data, or null to refuse. Do not change {@code data}; return a copy.
     */
    default EditResult addBox(JsonObject data, int from, Box box) {
        return null;
    }

    /** The user deleted box {@code index}. Return the new data, or null to refuse (for example for the last box). */
    default EditResult removeBox(JsonObject data, int index) {
        return null;
    }
}
