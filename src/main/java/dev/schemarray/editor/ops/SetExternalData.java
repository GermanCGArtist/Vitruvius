package dev.schemarray.editor.ops;

import com.google.gson.JsonObject;
import dev.schemarray.editor.core.ExternalNode;
import dev.schemarray.editor.core.Node;

import java.util.UUID;

/**
 * Replaces the data of an external node (an edit in the Properties window, made through the object type's own
 * widgets). Consecutive edits of the same node fold into one undo step.
 */
public final class SetExternalData extends BaseCommand {
    private final UUID id;
    private JsonObject data;

    public SetExternalData(UUID id, JsonObject data) {
        super("Edit");
        this.id = id;
        this.data = data.deepCopy();
    }

    @Override
    protected Refusal refusal(EditContext c) {
        Node n = c.scene().byId(id);
        if (!(n instanceof ExternalNode)) return new Refusal("The item no longer exists");
        return Nodes.readOnly(n);
    }

    @Override
    protected void doApply() {
        ExternalNode x = (ExternalNode) ctx.scene().byId(id);
        remember(x);
        x.setData(data.deepCopy());
        setLabel("Edit " + x.name());
    }

    @Override
    public boolean mergeWith(dev.schemarray.editor.ops.Command next) {
        if (next instanceof SetExternalData o && o.id.equals(id)) {
            // the earlier snapshot already holds the state before the first edit; only the final data differs
            data = o.data;
            return true;
        }
        return false;
    }
}
