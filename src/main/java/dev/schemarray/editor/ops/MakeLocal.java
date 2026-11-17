package dev.schemarray.editor.ops;

import dev.schemarray.editor.core.AssemblyNode;
import dev.schemarray.editor.core.Ids;
import dev.schemarray.editor.core.Node;
import dev.schemarray.editor.core.ObjectNode;
import dev.schemarray.editor.io.AssemblyIo;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Make Local: a linked assembly becomes a local one. The parts are read from the {@code .lasm}, get ids derived from
 * the instance ({@code Ids.derived(instanceId + ":" + partId)}, the ids a resolver gives them while linked, so two
 * instances of one {@code .lasm} never clash), and each file they use is copied into the project's {@code files/} under a free name
 * (undo moves the copies to {@code .trash}). Part files are read relative to {@code sourceBase}, by default the
 * project folder; give another folder when the {@code .lasm} belongs to a different project.
 */
public final class MakeLocal extends BaseCommand {
    private final UUID assemblyId;
    private final String sourceBase;

    public MakeLocal(UUID assemblyId) {
        this(assemblyId, null);
    }

    public MakeLocal(UUID assemblyId, String sourceBase) {
        super("Make local");
        this.assemblyId = assemblyId;
        this.sourceBase = sourceBase;
    }

    private AssemblyNode node(EditContext c) {
        return c.scene().byId(assemblyId) instanceof AssemblyNode a ? a : null;
    }

    private String base(EditContext c, AssemblyNode a) {
        return sourceBase != null ? sourceBase : LinkTargets.base(c.baseDir(a), lasmPath(c, a));
    }

    /** The port path of the linked file; null when the link leaves the schematics folder. */
    private static String lasmPath(EditContext c, AssemblyNode a) {
        String t = LinkTargets.target(c.baseDir(a), ((AssemblyNode.Link) a.source()).path());
        return t == null ? "" : t;
    }

    private AssemblyIo.Lasm read(EditContext c, AssemblyNode a) throws IOException {
        return AssemblyIo.fromJson(c.fs().readString(lasmPath(c, a)));
    }

    @Override
    protected Refusal refusal(EditContext c) {
        AssemblyNode a = node(c);
        if (a == null) return new Refusal("Only an assembly can be made local");
        if (!a.isLinked()) return new Refusal(Nodes.q(a) + " is already local");
        Refusal ro = Nodes.readOnly(a);
        if (ro != null) return ro;
        if (c.scene().projectOf(a) == null) return new Refusal(Nodes.q(a) + " is not in a project, so there is no files folder to copy into");
        String lasm = lasmPath(c, a);
        if (lasm.isEmpty()) return new Refusal("The link " + ((AssemblyNode.Link) a.source()).path() + " points outside the schematics folder");
        if (!c.fs().exists(lasm)) return new Refusal("The linked file " + lasm + " is missing");
        AssemblyIo.Lasm l;
        try {
            l = read(c, a);
        } catch (IOException e) {
            return new Refusal("Cannot read " + lasm + ": " + e.getMessage());
        }
        for (Node part : l.nodes) {
            for (Node s : part.subtree()) {
                if (s instanceof ObjectNode o && !o.file().isEmpty() && !c.fs().exists(FileSystemPort.join(base(c, a), o.file()))) {
                    return new Refusal("The file " + o.file() + " used by the assembly is missing");
                }
            }
        }
        return null;
    }

    @Override
    protected void doApply() throws IOException {
        AssemblyNode a = node(ctx);
        remember(a);
        setLabel("Make " + a.name() + " local");
        AssemblyIo.Lasm l = read(ctx, a);
        Map<UUID, UUID> ids = new HashMap<>();
        for (Node part : l.nodes) for (Node s : part.subtree()) ids.put(s.id(), Ids.derived(a.id() + ":" + s.id()));
        Map<String, String> copied = new HashMap<>();
        String dest = ctx.baseDir(a);
        String from = base(ctx, a);
        Nodes.Copier copier = new Nodes.Copier(ids, o -> {
            if (o.file().isEmpty()) return o.file();
            String hit = copied.get(o.file());
            if (hit != null) return hit;
            String rel = FileOps.freeFilesPath(ctx, dest, FileSystemPort.nameOf(o.file()));
            journal().copy(FileSystemPort.join(from, o.file()), FileSystemPort.join(dest, rel));
            copied.put(o.file(), rel);
            return rel;
        }, false, new HashMap<>(), this::remember);
        List<Node> parts = new ArrayList<>();
        for (Node part : l.nodes) {
            Node copy = copier.copy(part);
            copier.remapBooleans(copy);
            parts.add(copy);
        }
        String destDir = LinkTargets.normalize(dest);
        if (destDir != null && !destDir.equals(LinkTargets.normalize(from))) {
            // links inside the .lasm were relative to its base: point them at the same files from this project
            for (Node p : parts) {
                for (Node s : p.subtree()) {
                    if (s instanceof AssemblyNode na && na.isLinked()) {
                        String t = LinkTargets.target(from, ((AssemblyNode.Link) na.source()).path());
                        if (t != null) na.setSource(new AssemblyNode.Link(LinkTargets.relative(destDir, t)));
                    }
                }
            }
        }
        a.clearChildren();
        for (Node p : parts) a.addChild(p);
        a.setSource(new AssemblyNode.Local());
        touch(a);
    }
}
