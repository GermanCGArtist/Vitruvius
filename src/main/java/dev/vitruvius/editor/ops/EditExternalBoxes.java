package dev.vitruvius.editor.ops;

import com.google.gson.JsonObject;
import dev.vitruvius.editor.core.ExternalNode;
import dev.vitruvius.editor.core.Node;
import dev.vitruvius.editor.core.Transform;
import dev.vitruvius.editor.core.Vec3i;

import java.util.UUID;

/**
 * An edit-mode change of an external node (a side dragged, a box added or deleted): the new data, and the shift of the
 * local origin the object type reports (see {@code SceneObjectType.EditResult}), which moves the node the opposite way
 * so that every block that was not edited stays where it is in the world.
 */
public final class EditExternalBoxes extends BaseCommand {
    private final UUID id;
    private final JsonObject data;
    private final Vec3i shift;
    private final String what;

    public EditExternalBoxes(String verb, UUID id, JsonObject data, Vec3i shift) {
        super(verb);
        this.what = verb;
        this.id = id;
        this.data = data.deepCopy();
        this.shift = shift == null ? Vec3i.ZERO : shift;
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
        Transform t = x.transform();
        x.setTransform(t.withPos(t.pos().add(t.applyVector(shift))));
        setLabel(what + " " + x.name());
    }

}
