package dev.vitruvius.editor.ops;

import dev.vitruvius.editor.core.FolderNode;
import dev.vitruvius.editor.core.LooseNode;
import dev.vitruvius.editor.core.Node;
import dev.vitruvius.editor.core.ProjectNode;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * Moves nodes to another project (the "Move to" menu, dragging between projects), into the project itself or one of
 * its folders. Each file the nodes use is moved when no other node in any open project uses it, and copied
 * otherwise (so the nodes left behind keep working); the objects point at the new file. Undo puts files and nodes
 * back (a copy goes to {@code .trash}). Nodes keep their place in the world.
 *
 * <p>Refused: a target that is not a project or a folder of one, nodes already in that project, linked assemblies
 * (their {@code .lasm} would have to travel; make them local first), a missing file.
 */
public final class MoveToProject extends BaseCommand {
    private final List<UUID> ids;
    private final UUID targetId;
    private final int index;

    /** @param targetId a project or a folder inside a project @param index position in the target, negative = the end */
    public MoveToProject(Collection<UUID> ids, UUID targetId, int index) {
        super("Move to project");
        this.ids = new ArrayList<>(ids);
        this.targetId = targetId;
        this.index = index;
    }

    @Override
    protected Refusal refusal(EditContext c) {
        Node target = c.scene().byId(targetId);
        if (target == null) return new Refusal("The target no longer exists");
        ProjectNode project = c.scene().projectOf(target);
        if (project == null || !(target instanceof ProjectNode || target instanceof FolderNode)) {
            return new Refusal("Objects can be moved into a project or one of its folders");
        }
        Nodes.Pick p = Nodes.resolve(c.scene(), ids);
        if (p.refusal() != null) return p.refusal();
        List<Node> nodes = Nodes.topMost(p.nodes());
        for (Node n : nodes) {
            if (n instanceof ProjectNode || n instanceof LooseNode) return new Refusal("A project cannot be moved into another project; link it instead");
            if (Nodes.readOnly(n) != null) return Nodes.readOnly(n);
            if (c.scene().projectOf(n) == project) return new Refusal(Nodes.q(n) + " is in " + Nodes.q(project) + " already; use Reparent");
        }
        if (index > target.children().size()) return new Refusal("There is no position " + index + " in " + Nodes.q(target));
        return FileTransfer.problem(c, nodes);
    }

    @Override
    protected void doApply() {
        Node target = ctx.scene().byId(targetId);
        ProjectNode project = ctx.scene().projectOf(target);
        List<Node> nodes = Nodes.topMost(Nodes.resolve(ctx.scene(), ids).nodes());
        setLabel("Move " + Nodes.names(nodes) + " to " + project.name());
        // the port path (relative to the schematics folder): the project manager keeps project paths absolute
        FileTransfer.run(ctx, journal(), nodes, ctx.baseDir(project), this::remember);
        Nodes.reattach(ctx.scene(), nodes, target, index, this::remember);
    }
}
