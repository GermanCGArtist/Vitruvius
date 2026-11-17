package dev.schemarray.editor.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.schemarray.editor.core.AssemblyNode;
import dev.schemarray.editor.core.ExternalNode;
import dev.schemarray.editor.core.FolderNode;
import dev.schemarray.editor.core.Node;
import dev.schemarray.editor.core.ObjectNode;
import dev.schemarray.editor.core.ProjectNode;
import dev.schemarray.editor.core.Scene;
import dev.schemarray.editor.core.Transform;
import dev.schemarray.editor.core.Vec3i;
import dev.schemarray.editor.io.FormatException;
import dev.schemarray.editor.io.ProjectIo;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Test fixture: a scene with one project "Metro" (world "w"), an in-memory file system and a recording sink. */
final class Fx {
    static final class Sink implements ChangeSink {
        final Set<UUID> dirty = new LinkedHashSet<>();
        final Set<UUID> affected = new LinkedHashSet<>();

        @Override
        public void markDirty(UUID projectId) {
            dirty.add(projectId);
        }

        @Override
        public void affected(Collection<UUID> nodeIds) {
            affected.addAll(nodeIds);
        }

        void clear() {
            dirty.clear();
            affected.clear();
        }
    }

    final Scene scene = new Scene();
    final MemoryFileSystemPort fs = new MemoryFileSystemPort();
    final Sink sink = new Sink();
    final EditContext ctx = new EditContext(scene, fs, sink);
    final UndoStack stack = new UndoStack(ctx);
    final ProjectNode p;

    Fx() {
        p = project("Metro", "w");
    }

    ProjectNode project(String name, String world) {
        ProjectNode n = new ProjectNode(name);
        n.setPath("Projects/" + name);
        n.setWorld(world);
        scene.openProject(n);
        fs.put("Projects/" + name + "/project.json", "{}");
        return n;
    }

    static Transform at(int x, int y, int z) {
        return Transform.IDENTITY.withPos(new Vec3i(x, y, z));
    }

    ObjectNode obj(Node parent, String name, int x, int y, int z) {
        ObjectNode o = new ObjectNode(name, "files/" + name + ".litematic");
        o.setTransform(at(x, y, z));
        parent.addChild(o);
        fs.put(ctx.path(o, o.file()), "data of " + name);
        return o;
    }

    FolderNode folder(Node parent, String name) {
        FolderNode f = new FolderNode(name);
        parent.addChild(f);
        return f;
    }

    AssemblyNode asm(Node parent, String name, int x, int y, int z) {
        AssemblyNode a = new AssemblyNode(name);
        a.setTransform(at(x, y, z));
        parent.addChild(a);
        return a;
    }

    ExternalNode ext(Node parent, String name, int x, int y, int z) {
        ExternalNode e = new ExternalNode(name, "heavymatica:area");
        e.setTransform(at(x, y, z));
        parent.addChild(e);
        return e;
    }

    Node find(String name) {
        for (Node n : scene.allNodes()) if (n.name().equals(name)) return n;
        return null;
    }

    Vec3i pos(Node n) {
        return n.localTransform().pos();
    }

    /** A node's world origin. */
    Vec3i world(Node n) {
        return scene.worldTransform(n).pos();
    }

    static List<UUID> ids(Node... nodes) {
        List<UUID> out = new ArrayList<>();
        for (Node n : nodes) out.add(n.id());
        return out;
    }

    // ---- state comparison ----

    static void dump(Node n, StringBuilder sb, int depth) {
        sb.append("  ".repeat(depth)).append(n.getClass().getSimpleName()).append(' ').append(n.id()).append(" '").append(n.name())
                .append("' hidden=").append(n.hidden()).append(" extra=").append(n.extra());
        if (n instanceof ObjectNode o) {
            sb.append(" file=").append(o.file()).append(" t=").append(o.transform()).append(" front=").append(o.front()).append(" wire=").append(o.wire())
                    .append(" mods=").append(o.mods()).append(" placements=").append(o.placementIds());
        } else if (n instanceof AssemblyNode a) {
            sb.append(" t=").append(a.transform()).append(" front=").append(a.front()).append(" mods=").append(a.mods()).append(" src=").append(a.source());
        } else if (n instanceof ExternalNode e) {
            sb.append(" t=").append(e.transform()).append(" data=").append(e.data());
        } else if (n instanceof FolderNode f) {
            sb.append(" open=").append(f.open());
        } else if (n instanceof ProjectNode pr) {
            sb.append(" world=").append(pr.world()).append(" dim=").append(pr.dim()).append(" path=").append(pr.path()).append(" origin=").append(pr.origin());
        }
        sb.append('\n');
        for (Node c : n.children()) dump(c, sb, depth + 1);
    }

    /** The scene and the files (not the trash) as text. */
    String state() {
        StringBuilder sb = new StringBuilder();
        for (Node r : scene.roots()) dump(r, sb, 0);
        sb.append("active=").append(scene.activeProject() == null ? null : scene.activeProject().id()).append('\n');
        for (String f : fs.allFiles()) {
            if (f.startsWith(".trash/") || f.contains("/.trash/")) continue;
            sb.append(f).append(" = ").append(new String(fs.bytes(f))).append('\n');
        }
        return sb.toString();
    }

    List<ProjectNode> cloneProjects() {
        List<ProjectNode> out = new ArrayList<>();
        try {
            for (ProjectNode pr : scene.projects()) out.add(ProjectIo.fromJson(ProjectIo.toJson(pr)));
        } catch (FormatException e) {
            throw new IllegalStateException(e);
        }
        return out;
    }

    void assertSameAs(List<ProjectNode> clones) {
        assertEquals(clones.size(), scene.projects().size());
        for (int i = 0; i < clones.size(); i++) {
            assertTrue(clones.get(i).structurallyEquals(scene.projects().get(i)), "project " + clones.get(i).name() + " differs from before");
        }
    }

    /**
     * Runs the command, checks that undo gives back exactly the earlier state (as text, and with
     * {@code structurallyEquals} on the projects) and redo the same result again. Ends applied.
     */
    void roundTrip(Command c) {
        String before = state();
        List<ProjectNode> clones = cloneProjects();
        var refused = stack.execute(c);
        assertTrue(refused.isEmpty(), "refused: " + refused);
        String after = state();
        assertNotEquals(before, after, "the command changed nothing");
        assertTrue(stack.undo());
        assertEquals(before, state(), "undo did not restore the state");
        assertSameAs(clones);
        assertTrue(stack.redo());
        assertEquals(after, state(), "redo gave a different result");
    }

    Refusal refusal(Command c) {
        String before = state();
        Refusal r = stack.execute(c).orElse(null);
        if (r != null) assertEquals(before, state(), "a refused command changed something");
        return r;
    }
}
