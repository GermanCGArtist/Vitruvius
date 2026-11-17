package dev.vitruvius.editor.ops;

import dev.vitruvius.editor.core.Node;
import dev.vitruvius.editor.core.Transform;

import java.util.Objects;
import java.util.UUID;

/** Sets one node's local transform (typed values, scrubbing a field). Repeats on the same node merge. */
public final class SetTransform extends BaseCommand {
    private final UUID id;
    private Transform target;

    public SetTransform(UUID id, Transform target) {
        super("Set transform");
        this.id = id;
        this.target = Objects.requireNonNull(target);
    }

    @Override
    protected Refusal refusal(EditContext c) {
        Node n = c.scene().byId(id);
        if (n == null) return new Refusal("The item no longer exists");
        if (!Nodes.hasTransform(n)) return new Refusal(Nodes.q(n) + " has no transform");
        return Nodes.readOnly(n);
    }

    @Override
    protected void doApply() {
        Node n = ctx.scene().byId(id);
        setLabel("Transform " + n.name());
        remember(n);
        Nodes.setTransform(n, target);
    }

    @Override
    public boolean mergeWith(Command other) {
        if (!(other instanceof SetTransform s) || !id.equals(s.id)) return false;
        target = s.target;
        return true;
    }
}
