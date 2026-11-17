package dev.vitruvius.editor.ops;

import dev.vitruvius.editor.core.Node;
import dev.vitruvius.editor.core.Transform;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * A change that already happened outside the editor (Litematica's own GUI or hotkeys, a Syncmatica peer) and was
 * adopted into the model, kept as an undoable entry: hand it to {@link UndoStack#record}. {@code apply} (redo) and
 * {@code revert} (undo) put the model back to the after and the before state.
 */
public final class ExternalChangeCommand implements Command {
    private final String label;
    private final Consumer<EditContext> redo;
    private final Consumer<EditContext> undo;
    private final UUID mergeId;
    private final Transform before;
    private Transform after;

    private ExternalChangeCommand(String label, Consumer<EditContext> redo, Consumer<EditContext> undo, UUID mergeId, Transform before, Transform after) {
        this.label = label;
        this.redo = redo;
        this.undo = undo;
        this.mergeId = mergeId;
        this.before = before;
        this.after = after;
    }

    /** Any change, with the two steps that set the model to the after and the before state. */
    public static ExternalChangeCommand of(String label, Consumer<EditContext> redo, Consumer<EditContext> undo) {
        return new ExternalChangeCommand(label, Objects.requireNonNull(redo), Objects.requireNonNull(undo), null, null, null);
    }

    /** A node's transform changed from {@code before} to {@code after} outside the editor. Repeats on one node merge. */
    public static ExternalChangeCommand transform(UUID nodeId, String nodeName, Transform before, Transform after) {
        return new ExternalChangeCommand("External move: " + nodeName, null, null, nodeId, before, after);
    }

    @Override
    public String label() {
        return label;
    }

    @Override
    public void apply(EditContext ctx) {
        if (mergeId != null) set(ctx, after);
        else redo.accept(ctx);
    }

    @Override
    public void revert(EditContext ctx) {
        if (mergeId != null) set(ctx, before);
        else undo.accept(ctx);
    }

    private void set(EditContext ctx, Transform t) {
        Node n = ctx.scene().byId(mergeId);
        if (n == null || !Nodes.hasTransform(n)) return;
        Nodes.setTransform(n, t);
        ctx.sink().affected(List.of(mergeId));
        Node root = Nodes.rootOf(n);
        if (root != null) ctx.sink().markDirty(root.id());
    }

    @Override
    public boolean mergeWith(Command next) {
        if (mergeId == null || !(next instanceof ExternalChangeCommand e) || !mergeId.equals(e.mergeId)) return false;
        after = e.after;
        return true;
    }
}
