package dev.vitruvius.editor.core;

/** A modifier on an object or assembly: an array stack entry or a Boolean. */
public sealed interface Modifier permits ArrayModifier, BooleanModifier {
    boolean enabled();

    void setEnabled(boolean enabled);

    Modifier copy();
}
