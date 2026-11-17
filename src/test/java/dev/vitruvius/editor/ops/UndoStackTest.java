package dev.vitruvius.editor.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vitruvius.editor.core.ObjectNode;
import dev.vitruvius.editor.core.Transform;
import dev.vitruvius.editor.core.Vec3i;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class UndoStackTest {
    private final Fx fx = new Fx();
    private final ObjectNode a = fx.obj(fx.p, "a", 0, 0, 0);
    private final ObjectNode b = fx.obj(fx.p, "b", 5, 0, 0);

    private Move move(ObjectNode n, int dx) {
        return new Move(List.of(n.id()), new Vec3i(dx, 0, 0));
    }

    @Test
    void executeUndoRedoWithLabels() {
        assertFalse(fx.stack.canUndo());
        assertFalse(fx.stack.canRedo());
        assertTrue(fx.stack.execute(move(a, 3)).isEmpty());
        assertEquals(new Vec3i(3, 0, 0), fx.pos(a));
        assertEquals(Optional.of("Move a"), fx.stack.undoLabel());
        assertTrue(fx.stack.execute(new Rename(b.id(), "bee")).isEmpty());
        assertEquals(List.of("Rename b to bee", "Move a"), fx.stack.undoLabels());

        assertTrue(fx.stack.undo());
        assertEquals("b", b.name());
        assertEquals(Optional.of("Rename b to bee"), fx.stack.redoLabel());
        assertTrue(fx.stack.canRedo());
        assertTrue(fx.stack.undo());
        assertEquals(Vec3i.ZERO, fx.pos(a));
        assertFalse(fx.stack.undo());
        assertTrue(fx.stack.redo());
        assertTrue(fx.stack.redo());
        assertEquals("bee", b.name());
        assertEquals(new Vec3i(3, 0, 0), fx.pos(a));
        assertFalse(fx.stack.redo());
    }

    @Test
    void newCommandClearsRedo() {
        fx.stack.execute(move(a, 1));
        fx.stack.undo();
        assertTrue(fx.stack.canRedo());
        fx.stack.execute(new SetHidden(List.of(b.id()), true));
        assertFalse(fx.stack.canRedo());
    }

    @Test
    void refusalChangesNothingAndIsNotPushed() {
        Refusal r = fx.stack.execute(new Rename(a.id(), "  ")).orElseThrow();
        assertEquals("A name cannot be empty", r.reason());
        assertEquals("a", a.name());
        assertFalse(fx.stack.canUndo());
    }

    @Test
    void historyIsLimitedTo200() {
        for (int i = 0; i < 250; i++) {
            fx.stack.execute(new SetHidden(List.of(a.id()), i % 2 == 0));
            fx.stack.breakMerge();
        }
        assertEquals(UndoStack.LIMIT, fx.stack.undoSize());
        int n = 0;
        while (fx.stack.undo()) n++;
        assertEquals(200, n);
    }

    @Test
    void consecutiveNudgesMergeIntoOneStep() {
        fx.stack.execute(move(a, 1));
        fx.stack.execute(move(a, 1));
        fx.stack.execute(move(a, 2));
        assertEquals(1, fx.stack.undoSize());
        assertEquals(new Vec3i(4, 0, 0), fx.pos(a));
        fx.stack.undo();
        assertEquals(Vec3i.ZERO, fx.pos(a));
        fx.stack.redo();
        assertEquals(new Vec3i(4, 0, 0), fx.pos(a));
    }

    @Test
    void mergingNeedsTheSameNodesAndStopsAtBreaksAndUndo() {
        fx.stack.execute(move(a, 1));
        fx.stack.execute(move(b, 1));
        assertEquals(2, fx.stack.undoSize());
        fx.stack.breakMerge();
        fx.stack.execute(move(b, 1));
        assertEquals(3, fx.stack.undoSize());
        fx.stack.undo();
        fx.stack.execute(move(b, 1));   // after an undo the top entry is not extended
        assertEquals(3, fx.stack.undoSize());
        assertEquals(new Vec3i(7, 0, 0), fx.pos(b));
    }

    @Test
    void scrubbingTransformAndTypingNameMerge() {
        fx.stack.execute(new SetTransform(a.id(), Fx.at(1, 0, 0)));
        fx.stack.execute(new SetTransform(a.id(), Fx.at(2, 0, 0)));
        fx.stack.execute(new SetTransform(a.id(), Fx.at(9, 0, 0)));
        assertEquals(1, fx.stack.undoSize());
        fx.stack.undo();
        assertEquals(Transform.IDENTITY, a.transform());
        fx.stack.execute(new Rename(b.id(), "x"));
        fx.stack.execute(new Rename(b.id(), "xy"));
        assertEquals(1, fx.stack.undoSize());
        fx.stack.undo();
        assertEquals("b", b.name());
    }

    @Test
    void transactionIsOneEntry() {
        fx.stack.begin("Drag");
        assertTrue(fx.stack.inTransaction());
        fx.stack.execute(move(a, 1));
        fx.stack.execute(new Rename(b.id(), "bee"));
        fx.stack.execute(new SetHidden(List.of(a.id()), true));
        fx.stack.commit();
        assertFalse(fx.stack.inTransaction());
        assertEquals(1, fx.stack.undoSize());
        assertEquals(Optional.of("Drag"), fx.stack.undoLabel());
        fx.stack.undo();
        assertEquals(Vec3i.ZERO, fx.pos(a));
        assertEquals("b", b.name());
        assertFalse(a.hidden());
        fx.stack.redo();
        assertEquals(new Vec3i(1, 0, 0), fx.pos(a));
        assertEquals("bee", b.name());
        assertTrue(a.hidden());
    }

    @Test
    void commandsInsideATransactionMergeAndCollapse() {
        fx.stack.begin("Drag");
        for (int i = 0; i < 10; i++) fx.stack.execute(move(a, 1));
        fx.stack.commit();
        fx.stack.undo();
        assertEquals(Vec3i.ZERO, fx.pos(a));
        fx.stack.redo();
        assertEquals(new Vec3i(10, 0, 0), fx.pos(a));
    }

    @Test
    void rollbackUndoesAndLeavesNoEntry() {
        fx.stack.execute(move(b, 1));
        fx.stack.begin("Try");
        fx.stack.execute(move(a, 4));
        fx.stack.execute(new Rename(a.id(), "z"));
        fx.stack.rollback();
        assertEquals(Vec3i.ZERO, fx.pos(a));
        assertEquals("a", a.name());
        assertEquals(1, fx.stack.undoSize());
        assertFalse(fx.stack.inTransaction());
    }

    @Test
    void emptyTransactionAddsNothingAndNestedOnesRollBackSeparately() {
        fx.stack.begin("Nothing");
        fx.stack.commit();
        assertFalse(fx.stack.canUndo());

        fx.stack.begin("Outer");
        fx.stack.execute(move(a, 1));
        fx.stack.begin("Inner");
        fx.stack.execute(move(b, 1));
        fx.stack.rollback();
        assertEquals(new Vec3i(5, 0, 0), fx.pos(b));
        fx.stack.begin("Inner 2");
        fx.stack.execute(new Rename(b.id(), "bb"));
        fx.stack.commit();
        fx.stack.commit();
        assertEquals(1, fx.stack.undoSize());
        fx.stack.undo();
        assertEquals(Vec3i.ZERO, fx.pos(a));
        assertEquals("b", b.name());
    }

    @Test
    void undoAndRedoAreNotAllowedInsideATransaction() {
        fx.stack.begin("x");
        assertThrows(IllegalStateException.class, fx.stack::undo);
        assertThrows(IllegalStateException.class, fx.stack::redo);
        fx.stack.rollback();
        assertThrows(IllegalStateException.class, fx.stack::commit);
    }

    @Test
    void aRefusalInsideATransactionDoesNotPoisonIt() {
        fx.stack.begin("x");
        assertTrue(fx.stack.execute(new Rename(a.id(), "")).isPresent());
        fx.stack.execute(move(a, 1));
        fx.stack.commit();
        assertEquals(1, fx.stack.undoSize());
    }

    private static final class Boom implements Command {
        @Override
        public String label() {
            return "Boom";
        }

        @Override
        public void apply(EditContext ctx) {
            throw new IllegalStateException("boom");
        }

        @Override
        public void revert(EditContext ctx) {}
    }

    @Test
    void aCommandThatThrowsIsNotPushed() {
        fx.stack.execute(move(a, 1));
        assertThrows(IllegalStateException.class, () -> fx.stack.execute(new Boom()));
        assertEquals(1, fx.stack.undoSize());
    }

    @Test
    void failingFileEffectRollsTheCommandBack() {
        // a Duplicate whose file copy fails: the model must be left as it was
        ObjectNode c = fx.obj(fx.p, "c", 9, 0, 0);
        String before = fx.state();
        FileSystemPort broken = new MemoryFileSystemPort() {
            @Override
            public void copy(String from, String to) throws java.io.IOException {
                throw new java.io.IOException("disk full");
            }
        };
        EditContext bad = new EditContext(fx.scene, broken, null);
        Duplicate d = new Duplicate(List.of(c.id()), new Vec3i(1, 0, 0));
        ((MemoryFileSystemPort) broken).put("Projects/Metro/files/c.litematic", "x");
        assertThrows(java.io.UncheckedIOException.class, () -> d.apply(bad));
        assertEquals(before, fx.state());
    }

    @Test
    void externalChangeIsUndoableAndMergesPerNode() {
        // Litematica moved a placement; the reconciler adopted it into the model and records it
        a.setTransform(Fx.at(7, 0, 0));
        fx.stack.record(ExternalChangeCommand.transform(a.id(), "a", Transform.IDENTITY, Fx.at(7, 0, 0)));
        a.setTransform(Fx.at(8, 0, 0));
        fx.stack.record(ExternalChangeCommand.transform(a.id(), "a", Fx.at(7, 0, 0), Fx.at(8, 0, 0)));
        assertEquals(1, fx.stack.undoSize());
        assertEquals(Optional.of("External move: a"), fx.stack.undoLabel());
        fx.stack.undo();
        assertEquals(Transform.IDENTITY, a.transform());
        fx.stack.redo();
        assertEquals(Fx.at(8, 0, 0), a.transform());
        assertTrue(fx.sink.affected.contains(a.id()));
        assertTrue(fx.sink.dirty.contains(fx.p.id()));
    }

    @Test
    void externalChangeOfADifferentKindIsAnEntryOfItsOwn() {
        int[] state = {1};
        fx.stack.record(ExternalChangeCommand.of("External thing", c -> state[0] = 1, c -> state[0] = 0));
        fx.stack.record(ExternalChangeCommand.transform(a.id(), "a", Transform.IDENTITY, Fx.at(1, 0, 0)));
        assertEquals(2, fx.stack.undoSize());
        fx.stack.undo();
        fx.stack.undo();
        assertEquals(0, state[0]);
        fx.stack.redo();
        assertEquals(1, state[0]);
    }

    @Test
    void sinkHearsAboutApplyAndRevert() {
        fx.sink.clear();
        fx.stack.execute(move(a, 1));
        assertEquals(java.util.Set.of(a.id()), fx.sink.affected);
        assertEquals(java.util.Set.of(fx.p.id()), fx.sink.dirty);
        fx.sink.clear();
        fx.stack.undo();
        assertEquals(java.util.Set.of(a.id()), fx.sink.affected);
        assertEquals(java.util.Set.of(fx.p.id()), fx.sink.dirty);
    }

    @Test
    void clearForgetsEverything() {
        fx.stack.execute(move(a, 1));
        fx.stack.undo();
        fx.stack.clear();
        assertFalse(fx.stack.canUndo());
        assertFalse(fx.stack.canRedo());
    }
}
