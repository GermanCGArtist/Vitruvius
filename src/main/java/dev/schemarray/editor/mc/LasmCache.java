package dev.schemarray.editor.mc;

import dev.schemarray.editor.core.Node;
import dev.schemarray.editor.io.AssemblyIo;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reads {@code .lasm} files for linked assemblies and keeps the parts until the file changes (by modification time).
 * The parts are shared by every instance that links the file, so they are read only; instances tell their
 * placements apart by an instance-scoped id (see the reconciler). A file that cannot be read gives null, and is
 * reported once until it changes.
 */
public final class LasmCache {
    private record Entry(long mtime, long size, List<Node> parts) {}

    private final Map<Path, Entry> cache = new HashMap<>();
    private final Set<Path> reported = new HashSet<>();

    /** The parts of this .lasm, or null if it is missing or unreadable. */
    public List<Node> parts(Path file) {
        long mtime, size;
        try {
            mtime = Files.getLastModifiedTime(file).toMillis();
            size = Files.size(file);
        } catch (IOException e) {
            cache.remove(file);
            report(file, "cannot read " + file + ": " + e.getMessage());
            return null;
        }
        Entry e = cache.get(file);
        if (e != null && e.mtime == mtime && e.size == size) return e.parts;
        try {
            List<Node> parts = List.copyOf(AssemblyIo.read(file).nodes);
            cache.put(file, new Entry(mtime, size, parts));
            reported.remove(file);
            return parts;
        } catch (IOException | RuntimeException ex) {
            cache.remove(file);
            report(file, "cannot read " + file + ": " + ex.getMessage());
            return null;
        }
    }

    public void clear() {
        cache.clear();
        reported.clear();
    }

    private void report(Path file, String msg) {
        if (reported.add(file)) System.out.println("[SchematicArray] " + msg);
    }
}
