package dev.vitruvius.editor.core;

/**
 * A mirror applied before the turn, as Litematica does it: {@link #LEFT_RIGHT} negates z,
 * {@link #FRONT_BACK} negates x.
 */
public enum Mirror {
    NONE, LEFT_RIGHT, FRONT_BACK;
}
