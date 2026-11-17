package dev.vitruvius.editor.ops;

import dev.vitruvius.editor.core.ArrayModifier;
import dev.vitruvius.editor.core.Modifier;
import dev.vitruvius.editor.core.Node;

import java.util.List;
import java.util.UUID;

/** The modifier stack commands: add, remove, reorder, enable, edit. */
public final class ModifierEdits {
    private ModifierEdits() {}

    static String kind(Modifier m) {
        return m instanceof ArrayModifier ? "Array" : "Boolean";
    }

    private static Refusal noIndex(int i, int size) {
        return new Refusal("There is no modifier " + (i + 1) + " (it has " + size + ")");
    }

    /** Adds a modifier at {@code index} (negative = the end, the last to run). */
    public static final class AddModifier extends ModifierCommand {
        private final Modifier mod;
        private final int index;

        public AddModifier(UUID nodeId, Modifier mod, int index) {
            super("Add modifier", nodeId);
            this.mod = mod.copy();
            this.index = index;
        }

        @Override
        Refusal shape(List<Modifier> cur) {
            return index > cur.size() ? noIndex(index, cur.size()) : null;
        }

        @Override
        void change(List<Modifier> mods) {
            mods.add(index < 0 ? mods.size() : index, mod.copy());
        }

        @Override
        String text(Node owner) {
            return "Add " + kind(mod) + " to " + owner.name();
        }
    }

    public static final class RemoveModifier extends ModifierCommand {
        private final int index;

        public RemoveModifier(UUID nodeId, int index) {
            super("Remove modifier", nodeId);
            this.index = index;
        }

        @Override
        Refusal shape(List<Modifier> cur) {
            return index < 0 || index >= cur.size() ? noIndex(index, cur.size()) : null;
        }

        @Override
        void change(List<Modifier> mods) {
            mods.remove(index);
        }

        @Override
        String text(Node owner) {
            return "Remove modifier from " + owner.name();
        }
    }

    /** Moves the modifier at {@code from} so it ends up at {@code to} (the stack runs top to bottom). */
    public static final class ReorderModifier extends ModifierCommand {
        private final int from, to;

        public ReorderModifier(UUID nodeId, int from, int to) {
            super("Reorder modifier", nodeId);
            this.from = from;
            this.to = to;
        }

        @Override
        Refusal shape(List<Modifier> cur) {
            if (from < 0 || from >= cur.size()) return noIndex(from, cur.size());
            if (to < 0 || to >= cur.size()) return noIndex(to, cur.size());
            return null;
        }

        @Override
        void change(List<Modifier> mods) {
            mods.add(to, mods.remove(from));
        }

        @Override
        String text(Node owner) {
            return "Reorder modifiers of " + owner.name();
        }
    }

    public static final class SetModifierEnabled extends ModifierCommand {
        private final int index;
        private final boolean enabled;

        public SetModifierEnabled(UUID nodeId, int index, boolean enabled) {
            super(enabled ? "Enable modifier" : "Disable modifier", nodeId);
            this.index = index;
            this.enabled = enabled;
        }

        @Override
        Refusal shape(List<Modifier> cur) {
            return index < 0 || index >= cur.size() ? noIndex(index, cur.size()) : null;
        }

        @Override
        void change(List<Modifier> mods) {
            mods.get(index).setEnabled(enabled);
        }

        @Override
        String text(Node owner) {
            return (enabled ? "Enable" : "Disable") + " modifier of " + owner.name();
        }
    }

    /**
     * Replaces the modifier at {@code index} with an edited copy (the UI builds {@code after} from the current one and
     * changes the field). Edits of the same field of the same modifier merge into one step, so a scrub drag or a held
     * stepper button is one undo.
     */
    public static final class EditModifier extends ModifierCommand {
        private final int index;
        private final String field;
        private Modifier after;

        public EditModifier(UUID nodeId, int index, String field, Modifier after) {
            super("Edit modifier", nodeId);
            this.index = index;
            this.field = field;
            this.after = after.copy();
        }

        @Override
        Refusal shape(List<Modifier> cur) {
            if (index < 0 || index >= cur.size()) return noIndex(index, cur.size());
            if (cur.get(index).getClass() != after.getClass()) return new Refusal("The edit does not match the modifier type");
            return null;
        }

        @Override
        void change(List<Modifier> mods) {
            mods.set(index, after.copy());
        }

        @Override
        String text(Node owner) {
            return "Edit " + kind(after) + " " + field + " of " + owner.name();
        }

        @Override
        public boolean mergeWith(Command next) {
            if (!(next instanceof EditModifier e) || !nodeId.equals(e.nodeId) || index != e.index || !field.equals(e.field)) return false;
            after = e.after;
            return true;
        }
    }
}
