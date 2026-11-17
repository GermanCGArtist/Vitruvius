package dev.vitruvius.editor.ops;

import dev.vitruvius.editor.core.Node;
import dev.vitruvius.editor.io.ProjectLayout;

import java.util.Map;

/** File effects the commands share. Every effect goes through the {@link FileJournal}, so it can be undone. */
final class FileOps {
    private FileOps() {}

    /**
     * Copies the file {@code rel} (relative to the project of {@code owner}) next to itself under a free name
     * ({@code a 2.litematic}) and returns the new relative path. The copy is made once per source: {@code cache}
     * remembers sources already copied by this command. A file that does not exist is not copied (the path is
     * returned unchanged, so the new node shares it).
     */
    static String copyBeside(EditContext ctx, FileJournal journal, Node owner, String rel, Map<String, String> cache) {
        String hit = cache.get(rel);
        if (hit != null) return hit;
        String src = ctx.path(owner, rel);
        if (rel.isEmpty() || !ctx.fs().exists(src)) {
            cache.put(rel, rel);
            return rel;
        }
        String dir = FileSystemPort.dirOf(rel);
        String name = ProjectLayout.uniqueName(FileSystemPort.nameOf(rel), n -> ctx.fs().exists(ctx.path(owner, FileSystemPort.join(dir, n))));
        String newRel = FileSystemPort.join(dir, name);
        journal.copy(src, ctx.path(owner, newRel));
        cache.put(rel, newRel);
        return newRel;
    }

    /** {@code files/<unique name>} in the folder {@code baseDir}, a free name for {@code fileName}. */
    static String freeFilesPath(EditContext ctx, String baseDir, String fileName) {
        String name = ProjectLayout.uniqueName(fileName, n -> ctx.fs().exists(FileSystemPort.join(baseDir, FileSystemPort.join(ProjectLayout.FILES_DIR, n))));
        return FileSystemPort.join(ProjectLayout.FILES_DIR, name);
    }
}
