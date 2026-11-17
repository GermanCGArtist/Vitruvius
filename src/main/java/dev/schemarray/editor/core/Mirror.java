package dev.schemarray.editor.core;

/**
 * A mirror applied before the turn, as in {@code ArraySync.transform}: {@link #LEFT_RIGHT} negates z,
 * {@link #FRONT_BACK} negates x.
 */
public enum Mirror {
    NONE, LEFT_RIGHT, FRONT_BACK;
}
