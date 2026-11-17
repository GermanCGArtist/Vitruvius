package dev.vitruvius.editor.ops;

import dev.vitruvius.editor.core.AssemblyNode;
import dev.vitruvius.editor.core.BooleanModifier;
import dev.vitruvius.editor.core.ExternalNode;
import dev.vitruvius.editor.core.FolderNode;
import dev.vitruvius.editor.core.Ids;
import dev.vitruvius.editor.core.LooseNode;
import dev.vitruvius.editor.core.Mirror;
import dev.vitruvius.editor.core.Modifier;
import dev.vitruvius.editor.core.Node;
import dev.vitruvius.editor.core.ObjectNode;
import dev.vitruvius.editor.core.ProjectNode;
import dev.vitruvius.editor.core.Rot;
import dev.vitruvius.editor.core.Scene;
import dev.vitruvius.editor.core.Transform;
import dev.vitruvius.editor.core.Vec3i;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;

/** Small helpers the commands share. */
final class Nodes {
    private Nodes() {}

    /** A resolved selection, or the reason it cannot be used. */
    record Pick(List<Node> nodes, Refusal refusal) {
        static Pick fail(String reason) {
            return new Pick(List.of(), new Refusal(reason));
        }
    }

    static String q(Node n) {
        return "'" + n.name() + "'";
    }

    static String names(Collection<? extends Node> nodes) {
        if (nodes.size() == 1) return nodes.iterator().next().name();
        return nodes.size() + " objects";
    }

    static boolean hasTransform(Node n) {
        return n instanceof ObjectNode || n instanceof AssemblyNode || n instanceof ExternalNode;
    }

    static void setTransform(Node n, Transform t) {
        if (n instanceof ObjectNode o) o.setTransform(t);
        else if (n instanceof AssemblyNode a) a.setTransform(t);
        else if (n instanceof ExternalNode x) x.setTransform(t);
        else throw new IllegalArgumentException(q(n) + " has no transform");
    }

    /** The live modifier list of an object or assembly, else null. */
    static List<Modifier> mods(Node n) {
        if (n instanceof ObjectNode o) return o.mods();
        if (n instanceof AssemblyNode a) return a.mods();
        if (n instanceof ExternalNode x) return x.mods();
        return null;
    }

    static void setMods(Node n, List<Modifier> mods) {
        if (n instanceof ObjectNode o) o.setMods(mods);
        else if (n instanceof AssemblyNode a) a.setMods(mods);
        else if (n instanceof ExternalNode x) x.setMods(mods);
        else throw new IllegalArgumentException(q(n) + " has no modifiers");
    }

    static void setFront(Node n, int front) {
        if (n instanceof ObjectNode o) o.setFront(front);
        else if (n instanceof AssemblyNode a) a.setFront(front);
    }

    static int front(Node n) {
        if (n instanceof ObjectNode o) return o.front();
        if (n instanceof AssemblyNode a) return a.front();
        return 0;
    }

    /**
     * The transform that undoes {@code t}: {@code inverse(t).then(t)} is the identity. Found among the eight
     * (turn, mirror) combinations, since the linear part is one of them.
     */
    static Transform inverse(Transform t) {
        Transform lin = new Transform(Vec3i.ZERO, t.rot(), t.mirror());
        for (Mirror m : new Mirror[] {Mirror.NONE, Mirror.FRONT_BACK}) {
            for (Rot r : Rot.values()) {
                Transform c = new Transform(Vec3i.ZERO, r, m);
                if (c.then(lin).equals(Transform.IDENTITY)) {
                    return new Transform(c.applyVector(t.pos().neg()), r, m).canonical();
                }
            }
        }
        throw new IllegalStateException("no inverse for " + t);
    }

    /** The nearest linked assembly strictly above the node, or null. */
    static AssemblyNode linkedAncestor(Node n) {
        for (Node x = n.parent(); x != null; x = x.parent()) {
            if (x instanceof AssemblyNode a && a.isLinked()) return a;
        }
        return null;
    }

    /** A refusal when the node is a part of a linked assembly (read-only), else null. */
    static Refusal readOnly(Node n) {
        AssemblyNode a = linkedAncestor(n);
        if (a == null) return null;
        return new Refusal(q(n) + " is part of the linked assembly " + q(a) + " and read-only here. Make the assembly local first.");
    }

    /** Whether {@code n} has an ancestor in the set (by identity). */
    private static boolean hasAncestorIn(Node n, Set<Node> set) {
        for (Node x = n.parent(); x != null; x = x.parent()) if (set.contains(x)) return true;
        return false;
    }

    /** The nodes that have no ancestor among the nodes, in their given order. */
    static List<Node> topMost(Collection<Node> nodes) {
        Set<Node> set = identitySet(nodes);
        List<Node> out = new ArrayList<>();
        for (Node n : nodes) if (!hasAncestorIn(n, set) && !out.contains(n)) out.add(n);
        return out;
    }

    static Set<Node> identitySet(Collection<Node> c) {
        Set<Node> s = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
        s.addAll(c);
        return s;
    }

    /** {@code n} if it has a transform, else the top-most nodes with a transform below it. */
    static void bearers(Node n, List<Node> out) {
        if (hasTransform(n)) {
            out.add(n);
            return;
        }
        for (Node c : n.children()) bearers(c, out);
    }

    static Pick resolve(Scene scene, Collection<UUID> ids) {
        List<Node> out = new ArrayList<>();
        for (UUID id : ids) {
            Node n = scene.byId(id);
            if (n == null) return Pick.fail("A selected item no longer exists");
            if (!out.contains(n)) out.add(n);
        }
        if (out.isEmpty()) return Pick.fail("Nothing selected");
        return new Pick(out, null);
    }

    /**
     * The nodes a transform edit acts on: no node under another selected one; a folder, project or Loose stands for
     * the nodes with a transform inside it. Parts of linked assemblies are refused.
     */
    static Pick movables(Scene scene, Collection<UUID> ids) {
        Pick p = resolve(scene, ids);
        if (p.refusal() != null) return p;
        List<Node> out = new ArrayList<>();
        for (Node n : topMost(p.nodes())) {
            List<Node> b = new ArrayList<>();
            bearers(n, b);
            for (Node x : b) if (!out.contains(x)) out.add(x);
        }
        if (out.isEmpty()) return Pick.fail("Nothing to move: the selection holds no objects");
        for (Node n : out) {
            Refusal r = readOnly(n);
            if (r != null) return new Pick(List.of(), r);
        }
        return new Pick(out, null);
    }

    /** The parent's world transform (identity above the top). */
    static Transform parentWorld(Scene scene, Node n) {
        return n.parent() == null ? Transform.IDENTITY : scene.worldTransform(n.parent());
    }

    /** The name, or {@code name 2}, {@code name 3}, ... when a child of {@code parent} has it already. */
    static String uniqueSiblingName(Node parent, String base) {
        Set<String> taken = new HashSet<>();
        if (parent != null) for (Node c : parent.children()) taken.add(c.name());
        if (!taken.contains(base)) return base;
        for (int n = 2; ; n++) {
            String c = base + " " + n;
            if (!taken.contains(c)) return c;
        }
    }

    /**
     * Moves the nodes under {@code target} at {@code index} (a position among its children before the move; negative =
     * the end), keeping their order, and keeps every node with a transform at the same place in the world by
     * rewriting its local transform. Calls {@code remember} on everything it changes.
     */
    static void reattach(Scene scene, List<Node> nodes, Node target, int index, Consumer<Node> remember) {
        List<Node> bearers = new ArrayList<>();
        for (Node n : nodes) bearers(n, bearers);
        Map<Node, Transform> before = new IdentityHashMap<>();
        for (Node b : bearers) before.put(b, scene.worldTransform(b));
        remember.accept(target);
        for (Node n : nodes) {
            if (n.parent() != null) remember.accept(n.parent());
            remember.accept(n);
        }
        int at;
        if (index < 0) {
            at = target.children().size() - countChildrenOf(target, nodes);
        } else {
            at = Math.min(index, target.children().size());
            for (Node n : nodes) if (n.parent() == target && indexOf(target, n) < index) at--;
        }
        for (Node n : nodes) if (n.parent() != null) n.parent().removeChild(n);
        at = Math.max(0, Math.min(at, target.children().size()));
        for (Node n : nodes) target.addChild(at++, n);
        for (Node b : bearers) {
            Transform w0 = before.get(b);
            if (!w0.equals(scene.worldTransform(b))) {
                remember.accept(b);
                setTransform(b, inverse(parentWorld(scene, b)).then(w0));
            }
        }
    }

    private static int countChildrenOf(Node target, List<Node> nodes) {
        int c = 0;
        for (Node n : nodes) if (n.parent() == target) c++;
        return c;
    }

    private static int indexOf(Node parent, Node child) {
        List<Node> kids = parent.children();
        for (int i = 0; i < kids.size(); i++) if (kids.get(i) == child) return i;
        return -1;
    }

    /** Position of {@code child} among {@code parent}'s children, or -1. */
    static int indexIn(Node parent, Node child) {
        return indexOf(parent, child);
    }

    /** Whether {@code maybeAncestor} is {@code n} or above it. */
    static boolean isSelfOrAncestor(Node maybeAncestor, Node n) {
        for (Node x = n; x != null; x = x.parent()) if (x == maybeAncestor) return true;
        return false;
    }

    /** The top-level node (project or Loose) above this one, or null when detached. */
    static Node rootOf(Node n) {
        Node x = n;
        while (x.parent() != null) x = x.parent();
        return x instanceof ProjectNode || x instanceof LooseNode ? x : null;
    }

    /** Deep copies of nodes with new ids; the id map is kept by the caller so a redo gives the same ids. */
    static final class Copier {
        private final Map<UUID, UUID> idMap;
        private final Function<ObjectNode, String> fileOf;
        private final boolean linkedMode;
        private final Map<UUID, UUID> groupIds;
        private final Consumer<Node> remember;

        Copier(Map<UUID, UUID> idMap, Function<ObjectNode, String> fileOf, boolean linkedMode, Map<UUID, UUID> groupIds, Consumer<Node> remember) {
            this.idMap = idMap;
            this.fileOf = fileOf;
            this.linkedMode = linkedMode;
            this.groupIds = groupIds;
            this.remember = remember;
        }

        UUID idFor(UUID original) {
            return idMap.computeIfAbsent(original, k -> Ids.newId());
        }

        Node copy(Node src) {
            Node c = copyOne(src);
            c.setHidden(src.hidden());
            c.setExtra(src.extra().deepCopy());
            if (src instanceof AssemblyNode a && !a.isLinked()) {
                for (Node child : src.children()) c.addChild(copy(child));
            } else if (src instanceof FolderNode) {
                for (Node child : src.children()) c.addChild(copy(child));
            }
            return c;
        }

        private Node copyOne(Node src) {
            UUID id = idFor(src.id());
            if (src instanceof ObjectNode o) {
                ObjectNode n = new ObjectNode(id, o.name());
                n.setFile(fileOf.apply(o));
                n.setTransform(o.transform());
                n.setFront(o.front());
                n.setMods(copyMods(o.mods()));
                n.setWire(o.wire());
                return n;
            }
            if (src instanceof AssemblyNode a) {
                AssemblyNode n = new AssemblyNode(id, a.name());
                n.setTransform(a.transform());
                n.setFront(a.front());
                n.setMods(copyMods(a.mods()));
                n.setSource(a.source());
                if (linkedMode && !a.isLinked()) {
                    var existing = a.extra().get("linkedGroup");
                    UUID gid;
                    if (existing != null && existing.isJsonPrimitive() && Ids.parseOrNull(existing.getAsString()) != null) {
                        gid = Ids.parseOrNull(existing.getAsString());
                    } else {
                        gid = groupIds.computeIfAbsent(a.id(), k -> Ids.newId());
                        remember.accept(a);
                        a.extra().addProperty("linkedGroup", gid.toString());
                    }
                }
                return n;
            }
            if (src instanceof FolderNode f) {
                FolderNode n = new FolderNode(id, f.name());
                n.setOpen(f.open());
                return n;
            }
            if (src instanceof ExternalNode x) {
                ExternalNode n = new ExternalNode(id, x.name(), x.typeId());
                n.setTransform(x.transform());
                n.setData(x.data().deepCopy());
                n.setMods(copyMods(x.mods()));
                return n;
            }
            throw new IllegalArgumentException("cannot copy " + src);
        }

        /** Points Boolean targets that were copied along at their copies. */
        void remapBooleans(Node root) {
            for (Node n : root.subtree()) {
                List<Modifier> mods = mods(n);
                if (mods == null) continue;
                for (Modifier m : mods) {
                    if (m instanceof BooleanModifier b && b.targetId != null && idMap.containsKey(b.targetId)) b.targetId = idMap.get(b.targetId);
                }
            }
        }
    }

    static List<Modifier> copyMods(List<Modifier> mods) {
        List<Modifier> out = new ArrayList<>();
        for (Modifier m : mods) out.add(m.copy());
        return out;
    }

    static Set<Node> orderedSet(Collection<Node> c) {
        return new LinkedHashSet<>(c);
    }
}
