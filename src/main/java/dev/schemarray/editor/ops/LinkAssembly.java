package dev.schemarray.editor.ops;

import dev.schemarray.editor.core.AssemblyNode;
import dev.schemarray.editor.core.CycleCheck;
import dev.schemarray.editor.core.FrontOrder;
import dev.schemarray.editor.core.Ids;
import dev.schemarray.editor.core.Node;
import dev.schemarray.editor.core.ProjectNode;
import dev.schemarray.editor.core.Transform;
import dev.schemarray.editor.core.Vec3i;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Links a {@code .lasm} (or, with {@link LinkProject}, another project) into a project as a linked assembly: a new
 * {@link AssemblyNode} whose source is the link path. Its parts are not in the model; whoever resolves links loads
 * them.
 *
 * <p>Refused through {@code CycleCheck} when the link would loop (the target links itself, or the linked file
 * reaches the target again). {@code linkedIdentity} is the linked file in the one normalised form the caller uses
 * for {@link EditContext#links()}; the target's identity is the project folder ({@link ProjectNode#path()}). The new
 * assembly sits at {@code pos} (world) and behind everything in the front order.
 */
public class LinkAssembly extends BaseCommand {
    private final UUID parentId;
    private final int index;
    private final String name;
    private final String linkPath;
    private final String linkedIdentity;
    private final Vec3i pos;
    private final UUID newId = Ids.newId();

    /**
     * @param linkPath what is stored in the assembly ({@code files/station.lasm}, relative to the project folder)
     * @param index position among the parent's children, negative = the end
     */
    public LinkAssembly(UUID parentId, int index, String name, String linkPath, String linkedIdentity, Vec3i pos) {
        this(parentId, index, name, linkPath, linkedIdentity, pos, "Link assembly");
    }

    LinkAssembly(UUID parentId, int index, String name, String linkPath, String linkedIdentity, Vec3i pos, String verb) {
        super(verb);
        this.parentId = parentId;
        this.index = index;
        this.name = name == null || name.isBlank() ? "Assembly" : name.strip();
        this.linkPath = linkPath;
        this.linkedIdentity = linkedIdentity;
        this.pos = pos;
    }

    /** The id the new assembly gets (stable across redo). */
    public UUID assemblyId() {
        return newId;
    }

    @Override
    protected Refusal refusal(EditContext c) {
        Node parent = c.scene().byId(parentId);
        if (parent == null) return new Refusal("The target no longer exists");
        ProjectNode project = c.scene().projectOf(parent);
        if (project == null) return new Refusal("Links go into a project; Loose cannot hold a link");
        if (!parent.canContain(new AssemblyNode(name))) return new Refusal(Nodes.q(parent) + " cannot hold an assembly");
        if (Nodes.readOnly(parent) != null || (parent instanceof AssemblyNode a && a.isLinked())) {
            return new Refusal(Nodes.q(parent) + " is (part of) a linked assembly and read-only");
        }
        if (linkPath == null || linkPath.isBlank()) return new Refusal("No file to link");
        Optional<List<String>> cycle = CycleCheck.findCycle(project.path(), linkedIdentity, c.links());
        if (cycle.isPresent()) {
            return new Refusal("Linking " + linkedIdentity + " into " + Nodes.q(project) + " would make a loop: " + String.join(" -> ", cycle.get()));
        }
        return null;
    }

    @Override
    protected void doApply() {
        Node parent = ctx.scene().byId(parentId);
        remember(parent);
        AssemblyNode a = new AssemblyNode(newId, Nodes.uniqueSiblingName(parent, name));
        a.setSource(new AssemblyNode.Link(linkPath));
        Vec3i at = pos == null ? Vec3i.ZERO : pos;
        a.setTransform(Transform.IDENTITY.withPos(Nodes.inverse(ctx.scene().worldTransform(parent)).apply(at)));
        a.setFront(FrontOrder.nextBack(ctx.scene().frontOwnersInWorld(ctx.worldOf(parent))));
        setLabel("Link " + a.name());
        parent.addChild(index < 0 ? parent.children().size() : Math.min(index, parent.children().size()), a);
        touch(a);
    }
}
