package dev.schemarray.editor.ops;

import dev.schemarray.editor.io.ProjectLayout;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * The real {@link FileSystemPort}: plain java.nio below one root folder. Every path goes through
 * {@link ProjectLayout#resolveInside}, so an absolute path or one with {@code ..} is refused with an
 * {@link IllegalArgumentException} before anything is touched.
 */
public final class NioFileSystemPort implements FileSystemPort {
    private final Path root;

    public NioFileSystemPort(Path root) {
        this.root = root.toAbsolutePath().normalize();
    }

    public Path root() {
        return root;
    }

    private static String slashes(String path) {
        return path.replace('\\', '/');
    }

    private Path at(String path) {
        return ProjectLayout.resolveInside(root, slashes(path));
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
        String trash = FileSystemPort.join(FileSystemPort.trashDirOf(rel), UUID.randomUUID() + "-" + p.getFileName());
        Path t = at(trash);
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
            s.forEach(p -> out.add(slashes(root.relativize(p).toString())));
        }
        Collections.sort(out);
        return out;
    }
}
