package dev.vitruvius.editor.ops;

import dev.vitruvius.editor.core.LooseNode;
import dev.vitruvius.editor.core.Node;

import java.util.UUID;

/** Renames a node. Typing in the name field makes many of these; they merge into one step. */
public final class Rename extends BaseCommand {
    private final UUID id;
    private String initialName;
    private String name;

    public Rename(UUID id, String name) {
        super("Rename");
        this.id = id;
        this.name = name == null ? "" : name.strip();
    }

    @Override
    protected Refusal refusal(EditContext c) {
        Node n = c.scene().byId(id);
        if (n == null) return new Refusal("The item no longer exists");
        if (n instanceof LooseNode) return new Refusal("Loose placements cannot be renamed");
        if (name.isEmpty()) return new Refusal("A name cannot be empty");
        return Nodes.readOnly(n);
    }

    @Override
    protected void doApply() {
        Node n = ctx.scene().byId(id);
        initialName = n.name();
        setLabel("Rename " + initialName + " to " + name);
        remember(n);
        n.setName(name);
    }

    @Override
    public boolean mergeWith(Command other) {
        if (!(other instanceof Rename r) || !id.equals(r.id)) return false;
        name = r.name;
        if (initialName != null) setLabel("Rename " + initialName + " to " + name);
        return true;
    }
}
