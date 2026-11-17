package dev.vitruvius.editor.ops;

import dev.vitruvius.editor.core.AssemblyNode;
import dev.vitruvius.editor.core.FolderNode;
import dev.vitruvius.editor.core.Ids;
import dev.vitruvius.editor.core.Node;

import java.util.UUID;

/** Adds an empty folder under a project, folder or local assembly. */
public final class NewFolder extends BaseCommand {
    private final UUID parentId;
    private final String name;
    private final int index;
    private final UUID newId = Ids.newId();

    /** @param index position among the parent's children, negative = the end */
    public NewFolder(UUID parentId, String name, int index) {
        super("New folder");
        this.parentId = parentId;
        this.name = name == null || name.isBlank() ? "Folder" : name.strip();
        this.index = index;
    }

    /** The id the new folder gets (stable across redo). */
    public UUID folderId() {
        return newId;
    }

    @Override
    protected Refusal refusal(EditContext c) {
        Node p = c.scene().byId(parentId);
        if (p == null) return new Refusal("The target no longer exists");
        if (!p.canContain(new FolderNode(name))) return new Refusal(Nodes.q(p) + " cannot hold a folder");
        if (Nodes.readOnly(p) != null || (p instanceof AssemblyNode a && a.isLinked())) {
            return new Refusal(Nodes.q(p) + " is (part of) a linked assembly and read-only");
        }
        return null;
    }

    @Override
    protected void doApply() {
        Node p = ctx.scene().byId(parentId);
        remember(p);
        FolderNode f = new FolderNode(newId, Nodes.uniqueSiblingName(p, name));
        setLabel("New folder " + f.name());
        p.addChild(index < 0 ? p.children().size() : Math.min(index, p.children().size()), f);
        touch(f);
    }
}
