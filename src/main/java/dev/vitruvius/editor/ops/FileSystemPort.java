package dev.vitruvius.editor.ops;

import dev.vitruvius.editor.io.ProjectLayout;

import java.io.IOException;
import java.util.List;

/**
 * The file side of the edit commands. Paths are strings relative to the port's root (the schematics folder),
 * with '/' separators, e.g. {@code Projects/Metro/files/a.litematic}. Nothing is ever hard-deleted: removing a file
 * means {@link #moveToTrash}, and the token it returns brings the file back.
 *
 * <p>{@link #copy}, {@link #move} and {@link #moveToTrash} create missing folders at the target and refuse to
 * overwrite an existing file (they throw {@link java.nio.file.FileAlreadyExistsException}).
 */
public interface FileSystemPort {
    /** Whether a file or folder exists there. */
    boolean exists(String path);

    void copy(String from, String to) throws IOException;

    void move(String from, String to) throws IOException;

    /**
     * Moves the file to the {@code .trash} folder of its project ({@code Projects/<name>/.trash}, else the root's
     * {@code .trash}) under a fresh name and returns a token for {@link #restoreFromTrash}.
     */
    String moveToTrash(String path) throws IOException;

    /** Puts a trashed file back where it was; fails if something is there now. */
    void restoreFromTrash(String token) throws IOException;

    /** Writes (creates or replaces) a text file as UTF-8. */
    void writeString(String path, String text) throws IOException;

    String readString(String path) throws IOException;

    /** The direct children of a folder as full relative paths, sorted; empty when it does not exist. */
    List<String> list(String dir) throws IOException;

    // ---- shared path helpers ----

    /** {@code Projects/<name>/.trash} for a path inside a project folder, else {@code .trash}. */
    static String trashDirOf(String path) {
        String[] seg = path.split("/");
        for (int i = 0; i + 2 < seg.length; i++) {
            if (seg[i].equals(ProjectLayout.PROJECTS_DIR)) {
                return seg[i] + "/" + seg[i + 1] + "/" + ProjectLayout.TRASH_DIR;
            }
        }
        return ProjectLayout.TRASH_DIR;
    }

    /** A token is {@code trashPath|originalPath} (a pipe cannot occur in a file name). */
    static String token(String trashPath, String original) {
        return trashPath + "|" + original;
    }

    static String[] parseToken(String token) throws IOException {
        int i = token.indexOf('|');
        if (i <= 0 || i == token.length() - 1) throw new IOException("bad trash token: " + token);
        return new String[] {token.substring(0, i), token.substring(i + 1)};
    }

    static String join(String dir, String name) {
        if (dir == null || dir.isEmpty()) return name;
        if (name == null || name.isEmpty()) return dir;
        return dir.endsWith("/") ? dir + name : dir + "/" + name;
    }

    static String nameOf(String path) {
        int i = path.lastIndexOf('/');
        return i < 0 ? path : path.substring(i + 1);
    }

    /** The folder part of a path, "" when there is none. */
    static String dirOf(String path) {
        int i = path.lastIndexOf('/');
        return i < 0 ? "" : path.substring(0, i);
    }
}
