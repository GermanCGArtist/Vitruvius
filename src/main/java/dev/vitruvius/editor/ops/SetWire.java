package dev.vitruvius.editor.ops;

import dev.vitruvius.editor.core.Node;
import dev.vitruvius.editor.core.ObjectNode;

import java.util.UUID;

/**
 * Marks an object as a wire cutter (drawn as a dotted outline, its own placement hidden, never counted or trimmed),
 * or turns it back into a normal object.
 */
public final class SetWire extends BaseCommand {
    private final UUID id;
    private final boolean wire;

    public SetWire(UUID id, boolean wire) {
        super(wire ? "Draw as wire cutter" : "Show cutter blocks");
        this.id = id;
        this.wire = wire;
    }

    @Override
    protected Refusal refusal(EditContext c) {
        Node n = c.scene().byId(id);
        if (!(n instanceof ObjectNode o)) return new Refusal("Only a schematic object can be a cutter");
        Refusal ro = Nodes.readOnly(n);
        if (ro != null) return ro;
        if (wire) {
            for (var m : o.mods()) {
                if (m instanceof dev.vitruvius.editor.core.BooleanModifier) return new Refusal(Nodes.q(n) + " has a Boolean; remove it first (a cutter cannot have one)");
            }
        }
        return null;
    }

    @Override
    protected void doApply() {
        ObjectNode o = (ObjectNode) ctx.scene().byId(id);
        remember(o);
        o.setWire(wire);
        setLabel((wire ? "Draw as wire: " : "Show blocks of ") + o.name());
    }
}
