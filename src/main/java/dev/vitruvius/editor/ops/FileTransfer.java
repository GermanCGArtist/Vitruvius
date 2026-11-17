package dev.vitruvius.editor.ops;

import dev.vitruvius.editor.core.AssemblyNode;
import dev.vitruvius.editor.core.Node;
import dev.vitruvius.editor.core.ObjectNode;
import dev.vitruvius.editor.core.Scene;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Brings the files of nodes that leave a project into the project they enter: a file nothing else in any open project
 * uses is moved, one that is still used elsewhere is copied (DESIGN-NOTES, resolved 6). The files land in the target's
 * {@code files/} under a free name, and the objects are pointed at them.
 */
final class FileTransfer {
    private FileTransfer() {}

    private static List<ObjectNode> objectsIn(List<Node> roots) {
        List<ObjectNode> out = new ArrayList<>();
        for (Node r : roots) for (Node n : r.subtree()) if (n instanceof ObjectNode o && !o.file().isEmpty()) out.add(o);
        return out;
    }

    /** Why these nodes cannot leave their project, or null: a linked assembly among them, or a file that is missing. */
    static Refusal problem(EditContext ctx, List<Node> roots) {
        for (Node r : roots) {
            for (Node n : r.subtree()) {
                if (n instanceof AssemblyNode a && a.isLinked()) {
                    return new Refusal("The linked assembly " + Nodes.q(a) + " cannot go to another project; make it local first");
                }
            }
        }
        for (ObjectNode o : objectsIn(roots)) {
            if (!ctx.fs().exists(ctx.path(o, o.file()))) return new Refusal("The file " + o.file() + " of " + Nodes.q(o) + " is missing");
        }
        return null;
    }

    /**
     * Moves or copies the files into {@code destBase/files}. Call it before the nodes are moved in the tree (the
     * source folder of each object comes from where it is now).
     */
    static void run(EditContext ctx, FileJournal journal, List<Node> roots, String destBase, Consumer<Node> remember) {
        Scene scene = ctx.scene();
        List<ObjectNode> moving = objectsIn(roots);
        java.util.Set<Node> movingNodes = Nodes.identitySet(new ArrayList<>());
        for (Node r : roots) movingNodes.addAll(r.subtree());
        Map<String, List<ObjectNode>> byFile = new LinkedHashMap<>();
        for (ObjectNode o : moving) byFile.computeIfAbsent(ctx.path(o, o.file()), k -> new ArrayList<>()).add(o);
        for (Map.Entry<String, List<ObjectNode>> e : byFile.entrySet()) {
            String src = e.getKey();
            boolean usedElsewhere = false;
            for (Node n : scene.allNodes()) {
                if (n instanceof ObjectNode o2 && !movingNodes.contains(o2) && !o2.file().isEmpty() && ctx.path(o2, o2.file()).equals(src)) {
                    usedElsewhere = true;
                    break;
                }
            }
            String rel = FileOps.freeFilesPath(ctx, destBase, FileSystemPort.nameOf(src));
            String dest = FileSystemPort.join(destBase, rel);
            if (usedElsewhere) journal.copy(src, dest);
            else journal.move(src, dest);
            for (ObjectNode o : e.getValue()) {
                remember.accept(o);
                o.setFile(rel);
            }
        }
    }
}
