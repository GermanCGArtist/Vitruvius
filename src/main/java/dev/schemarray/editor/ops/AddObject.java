package dev.schemarray.editor.ops;

import dev.schemarray.editor.core.AssemblyNode;
import dev.schemarray.editor.core.Ids;
import dev.schemarray.editor.core.Node;
import dev.schemarray.editor.core.ObjectNode;
import dev.schemarray.editor.core.Transform;
import dev.schemarray.editor.core.Vec3i;

import java.io.IOException;
import java.util.UUID;

/**
 * Adds a schematic object (the Add menu). The file {@code sourcePath} (a port path, normally in Litematica's
 * schematics folder) is copied into the target project's {@code files/} under a free name; under Loose the object
 * points at the source path itself. Undo removes the node and moves the copy to {@code .trash}.
 */
public final class AddObject extends BaseCommand {
    private final UUID parentId;
    private final int index;
    private final String name;
    private final String sourcePath;
    private final Vec3i pos;
    private final UUID newId = Ids.newId();

    /** @param index position among the parent's children, negative = the end */
    public AddObject(UUID parentId, int index, String name, String sourcePath, Vec3i pos) {
        super("Add");
        this.parentId = parentId;
        this.index = index;
        this.name = name == null || name.isBlank() ? FileSystemPort.nameOf(sourcePath) : name.strip();
        this.sourcePath = sourcePath;
        this.pos = pos;
    }

    /** The id the new object gets (stable across redo). */
    public UUID objectId() {
        return newId;
    }

    @Override
    protected Refusal refusal(EditContext c) {
        Node p = c.scene().byId(parentId);
        if (p == null) return new Refusal("The target no longer exists");
        ObjectNode probe = new ObjectNode(name, sourcePath);
        if (!p.canContain(probe)) return new Refusal(Nodes.q(p) + " cannot hold an object");
        if (Nodes.readOnly(p) != null || (p instanceof AssemblyNode a && a.isLinked())) {
            return new Refusal(Nodes.q(p) + " is (part of) a linked assembly and read-only");
        }
        if (!c.fs().exists(sourcePath)) return new Refusal("The file " + sourcePath + " does not exist");
        return null;
    }

    @Override
    protected void doApply() throws IOException {
        Node p = ctx.scene().byId(parentId);
        remember(p);
        String file = sourcePath;
        if (ctx.scene().projectOf(p) != null) {
            String base = ctx.baseDir(p);
            file = FileOps.freeFilesPath(ctx, base, FileSystemPort.nameOf(sourcePath));
            journal().copy(sourcePath, FileSystemPort.join(base, file));
        }
        ObjectNode o = new ObjectNode(newId, Nodes.uniqueSiblingName(p, stripExtension(name)));
        o.setFile(file);
        o.setTransform(Transform.IDENTITY.withPos(pos));
        setLabel("Add " + o.name());
        p.addChild(index < 0 ? p.children().size() : Math.min(index, p.children().size()), o);
        touch(o);
    }

    private static String stripExtension(String n) {
        int i = n.lastIndexOf('.');
        return i > 0 ? n.substring(0, i) : n;
    }
}
