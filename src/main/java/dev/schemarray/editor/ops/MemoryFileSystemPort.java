package dev.schemarray.editor.ops;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.NoSuchFileException;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;

/** An in-memory {@link FileSystemPort} for tests (folders exist implicitly while a file is under them). */
public class MemoryFileSystemPort implements FileSystemPort {
    private final TreeMap<String, byte[]> files = new TreeMap<>();

    private static String norm(String path) {
        if (path == null) throw new IllegalArgumentException("path");
        String p = path.replace('\\', '/');
        while (p.startsWith("./")) p = p.substring(2);
        while (p.endsWith("/")) p = p.substring(0, p.length() - 1);
        if (p.isEmpty() || p.startsWith("/") || p.matches("^[A-Za-z]:.*")) throw new IllegalArgumentException("not a relative path: " + path);
        for (String s : p.split("/")) if (s.equals("..") || s.isEmpty()) throw new IllegalArgumentException("path leaves the root: " + path);
        return p;
    }

    /** Puts a file in (test setup). */
    public void put(String path, byte[] bytes) {
        files.put(norm(path), bytes.clone());
    }

    public void put(String path, String text) {
        put(path, text.getBytes(StandardCharsets.UTF_8));
    }

    public byte[] bytes(String path) {
        byte[] b = files.get(norm(path));
        return b == null ? null : b.clone();
    }

    /** All files, sorted. */
    public List<String> allFiles() {
        return new ArrayList<>(files.keySet());
    }

    @Override
    public boolean exists(String path) {
        String p = norm(path);
        if (files.containsKey(p)) return true;
        String prefix = p + "/";
        String k = files.ceilingKey(prefix);
        return k != null && k.startsWith(prefix);
    }

    private boolean isFile(String p) {
        return files.containsKey(p);
    }

    @Override
    public void copy(String from, String to) throws IOException {
        String f = norm(from), t = norm(to);
        if (!isFile(f)) throw new NoSuchFileException(from);
        if (exists(t)) throw new FileAlreadyExistsException(to);
        files.put(t, files.get(f).clone());
    }

    @Override
    public void move(String from, String to) throws IOException {
        String f = norm(from), t = norm(to);
        if (!isFile(f)) throw new NoSuchFileException(from);
        if (exists(t)) throw new FileAlreadyExistsException(to);
        files.put(t, files.remove(f));
    }

    @Override
    public String moveToTrash(String path) throws IOException {
        String p = norm(path);
        if (!isFile(p)) throw new NoSuchFileException(path);
        String trash = FileSystemPort.join(FileSystemPort.trashDirOf(p), UUID.randomUUID() + "-" + FileSystemPort.nameOf(p));
        files.put(trash, files.remove(p));
        return FileSystemPort.token(trash, p);
    }

    @Override
    public void restoreFromTrash(String token) throws IOException {
        String[] t = FileSystemPort.parseToken(token);
        if (!isFile(t[0])) throw new NoSuchFileException(t[0]);
        if (exists(t[1])) throw new FileAlreadyExistsException(t[1]);
        files.put(t[1], files.remove(t[0]));
    }

    @Override
    public void writeString(String path, String text) {
        files.put(norm(path), text.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public String readString(String path) throws IOException {
        byte[] b = files.get(norm(path));
        if (b == null) throw new NoSuchFileException(path);
        return new String(b, StandardCharsets.UTF_8);
    }

    @Override
    public List<String> list(String dir) {
        String prefix = dir == null || dir.isEmpty() ? "" : norm(dir) + "/";
        TreeSet<String> out = new TreeSet<>();
        for (String k : files.tailMap(prefix, true).keySet()) {
            if (!k.startsWith(prefix)) break;
            String rest = k.substring(prefix.length());
            int i = rest.indexOf('/');
            out.add(prefix + (i < 0 ? rest : rest.substring(0, i)));
        }
        return new ArrayList<>(out);
    }
}
