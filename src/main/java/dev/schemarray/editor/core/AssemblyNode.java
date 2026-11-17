package dev.schemarray.editor.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * A group of parts (objects and sub-assemblies) that moves, arrays and Booleans as one. Its children are the parts,
 * with transforms local to the assembly. When {@code source} is a {@link Link}, the children come from the
 * {@code .lasm} and are not saved in the project; they are read-only.
 */
public final class AssemblyNode extends Node implements HasFront {
    public sealed interface Source permits Local, Link {}

    /** The parts belong to this assembly and are saved with it. */
    public record Local() implements Source {}

    /** The parts are read from this {@code .lasm} (path relative to the project folder, '/' separators). */
    public record Link(String path) implements Source {
        public Link {
            Objects.requireNonNull(path, "path");
        }
    }

    private Transform transform = Transform.IDENTITY;
    private int front;
    private List<Modifier> mods = new ArrayList<>();
    private Source source = new Local();

    public AssemblyNode(UUID id, String name) {
        super(id, name);
    }

    public AssemblyNode(String name) {
        this(Ids.newId(), name);
    }

    @Override
    public boolean isContainer() {
        return true;
    }

    public Transform transform() {
        return transform;
    }

    public void setTransform(Transform t) {
        this.transform = Objects.requireNonNull(t);
    }

    @Override
    public Transform localTransform() {
        return transform;
    }

    @Override
    public int front() {
        return front;
    }

    @Override
    public void setFront(int front) {
        this.front = front;
    }

    public List<Modifier> mods() {
        return mods;
    }

    public void setMods(List<Modifier> mods) {
        this.mods = new ArrayList<>(mods);
    }

    public Source source() {
        return source;
    }

    public void setSource(Source source) {
        this.source = Objects.requireNonNull(source);
    }

    public boolean isLinked() {
        return source instanceof Link;
    }

    @Override
    protected boolean fieldsEqual(Node other) {
        AssemblyNode o = (AssemblyNode) other;
        return transform.equals(o.transform) && front == o.front && mods.equals(o.mods) && source.equals(o.source);
    }
}
