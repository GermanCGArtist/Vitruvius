package dev.schemarray.editor.ops;

import dev.schemarray.editor.core.Node;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Collection;
import java.util.Optional;

/**
 * The shared machinery of the commands. A subclass says when it is refused ({@link #refusal}) and what it does
 * ({@link #doApply}); it calls {@link #remember} on every node it is about to change (fields or child list) and
 * {@link #journal()} for every file effect. Undo is then generic: the remembered nodes are put back and the file
 * effects are undone. If {@code doApply} throws, that happens at once and the error is rethrown, so a failed
 * command leaves nothing behind.
 */
abstract class BaseCommand implements Command {
    private final String verb;
    private String label;
    private Snapshot snap;
    private FileJournal journal;
    /** The context of the current apply or revert. */
    protected EditContext ctx;

    protected BaseCommand(String verb) {
        this.verb = verb;
    }

    @Override
    public String label() {
        return label != null ? label : verb;
    }

    protected final void setLabel(String label) {
        this.label = label;
    }

    /** "Move tunnel" for one node, "Move 3 objects" for several. */
    protected final void describe(Collection<? extends Node> nodes) {
        label = nodes.isEmpty() ? verb : verb + " " + Nodes.names(nodes);
    }

    @Override
    public final Optional<Refusal> check(EditContext c) {
        return Optional.ofNullable(refusal(c));
    }

    /** Why this cannot run now, or null. Must not change anything. */
    protected abstract Refusal refusal(EditContext c);

    /** Does the work. {@link #ctx} is set. */
    protected abstract void doApply() throws IOException;

    /** Called at the start of a revert, before the generic undo, for state the snapshot does not cover. */
    protected void undoExtra() {}

    protected final void remember(Node n) {
        snap.remember(n);
    }

    protected final void touch(Node n) {
        snap.touch(n);
    }

    protected final void rememberScene() {
        snap.rememberScene();
    }

    protected final FileJournal journal() {
        return journal;
    }

    @Override
    public final void apply(EditContext c) {
        Refusal r = refusal(c);
        if (r != null) throw new IllegalStateException(r.reason());
        ctx = c;
        snap = new Snapshot(c.scene());
        journal = new FileJournal(c.fs());
        try {
            doApply();
        } catch (IOException e) {
            rollback();
            throw new UncheckedIOException(e);
        } catch (RuntimeException e) {
            rollback();
            throw e;
        }
        snap.report(c.sink());
    }

    private void rollback() {
        try {
            snap.restore();
        } catch (RuntimeException ignored) {
            // best effort: the original error is the one to report
        }
        try {
            journal.undoAll();
        } catch (RuntimeException ignored) {
            // same
        }
    }

    @Override
    public final void revert(EditContext c) {
        if (snap == null) throw new IllegalStateException("revert before apply");
        ctx = c;
        undoExtra();
        snap.restore();
        journal.undoAll();
        snap.report(c.sink());
    }
}
