package dev.vitruvius.editor.sync;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.vitruvius.editor.core.ProjectNode;
import dev.vitruvius.editor.io.ProjectIo;
import dev.vitruvius.editor.io.ProjectLayout;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Stream;

/**
 * The parts of sharing a project on a Liskov server drive that need neither Minecraft nor the network (see
 * {@link LiskovShare}). Pure, apart from reading and writing files in a project folder.
 *
 * <ul>
 *   <li>A shared project is the folder {@code Projects/<name>/} on the server drive; Liskov keys it by
 *       {@code Projects/<name>/project.vproj} and carries the whole bundle ({@code project.vproj} + {@code files/**}).
 *   <li>The <b>base</b> is the scene as this computer last agreed with the server (the version it received or pushed),
 *       kept in the project's hidden {@code .cache/share-base.json}. It is the fallback merge base, and what
 *       {@link #pending} counts against.
 *   <li>A version from another player is merged three ways ({@link ProjectMerge}): per node, so two players editing
 *       different objects both keep their work.
 * </ul>
 */
public final class ShareCore {
    private ShareCore() {}

    public static final String BASE_FILE = ".cache/share-base.json";

    /** Liskov's path of a shared project: {@code Projects/<folder>/project.vproj}. */
    public static String repoPath(String folder) {
        return ProjectLayout.PROJECTS_DIR + "/" + folder + "/" + ProjectLayout.PROJECT_FILE;
    }

    /** The project folder name of a server path {@code Projects/<name>/project.vproj}, else null. */
    public static String folderOf(String relativePath) {
        if (relativePath == null) return null;
        String r = relativePath.replace('\\', '/');
        String head = ProjectLayout.PROJECTS_DIR + "/", tail = "/" + ProjectLayout.PROJECT_FILE;
        if (!r.startsWith(head) || !r.endsWith(tail)) return null;
        String name = r.substring(head.length(), r.length() - tail.length());
        return name.isEmpty() || name.contains("/") ? null : name;
    }

    /** The repo path of a project folder below a drive root, or null when it is not {@code <root>/Projects/<name>}. */
    public static String repoPathOf(Path driveRoot, Path projectDir) {
        Path root = driveRoot.toAbsolutePath().normalize(), dir = projectDir.toAbsolutePath().normalize();
        if (!dir.startsWith(root) || dir.equals(root)) return null;
        String rel = root.relativize(dir).toString().replace(java.io.File.separatorChar, '/');
        String name = rel.startsWith(ProjectLayout.PROJECTS_DIR + "/") ? rel.substring(ProjectLayout.PROJECTS_DIR.length() + 1) : null;
        return name == null || name.isEmpty() || name.contains("/") ? null : repoPath(name);
    }

    /** The project as JSON, as it would be saved. */
    public static JsonObject json(ProjectNode p) {
        return JsonParser.parseString(ProjectIo.toJson(p)).getAsJsonObject();
    }

    public static JsonObject parse(byte[] bytes) {
        if (bytes == null) return null;
        try {
            var e = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8));
            return e.isJsonObject() ? e.getAsJsonObject() : null;
        } catch (RuntimeException ex) {
            return null;
        }
    }

    public static JsonObject read(Path file) {
        try {
            return Files.isRegularFile(file) ? parse(Files.readAllBytes(file)) : null;
        } catch (IOException e) {
            return null;
        }
    }

    public static JsonObject readBase(Path projectDir) {
        return read(projectDir.resolve(BASE_FILE));
    }

    public static void writeBase(Path projectDir, JsonObject scene) {
        Path f = projectDir.resolve(BASE_FILE);
        try {
            Files.createDirectories(f.getParent());
            Path tmp = f.resolveSibling(f.getFileName() + ".tmp");
            Files.writeString(tmp, ProjectMerge.withoutLocal(scene).toString(), StandardCharsets.UTF_8);
            Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            System.out.println("[Vitruvius] cannot write " + f + ": " + e.getMessage());
        }
    }

    /** A base for a project nothing is known about yet: the same id, no nodes (everything counts as new). */
    public static JsonObject emptyLike(JsonObject project) {
        JsonObject b = new JsonObject();
        if (project != null && project.has("id")) b.add("id", project.get("id"));
        b.add("nodes", new JsonArray());
        return b;
    }

    /** How many changes of ours the server does not have yet (0 = in sync). */
    public static int pending(JsonObject base, JsonObject ours) {
        return ProjectMerge.changes(base == null ? emptyLike(ours) : base, ours);
    }

    /**
     * The result of taking in another player's version: the merged project, how many nodes both edited, how many
     * nodes of the open project change, and whether the merge holds something of ours the server lacks (push back).
     */
    public record Merge(JsonObject project, int conflicts, int changed, boolean pushBack) {}

    /** Merges {@code theirs} into {@code ours}; {@code base} is the version theirs was made from (null: unknown). */
    public static Merge incoming(JsonObject base, JsonObject ours, JsonObject theirs) {
        JsonObject b = base == null ? emptyLike(ours) : base;
        ProjectMerge.Result r = ProjectMerge.merge(b, ours, theirs);
        return new Merge(r.project(), r.conflicts(), ProjectMerge.changes(ours, r.project()), !ProjectMerge.equivalent(r.project(), theirs));
    }

    /**
     * The files of a project folder to share or record: {@code project.vproj} and {@code files/**}, '/'-separated paths
     * relative to the folder; hidden folders and temporary files are left out.
     */
    public static Map<String, byte[]> bundle(Path dir) throws IOException {
        Map<String, byte[]> out = new LinkedHashMap<>();
        Path vproj = dir.resolve(ProjectLayout.PROJECT_FILE);
        if (Files.isRegularFile(vproj)) out.put(ProjectLayout.PROJECT_FILE, Files.readAllBytes(vproj));
        Path files = ProjectLayout.filesDir(dir);
        if (Files.isDirectory(files)) {
            try (Stream<Path> walk = Files.walk(files)) {
                for (Path p : walk.filter(Files::isRegularFile).sorted().toList()) {
                    String rel = dir.relativize(p).toString().replace(java.io.File.separatorChar, '/');
                    if (rel.contains("/.") || rel.endsWith(".tmp")) continue;
                    out.put(rel, Files.readAllBytes(p));
                }
            }
        }
        return out;
    }

    /** Writes a bundle into a folder (creating it), each file atomically. */
    public static void writeBundle(Path dir, Map<String, byte[]> files) throws IOException {
        for (var e : files.entrySet()) {
            Path target = ProjectLayout.resolveInside(dir, e.getKey());
            Files.createDirectories(target.getParent());
            Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
            Files.write(tmp, e.getValue());
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
