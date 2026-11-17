package dev.schemarray.editor.ops;

import com.google.gson.JsonObject;
import dev.schemarray.editor.core.AssemblyNode;
import dev.schemarray.editor.core.ExternalNode;
import dev.schemarray.editor.core.Ids;
import dev.schemarray.editor.core.Node;
import dev.schemarray.editor.core.Transform;
import dev.schemarray.editor.core.Vec3i;

import java.util.UUID;

/**
 * Adds an object of a type another mod registered (Add, then the type's name): an external node at a position, with
 * the data the type created. Undo removes it again.
 */
public final class AddExternal extends BaseCommand {
    private final UUID parentId;
    private final int index;
    private final String name;
    private final String typeId;
    private final JsonObject data;
    private final Vec3i pos;
    private final UUID newId = Ids.newId();

    /** @param index position among the parent's children, negative = the end */
    public AddExternal(UUID parentId, int index, String typeId, String name, JsonObject data, Vec3i pos) {
        super("Add");
        this.parentId = parentId;
        this.index = index;
        this.typeId = typeId;
        this.name = name == null || name.isBlank() ? typeId : name.strip();
        this.data = data == null ? new JsonObject() : data.deepCopy();
        this.pos = pos == null ? Vec3i.ZERO : pos;
    }

    /** The id the new node gets (stable across redo). */
    public UUID nodeId() {
        return newId;
    }

    @Override
    protected Refusal refusal(EditContext c) {
        Node p = c.scene().byId(parentId);
        if (p == null) return new Refusal("The target no longer exists");
        if (!p.canContain(new ExternalNode(name, typeId))) return new Refusal(Nodes.q(p) + " cannot hold this");
        if (Nodes.readOnly(p) != null || (p instanceof AssemblyNode a && a.isLinked())) {
            return new Refusal(Nodes.q(p) + " is (part of) a linked assembly and read-only");
        }
        return null;
    }

    @Override
    protected void doApply() {
        Node p = ctx.scene().byId(parentId);
        remember(p);
        ExternalNode x = new ExternalNode(newId, Nodes.uniqueSiblingName(p, name), typeId);
        x.setTransform(Transform.IDENTITY.withPos(pos));
        x.setData(data.deepCopy());
        setLabel("Add " + x.name());
        p.addChild(index < 0 ? p.children().size() : Math.min(index, p.children().size()), x);
        touch(x);
    }
}
