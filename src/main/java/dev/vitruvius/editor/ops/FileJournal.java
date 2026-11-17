package dev.vitruvius.editor.ops;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;

/**
 * The file effects of one command run, each with the step that undoes it. Nothing is deleted: undoing a created
 * file moves it to {@code .trash}.
 */
final class FileJournal {
    private interface Undo {
        void run(FileSystemPort fs) throws IOException;
    }

    private final FileSystemPort fs;
    private final List<Undo> undos = new ArrayList<>();

    FileJournal(FileSystemPort fs) {
        this.fs = fs;
    }

    void copy(String from, String to) {
        try {
            fs.copy(from, to);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        undos.add(p -> p.moveToTrash(to));
    }

    void move(String from, String to) {
        try {
            fs.move(from, to);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        undos.add(p -> p.move(to, from));
    }

    /** Writes a file that must not exist yet. */
    void create(String path, String text) {
        try {
            if (fs.exists(path)) throw new java.nio.file.FileAlreadyExistsException(path);
            fs.writeString(path, text);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        undos.add(p -> p.moveToTrash(path));
    }

    boolean isEmpty() {
        return undos.isEmpty();
    }

    /** Undoes everything done so far, newest first. Keeps going after a failure and rethrows the first one. */
    void undoAll() {
        IOException first = null;
        for (int i = undos.size() - 1; i >= 0; i--) {
            try {
                undos.get(i).run(fs);
            } catch (IOException e) {
                if (first == null) first = e;
            }
        }
        undos.clear();
        if (first != null) throw new UncheckedIOException(first);
    }
}
