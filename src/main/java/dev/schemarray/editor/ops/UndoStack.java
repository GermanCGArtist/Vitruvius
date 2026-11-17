package dev.schemarray.editor.ops;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Undo and redo for the editor.
 *
 * <ul>
 *   <li>{@link #execute} checks a command (a refusal is returned and nothing changes), applies it and pushes it.
 *       The last {@value #LIMIT} entries are kept.</li>
 *   <li>Consecutive commands that {@link Command#mergeWith merge} become one entry, until something breaks the
 *       run: an undo or redo, a different kind of command, or {@link #breakMerge()} (call it when a drag ends).</li>
 *   <li>{@link #begin}/{@link #commit}/{@link #rollback} group everything executed in between into one entry.
 *       They nest; a rollback undoes just its own level.</li>
 *   <li>{@link #record} adds something that already happened, like an {@link ExternalChangeCommand}.</li>
 * </ul>
 * Not thread safe: use it from the client thread.
 */
public final class UndoStack {
    public static final int LIMIT = 200;

    private final EditContext ctx;
    private final List<Command> undoList = new ArrayList<>();
    private final List<Command> redoList = new ArrayList<>();
    private final List<Frame> frames = new ArrayList<>();
    private boolean mergeable;

    private static final class Frame {
        final String label;
        final List<Command> commands = new ArrayList<>();

        Frame(String label) {
            this.label = label;
        }
    }

    public UndoStack(EditContext ctx) {
        this.ctx = Objects.requireNonNull(ctx);
    }

    public EditContext context() {
        return ctx;
    }

    /** Runs a command. Returns the refusal (and changes nothing) when it cannot run. */
    public Optional<Refusal> execute(Command c) {
        Optional<Refusal> r = c.check(ctx);
        if (r.isPresent()) return r;
        c.apply(ctx);
        push(c);
        return Optional.empty();
    }

    /** Adds a command whose effect already happened (it is not applied again). */
    public void record(Command alreadyApplied) {
        push(alreadyApplied);
    }

    private void push(Command c) {
        if (!frames.isEmpty()) {
            List<Command> list = frames.get(frames.size() - 1).commands;
            if (!(!list.isEmpty() && list.get(list.size() - 1).mergeWith(c))) list.add(c);
            return;
        }
        redoList.clear();
        if (mergeable && !undoList.isEmpty() && undoList.get(undoList.size() - 1).mergeWith(c)) return;
        undoList.add(c);
        while (undoList.size() > LIMIT) undoList.remove(0);
        mergeable = true;
    }

    /** The next command that follows is never merged into the last entry. */
    public void breakMerge() {
        mergeable = false;
    }

    public boolean canUndo() {
        return !undoList.isEmpty();
    }

    public boolean canRedo() {
        return !redoList.isEmpty();
    }

    /** The label of what Undo would undo, e.g. "Move tunnel". */
    public Optional<String> undoLabel() {
        return undoList.isEmpty() ? Optional.empty() : Optional.of(undoList.get(undoList.size() - 1).label());
    }

    public Optional<String> redoLabel() {
        return redoList.isEmpty() ? Optional.empty() : Optional.of(redoList.get(redoList.size() - 1).label());
    }

    /** All undo labels, the next one to undo first. */
    public List<String> undoLabels() {
        List<String> out = new ArrayList<>();
        for (int i = undoList.size() - 1; i >= 0; i--) out.add(undoList.get(i).label());
        return out;
    }

    public List<String> redoLabels() {
        List<String> out = new ArrayList<>();
        for (int i = redoList.size() - 1; i >= 0; i--) out.add(redoList.get(i).label());
        return out;
    }

    public int undoSize() {
        return undoList.size();
    }

    /** @return false when there was nothing to undo */
    public boolean undo() {
        requireNoTransaction();
        if (undoList.isEmpty()) return false;
        Command c = undoList.get(undoList.size() - 1);
        c.revert(ctx);
        undoList.remove(undoList.size() - 1);
        redoList.add(c);
        mergeable = false;
        return true;
    }

    /** @return false when there was nothing to redo */
    public boolean redo() {
        requireNoTransaction();
        if (redoList.isEmpty()) return false;
        Command c = redoList.get(redoList.size() - 1);
        c.apply(ctx);
        redoList.remove(redoList.size() - 1);
        undoList.add(c);
        mergeable = false;
        return true;
    }

    /** Forgets all history (e.g. when the last project closes). */
    public void clear() {
        requireNoTransaction();
        undoList.clear();
        redoList.clear();
        mergeable = false;
    }

    private void requireNoTransaction() {
        if (!frames.isEmpty()) throw new IllegalStateException("a transaction is open: " + frames.get(frames.size() - 1).label);
    }

    // ---- transactions ----

    /** Starts a group: everything executed until the matching {@link #commit} is one undo entry labelled {@code label}. */
    public void begin(String label) {
        frames.add(new Frame(label));
    }

    public boolean inTransaction() {
        return !frames.isEmpty();
    }

    /** Ends the innermost group and makes it one entry (nothing is added when the group is empty). */
    public void commit() {
        Frame f = popFrame();
        if (f.commands.isEmpty()) return;
        push(new Composite(f.label, f.commands));
    }

    /** Ends the innermost group and undoes what it did; nothing is added to the history. */
    public void rollback() {
        Frame f = popFrame();
        RuntimeException first = null;
        for (int i = f.commands.size() - 1; i >= 0; i--) {
            try {
                f.commands.get(i).revert(ctx);
            } catch (RuntimeException e) {
                if (first == null) first = e;
            }
        }
        if (first != null) throw first;
    }

    private Frame popFrame() {
        if (frames.isEmpty()) throw new IllegalStateException("no transaction is open");
        return frames.remove(frames.size() - 1);
    }

    /** Several commands as one entry. */
    private static final class Composite implements Command {
        private final String label;
        private final List<Command> commands;

        Composite(String label, List<Command> commands) {
            this.label = label;
            this.commands = new ArrayList<>(commands);
        }

        @Override
        public String label() {
            return label;
        }

        @Override
        public void apply(EditContext ctx) {
            int done = 0;
            try {
                for (Command c : commands) {
                    c.apply(ctx);
                    done++;
                }
            } catch (RuntimeException e) {
                for (int i = done - 1; i >= 0; i--) {
                    try {
                        commands.get(i).revert(ctx);
                    } catch (RuntimeException ignored) {
                        // keep going; report the original failure
                    }
                }
                throw e;
            }
        }

        @Override
        public void revert(EditContext ctx) {
            for (int i = commands.size() - 1; i >= 0; i--) commands.get(i).revert(ctx);
        }
    }
}
