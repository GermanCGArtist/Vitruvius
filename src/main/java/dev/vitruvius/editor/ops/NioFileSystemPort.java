package dev.vitruvius.editor.ops;

import dev.vitruvius.editor.io.ProjectLayout;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Stream;

/**
 * The real {@link FileSystemPort}: plain java.nio below one root folder, plus optional extra allowed roots (open
 * projects that live outside this profile's schematics folder). Any path outside both {@code root} and the allowed
 * extra roots is refused with an {@link IllegalArgumentException} before anything is touched.
 */
public final class NioFileSystemPort implements FileSystemPort {
    private final Path root;
    private final Supplier<? extends Collection<Path>> extraRoots;

    public NioFileSystemPort(Path root) {
        this(root, List::of);
    }

    public NioFileSystemPort(Path root, Supplier<? extends Collection<Path>> extraRoots) {
        this.root = root.toAbsolutePath().normalize();
        this.extraRoots = extraRoots == null ? List::of : extraRoots;
    }

    public Path root() {
        return root;
    }

    private static String slashes(String path) {
        return path.replace('\\', '/');
    }

    private Path matchingExtraRoot(Path candidate) {
        Collection<Path> extras = extraRoots.get();
        if (extras == null) return null;
        for (Path raw : extras) {
            if (raw == null) continue;
            Path extra = raw.toAbsolutePath().normalize();
            if (candidate.startsWith(extra) && !candidate.equals(extra)) return extra;
        }
        return null;
    }

    private Path at(String path) {
        String rel = slashes(path);
        try {
            return ProjectLayout.resolveInside(root, rel);
        } catch (IllegalArgumentException e) {
            if (rel.isEmpty()) throw e;
            Path p = Path.of(rel);
            Path candidate = (p.isAbsolute() ? p : root.resolve(rel)).toAbsolutePath().normalize();
            if (matchingExtraRoot(candidate) != null) return candidate;
            throw e;
        }
    }

    private String toPortPath(Path abs) {
        Path norm = abs.toAbsolutePath().normalize();
        try {
            return slashes(root.relativize(norm).toString());
        } catch (IllegalArgumentException e) {
            return slashes(norm.toString());
        }
    }

    private static void makeParent(Path p) throws IOException {
        Path d = p.getParent();
        if (d != null) Files.createDirectories(d);
    }

    @Override
    public boolean exists(String path) {
        return Files.exists(at(path));
    }

    @Override
    public void copy(String from, String to) throws IOException {
        Path f = at(from), t = at(to);
        makeParent(t);
        Files.copy(f, t);
    }

    @Override
    public void move(String from, String to) throws IOException {
        Path f = at(from), t = at(to);
        makeParent(t);
        Files.move(f, t);
    }

    @Override
    public String moveToTrash(String path) throws IOException {
        String rel = slashes(path);
        Path p = at(rel);
        if (!Files.exists(p)) throw new NoSuchFileException(path);
        Path extra = p.startsWith(root) ? null : matchingExtraRoot(p);
        String trash;
        Path t;
        if (extra != null) {
            t = extra.resolve(ProjectLayout.TRASH_DIR).resolve(UUID.randomUUID() + "-" + p.getFileName()).normalize();
            trash = toPortPath(t);
        } else {
            trash = FileSystemPort.join(FileSystemPort.trashDirOf(rel), UUID.randomUUID() + "-" + p.getFileName());
            t = at(trash);
        }
        makeParent(t);
        Files.move(p, t);
        return FileSystemPort.token(trash, rel);
    }

    @Override
    public void restoreFromTrash(String token) throws IOException {
        String[] t = FileSystemPort.parseToken(token);
        Path from = at(t[0]), to = at(t[1]);
        makeParent(to);
        Files.move(from, to);   // fails (FileAlreadyExistsException) if something is there now
    }

    @Override
    public void writeString(String path, String text) throws IOException {
        Path p = at(path);
        makeParent(p);
        Files.writeString(p, text, StandardCharsets.UTF_8);
    }

    @Override
    public String readString(String path) throws IOException {
        return Files.readString(at(path), StandardCharsets.UTF_8);
    }

    @Override
    public List<String> list(String dir) throws IOException {
        Path d = dir == null || dir.isEmpty() ? root : at(dir);
        if (!Files.isDirectory(d)) return List.of();
        List<String> out = new ArrayList<>();
        try (Stream<Path> s = Files.list(d)) {
            s.forEach(p -> out.add(toPortPath(p)));
        }
        Collections.sort(out);
        return out;
    }
}
