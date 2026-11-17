package dev.vitruvius.editor.mc;

/** Told by the reconciler about changes made outside the editor. The undo stack (WP 1.2) implements it. */
public interface DriftListener {
    DriftListener NONE = c -> {};

    /** A base placement drifted and the model took it over. */
    void onExternalChange(ExternalChange change);
}
