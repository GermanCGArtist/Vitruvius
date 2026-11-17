package dev.vitruvius.editor.core;

import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * A node of the scene graph. Container types (project, folder, loose, assembly) hold children; the others never do.
 * A node has at most one parent, kept in step by {@link #addChild} and {@link #removeChild}.
 *
 * <p>{@code extra} holds the fields of the saved file this version does not know, so they survive a round trip.
 */
public abstract class Node {
    private final UUID id;
    private String name;
    private boolean hidden;
    private Node parent;
    private final List<Node> children = new ArrayList<>();
    private JsonObject extra = new JsonObject();

    protected Node(UUID id, String name) {
        this.id = Objects.requireNonNull(id, "id");
        this.name = name == null ? "" : name;
    }

    public UUID id() {
        return id;
    }

    public String name() {
        return name;
    }

    public void setName(String name) {
        this.name = name == null ? "" : name;
    }

    public boolean hidden() {
        return hidden;
    }

    public void setHidden(boolean hidden) {
        this.hidden = hidden;
    }

    /** The parent, or null for a top-level node (project, loose) and for a node that is not attached. */
    public Node parent() {
        return parent;
    }

    /** Fields of the saved file that this version does not know; never null. */
    public JsonObject extra() {
        return extra;
    }

    public void setExtra(JsonObject extra) {
        this.extra = extra == null ? new JsonObject() : extra;
    }

    /** Whether this type can hold children. */
    public boolean isContainer() {
        return false;
    }

    /** Whether {@code child} may sit under this node: a container, and the child is not a project or the loose root. */
    public boolean canContain(Node child) {
        return isContainer() && !(child instanceof ProjectNode) && !(child instanceof LooseNode);
    }

    /** The children as a read-only view (empty for a leaf type). */
    public List<Node> children() {
        return Collections.unmodifiableList(children);
    }

    /** This node's own transform relative to its parent; folders, projects and the loose root have none. */
    public Transform localTransform() {
        return Transform.IDENTITY;
    }

    public void addChild(Node child) {
        addChild(children.size(), child);
    }

    /**
     * Inserts {@code child} at {@code index} (counted before the child is taken out of its old parent); if it has a
     * parent already it is taken from there first.
     *
     * @throws IllegalArgumentException if this node cannot hold that child, or the child is this node or one of its
     *     ancestors (a tree cannot loop)
     */
    public void addChild(int index, Node child) {
        Objects.requireNonNull(child, "child");
        if (!canContain(child)) throw new IllegalArgumentException(getClass().getSimpleName() + " cannot contain " + child.getClass().getSimpleName());
        for (Node n = this; n != null; n = n.parent) {
            if (n == child) throw new IllegalArgumentException("cannot put " + child.name + " into itself or a descendant");
        }
        if (index < 0 || index > children.size()) throw new IndexOutOfBoundsException("index " + index);
        Node old = child.parent;
        int at = index;
        if (old != null) {
            int was = old.indexOfIdentity(child);
            if (was >= 0) old.children.remove(was);
            if (old == this && was >= 0 && was < at) at--;
            child.parent = null;
        }
        children.add(at, child);
        child.parent = this;
    }

    public boolean removeChild(Node child) {
        int i = indexOfIdentity(child);
        if (i < 0) return false;
        children.remove(i);
        child.parent = null;
        return true;
    }

    /** Removes all children (they become parentless). */
    public void clearChildren() {
        for (Node c : children) c.parent = null;
        children.clear();
    }

    private int indexOfIdentity(Node child) {
        for (int i = 0; i < children.size(); i++) if (children.get(i) == child) return i;
        return -1;
    }

    /** This node and all its descendants, depth first, parents before children. */
    public List<Node> subtree() {
        List<Node> out = new ArrayList<>();
        collect(this, out);
        return out;
    }

    private static void collect(Node n, List<Node> out) {
        out.add(n);
        for (Node c : n.children) collect(c, out);
    }

    /** Whether the type-specific fields equal those of {@code other}, which is of the same class. */
    protected abstract boolean fieldsEqual(Node other);

    /**
     * Whether {@code o} is the same subtree in every saved respect: type, id, name, hidden, type fields, unknown
     * fields, and the children in order. (Parent links are not compared.) Used by the round-trip tests.
     */
    public final boolean structurallyEquals(Node o) {
        if (o == this) return true;
        if (o == null || o.getClass() != getClass()) return false;
        if (!id.equals(o.id) || !name.equals(o.name) || hidden != o.hidden || !extra.equals(o.extra) || !fieldsEqual(o)) return false;
        if (children.size() != o.children.size()) return false;
        for (int i = 0; i < children.size(); i++) if (!children.get(i).structurallyEquals(o.children.get(i))) return false;
        return true;
    }

    @Override
    public String toString() {
        return getClass().getSimpleName() + "[" + name + " " + id + "]";
    }
}
