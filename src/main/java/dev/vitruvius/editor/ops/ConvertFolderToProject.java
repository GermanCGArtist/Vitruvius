package dev.vitruvius.editor.ops;

import dev.vitruvius.editor.core.FolderNode;
import dev.vitruvius.editor.core.Ids;
import dev.vitruvius.editor.core.LooseNode;
import dev.vitruvius.editor.core.Node;
import dev.vitruvius.editor.core.ProjectNode;
import dev.vitruvius.editor.core.Vec3i;
import dev.vitruvius.editor.io.ProjectIo;
import dev.vitruvius.editor.io.ProjectLayout;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Convert to project: a folder (or Loose) becomes a new project holding its content. The files its objects use go
 * into the new project's {@code files/} (moved, or copied when something else still uses them, as in
 * {@link MoveToProject}), {@code project.vproj} is written, and the project is opened. A folder disappears; Loose stays
 * and is empty afterwards. The project gets the world of where the content was (the Loose world for Loose) and the
 * origin 0,0,0. Undo undoes all of it.
 */
public final class ConvertFolderToProject extends BaseCommand {
    private final UUID nodeId;
    private final String name;
    private final UUID projectId = Ids.newId();
    private String path;

    /** @param name the project name, or null for the folder's name */
    public ConvertFolderToProject(UUID nodeId, String name) {
        super("Convert to project");
        this.nodeId = nodeId;
        this.name = name;
    }

    public UUID projectId() {
        return projectId;
    }

    @Override
    protected Refusal refusal(EditContext c) {
        Node n = c.scene().byId(nodeId);
        if (!(n instanceof FolderNode || n instanceof LooseNode)) return new Refusal("Only a folder or Loose can be converted to a project");
        if (n.children().isEmpty()) return new Refusal(Nodes.q(n) + " is empty");
        if (Nodes.readOnly(n) != null) return Nodes.readOnly(n);
        if (name != null && name.isBlank()) return new Refusal("A project needs a name");
        return FileTransfer.problem(c, new ArrayList<>(n.children()));
    }

    @Override
    protected void doApply() {
        Node n = ctx.scene().byId(nodeId);
        String projectName = name != null ? name.strip() : n instanceof LooseNode ? "Loose placements" : n.name();
        rememberScene();
        path = NewProject.freeProjectPath(ctx, projectName, path);
        ProjectNode source = ctx.scene().projectOf(n);
        ProjectNode p = new ProjectNode(projectId, projectName);
        p.setPath(path);
        p.setWorld(ctx.worldOf(n));
        p.setDim(source != null ? source.dim() : "minecraft:overworld");
        p.setOrigin(Vec3i.ZERO);
        setLabel("Convert " + n.name() + " to project");
        List<Node> content = new ArrayList<>(n.children());
        FileTransfer.run(ctx, journal(), content, path, this::remember);
        Node parent = n.parent();
        remember(n);
        Nodes.reattach(ctx.scene(), content, p, -1, x -> {
            if (x != p) remember(x);
        });
        if (n instanceof FolderNode) {
            remember(parent);
            parent.removeChild(n);
        }
        ctx.scene().openProject(p);
        journal().create(FileSystemPort.join(path, ProjectLayout.PROJECT_FILE), ProjectIo.toJson(p));
        touch(p);
    }
}
