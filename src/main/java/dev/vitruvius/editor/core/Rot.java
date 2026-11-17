package dev.vitruvius.editor.core;

/**
 * A quarter turn about the vertical axis. Positive steps turn clockwise (seen from above), as in Litematica.
 * {@link #CW_90} is one clockwise step, {@link #CCW_90} is three.
 */
public enum Rot {
    NONE, CW_90, CW_180, CCW_90;

    /** Clockwise quarter turns from {@link #NONE}: 0, 1, 2 or 3. */
    public int steps() {
        return ordinal();
    }

    /** This turn plus {@code steps} clockwise quarter turns (negative turns counter-clockwise, wraps). */
    public Rot rotate(int steps) {
        return values()[Math.floorMod(ordinal() + steps, 4)];
    }

    /** The turn that undoes this one. */
    public Rot inverse() {
        return ofSteps(-steps());
    }

    /** The turn with this many clockwise quarter turns from {@link #NONE} (wraps). */
    static Rot ofSteps(int steps) {
        return NONE.rotate(steps);
    }
}
