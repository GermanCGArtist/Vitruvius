package dev.schemarray.editor.ops;

import java.util.Optional;

/**
 * One undoable edit of the scene (and of the files next to it). Pure model operation: no Minecraft types.
 *
 * <p>The contract:
 * <ul>
 *   <li>{@link #check} says whether the command can run now, without changing anything.</li>
 *   <li>{@link #apply} runs it (also for redo, so it must give the same result again); if it throws, it has put
 *       everything back first.</li>
 *   <li>{@link #revert} undoes the last {@code apply}; the scene and the files are as they were before it.</li>
 * </ul>
 */
public interface Command {
    /** Text for the Undo/Redo menu entries, e.g. "Move tunnel". */
    String label();

    /** A reason the command cannot run, or empty. Does not change anything. */
    default Optional<Refusal> check(EditContext ctx) {
        return Optional.empty();
    }

    void apply(EditContext ctx);

    void revert(EditContext ctx);

    /**
     * Folds {@code next}, which was just applied after this command, into this one so both undo as one step
     * (consecutive nudges, a scrub drag, typing a name). Returns false when they do not belong together.
     */
    default boolean mergeWith(Command next) {
        return false;
    }
}
