package dev.vitruvius.editor.ops;

import dev.vitruvius.editor.core.Node;
import dev.vitruvius.editor.core.ProjectNode;
import dev.vitruvius.editor.core.Scene;

import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/**
 * What a command works on: the scene, the file port, and the sink that hears what changed.
 *
 * <p>Paths in the model are relative to a project folder; {@link #path} turns them into port paths using
 * {@link ProjectNode#path()}, which for this layer is the project folder relative to the port root
 * (e.g. {@code Projects/Metro}). Files of Loose objects are relative to {@link #looseDir()} (the port root by default).
 */
public final class EditContext {
    private final Scene scene;
    private final FileSystemPort fs;
    private final ChangeSink sink;
    private Function<String, Collection<String>> links = p -> List.of();
    private String looseDir = "";
    private String looseWorld;
    private java.nio.file.Path portRoot;

    public EditContext(Scene scene, FileSystemPort fs, ChangeSink sink) {
        this.scene = Objects.requireNonNull(scene);
        this.fs = Objects.requireNonNull(fs);
        this.sink = sink == null ? ChangeSink.NONE : sink;
    }

    public Scene scene() {
        return scene;
    }

    public FileSystemPort fs() {
        return fs;
    }

    public ChangeSink sink() {
        return sink;
    }

    /** What a linked path itself links (for the cycle check); paths in the one normalised form the caller uses. */
    public Function<String, Collection<String>> links() {
        return links;
    }

    public EditContext setLinks(Function<String, Collection<String>> links) {
        this.links = Objects.requireNonNull(links);
        return this;
    }

    /** The folder Loose objects' files are relative to; "" is the port root. */
    public String looseDir() {
        return looseDir;
    }

    public EditContext setLooseDir(String dir) {
        this.looseDir = dir == null ? "" : dir;
        return this;
    }

    /** The world Loose objects count as in the front order; null means the active project's world. */
    public EditContext setLooseWorld(String world) {
        this.looseWorld = world;
        return this;
    }

    /** The world a node belongs to for the front order: its project's, else the Loose world. */
    public String worldOf(Node n) {
        ProjectNode p = scene.projectOf(n);
        if (p != null) return p.world();
        if (looseWorld != null) return looseWorld;
        ProjectNode a = scene.activeProject();
        return a == null ? "" : a.world();
    }

    /** The port folder this node's files are relative to: its project folder, or the Loose folder. */
    public String baseDir(Node n) {
        ProjectNode p = scene.projectOf(n);
        return p != null ? portPath(p.path()) : looseDir;
    }

    /** The port path of a file given relative to this node's project. */
    public String path(Node n, String relative) {
        return FileSystemPort.join(baseDir(n), relative);
    }

    /**
     * The folder the file port is rooted at. When set, a project path that is absolute (the project manager keeps
     * absolute paths) is turned into a path relative to it for the port.
     */
    public EditContext setPortRoot(java.nio.file.Path root) {
        this.portRoot = root == null ? null : root.toAbsolutePath().normalize();
        return this;
    }

    private String portPath(String projectPath) {
        if (portRoot == null || projectPath.isEmpty()) return projectPath;
        try {
            java.nio.file.Path abs = java.nio.file.Path.of(projectPath);
            if (!abs.isAbsolute()) return projectPath;
            return portRoot.relativize(abs.normalize()).toString().replace(java.io.File.separatorChar, '/');
        } catch (RuntimeException e) {
            return projectPath;
        }
    }
}
