package dev.schemarray.editor.mc;

import dev.schemarray.editor.core.AssemblyNode;
import dev.schemarray.editor.core.Node;
import dev.schemarray.editor.io.AssemblyIo;
import dev.schemarray.editor.io.ProjectLayout;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Where a link points, and what its parts' files are relative to. No Minecraft types.
 *
 * <ul>
 *   <li>A link path is relative to the folder of the linking project (or, inside a linked file, to that file's
 *       base), with {@code /} separators; {@code ..} is allowed, so a {@code .lasm} directly in the schematics folder
 *       is {@code ../../x.lasm} and another project is {@code ../Other/project.json}.
 *   <li>The <b>base</b> of a {@code .lasm}'s parts (what their {@code file} paths are relative to) is the linking
 *       project's folder when the file is inside it, else the folder of the project that owns the file
 *       ({@code Projects/<name>}), else the folder of the file itself.
 *   <li>The <b>identity</b> used by the cycle check is the normalised absolute path of the file; a link to a
 *       {@code project.json} is identified by the project folder.
 * </ul>
 */
public final class LinkPaths {
    private LinkPaths() {}

    /** The file a link points to. */
    public static Path target(Path linkingBase, String link) {
        return linkingBase.toAbsolutePath().normalize().resolve(link).normalize();
    }

    /** The folder the parts' files of the linked file are relative to. */
    public static Path baseOf(Path linkingBase, Path target) {
        Path lb = linkingBase.toAbsolutePath().normalize(), t = target.toAbsolutePath().normalize();
        if (t.startsWith(lb)) return lb;
        return ownerBase(t);
    }

    /** The project folder that holds this file ({@code Projects/<name>}), else its own folder. */
    public static Path ownerBase(Path file) {
        Path f = file.toAbsolutePath().normalize();
        for (Path d = f.getParent(); d != null; d = d.getParent()) {
            Path up = d.getParent();
            if (up != null && up.getFileName() != null && up.getFileName().toString().equals(ProjectLayout.PROJECTS_DIR)
                    && Files.exists(d.resolve(ProjectLayout.PROJECT_FILE))) {
                return d;
            }
        }
        Path p = f.getParent();
        return p == null ? f : p;
    }

    /** The identity of a linked file for the cycle check. */
    public static String identity(Path target) {
        Path t = target.toAbsolutePath().normalize();
        if (t.getFileName() != null && t.getFileName().toString().equals(ProjectLayout.PROJECT_FILE) && t.getParent() != null) {
            return t.getParent().toString();
        }
        return t.toString();
    }

    /** The identity of a project folder. */
    public static String projectIdentity(String projectPath) {
        return Path.of(projectPath).toAbsolutePath().normalize().toString();
    }

    /** The file to read for an identity: the project folder's {@code project.json}, or the file itself. */
    public static Path fileOf(String identity) {
        Path p = Path.of(identity);
        return Files.isDirectory(p) ? p.resolve(ProjectLayout.PROJECT_FILE) : p;
    }

    /** The link path from a folder to a target, with {@code /} separators ({@code ..} where needed). */
    public static String relativeLink(Path linkingBase, Path target) {
        Path from = linkingBase.toAbsolutePath().normalize(), to = target.toAbsolutePath().normalize();
        String rel;
        try {
            rel = from.relativize(to).toString();
        } catch (IllegalArgumentException e) {
            rel = to.toString();   // another drive: keep it absolute
        }
        return rel.replace('\\', '/');
    }

    /** What an identity links, as identities (for {@code EditContext.links()}); empty for a file that cannot be read. */
    public static Collection<String> linksOf(String identity) {
        Path file = fileOf(identity);
        if (!Files.isRegularFile(file)) return List.of();
        List<String> out = new ArrayList<>();
        try {
            Path base = Files.isDirectory(Path.of(identity)) ? Path.of(identity) : ownerBase(file);
            for (Node part : AssemblyIo.read(file).nodes) {
                for (Node n : part.subtree()) {
                    if (n instanceof AssemblyNode a && a.isLinked()) {
                        out.add(identity(target(base, ((AssemblyNode.Link) a.source()).path())));
                    }
                }
            }
        } catch (IOException | RuntimeException e) {
            return List.of();
        }
        return out;
    }
}
