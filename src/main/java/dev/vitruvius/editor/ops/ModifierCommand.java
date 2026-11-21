package dev.vitruvius.editor.ops;

import dev.vitruvius.editor.core.ArrayCells;
import dev.vitruvius.editor.core.ArrayModifier;
import dev.vitruvius.editor.core.BooleanModifier;
import dev.vitruvius.editor.core.CycleCheck;
import dev.vitruvius.editor.core.Modifier;
import dev.vitruvius.editor.core.Node;
import dev.vitruvius.editor.core.ProjectNode;
import dev.vitruvius.editor.core.Scene;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * Base of the modifier stack edits. A subclass says how the list changes ({@link #change}) and whether the
 * positions it names exist ({@link #shape}); this class checks that the changed list is allowed (see {@link #problem})
 * on a copy before anything is touched.
 */
abstract class ModifierCommand extends BaseCommand {
    protected final UUID nodeId;

    ModifierCommand(String verb, UUID nodeId) {
        super(verb);
        this.nodeId = nodeId;
    }

    /** Null, or why the positions this command names do not fit the current list. */
    abstract Refusal shape(List<Modifier> current);

    /** Changes the list in place. */
    abstract void change(List<Modifier> mods);

    /** What the undo menu says, e.g. "Add Array to tunnel". */
    abstract String text(Node owner);

    @Override
    protected final Refusal refusal(EditContext c) {
        Node n = c.scene().byId(nodeId);
        if (n == null) return new Refusal("The item no longer exists");
        List<Modifier> mods = Nodes.mods(n);
        if (mods == null) return new Refusal(Nodes.q(n) + " cannot have modifiers");
        Refusal ro = Nodes.readOnly(n);
        if (ro != null) return ro;
        Refusal sh = shape(mods);
        if (sh != null) return sh;
        List<Modifier> sim = Nodes.copyMods(mods);
        change(sim);
        return problem(c.scene(), n, sim);
    }

    @Override
    protected final void doApply() {
        Node n = ctx.scene().byId(nodeId);
        setLabel(text(n));
        remember(n);
        change(Nodes.mods(n));
    }

    /**
     * Why this modifier list is not allowed on the node, or null: an array count below 1, more than
     * {@link ArrayCells#MAX_COPIES} copies, a Boolean whose target is the node itself, lies in another
     * project, or would make two nodes cut each other in a loop.
     */
    static Refusal problem(Scene scene, Node owner, List<Modifier> mods) {
        if (owner instanceof dev.vitruvius.editor.core.ExternalNode) {
            for (Modifier m : mods) if (!(m instanceof ArrayModifier)) return new Refusal(Nodes.q(owner) + " takes arrays only (it never changes blocks)");
        }
        if (owner instanceof dev.vitruvius.editor.core.ObjectNode o && o.wire()) {
            for (Modifier m : mods) if (m instanceof BooleanModifier) return new Refusal(Nodes.q(owner) + " is a wire object and cannot have Boolean modifiers");
        }
        List<ArrayModifier> arrays = new ArrayList<>();
        for (Modifier m : mods) {
            if (m instanceof ArrayModifier a) {
                if (a.count < 1) return new Refusal("An array needs a count of at least 1");
                arrays.add(a);
            }
        }
        long copies = ArrayCells.copyCountOf(arrays);
        if (copies > ArrayCells.MAX_COPIES) return new Refusal("That array would make " + copies + " copies; the most is " + ArrayCells.MAX_COPIES);
        ProjectNode project = scene.projectOf(owner);
        for (Modifier m : mods) {
            if (!(m instanceof BooleanModifier b) || b.targetId == null) continue;
            Node t = scene.byId(b.targetId);
            if (t == null) continue;   // deleted target: stale id, shown as "no target" by the UI; does not block other edits
            if (t == owner) return new Refusal(Nodes.q(owner) + " cannot cut itself");
            if (scene.projectOf(t) != project) return new Refusal("The Boolean target " + Nodes.q(t) + " is in another project");
            if (CycleCheck.wouldCreateCycle(owner.id().toString(), t.id().toString(), id -> booleanTargets(scene, owner, mods, id))) {
                return new Refusal(Nodes.q(t) + " is already cut by " + Nodes.q(owner) + ", so they cannot cut each other");
            }
        }
        return null;
    }

    private static Collection<String> booleanTargets(Scene scene, Node owner, List<Modifier> ownerMods, String id) {
        Node n = scene.byId(UUID.fromString(id));
        List<Modifier> mods = n == owner ? ownerMods : n == null ? null : Nodes.mods(n);
        List<String> out = new ArrayList<>();
        if (mods != null) for (Modifier m : mods) if (m instanceof BooleanModifier b && b.targetId != null) out.add(b.targetId.toString());
        return out;
    }
}
