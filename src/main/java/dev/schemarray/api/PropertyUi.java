package dev.schemarray.api;

/**
 * The widgets of the Properties window for an object type. Every method draws one row and returns the (possibly
 * edited) value; a change also sets {@link #changed()}. There is no ImGui type in here, so a mod can implement a
 * type without depending on the UI library.
 */
public interface PropertyUi {
    /** A small section title. */
    void section(String title);

    void text(String text);

    /** Secondary, dimmed text. */
    void note(String text);

    int intField(String label, int value);

    double floatField(String label, double value);

    boolean checkbox(String label, boolean value);

    String textField(String label, String value);

    /** A drop-down; returns the chosen index. */
    int combo(String label, int index, String... options);

    /** True when the button was clicked this frame (it does not set {@link #changed()}). */
    boolean button(String label);

    /** Whether any widget changed a value since the call began (or {@link #markChanged} was called). */
    boolean changed();

    /** For changes you made without a widget of this interface. */
    void markChanged();
}
