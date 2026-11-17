package dev.schemarray.shape.gui;

import fi.dy.masa.malilib.gui.widgets.WidgetDropDownList;
import fi.dy.masa.malilib.interfaces.IStringRetriever;
import fi.dy.masa.malilib.render.GuiContext;

import java.util.List;

/**
 * malilib's drop down list plus what the Shape Generator screen needs from it: asking whether the list is open,
 * closing it from outside, and drawing the open list separately. render() only draws the closed box; the screen calls
 * drawList() after every other widget, so the list is on top of the controls below it.
 */
final class ShapeDropDown<T> extends WidgetDropDownList<T> {
    private static final int ROW_H = 20;

    /** screenHeight limits how many rows are shown at once (the rest scrolls with the wheel). */
    ShapeDropDown(int x, int y, int width, List<T> entries, IStringRetriever<T> names, int screenHeight) {
        super(x, y, width, ROW_H, ROW_H * rows(entries.size(), y, screenHeight), rows(entries.size(), y, screenHeight), entries, names);
    }

    private static int rows(int entries, int y, int screenHeight) {
        return Math.max(2, Math.min(Math.min(entries, 12), (screenHeight - y - ROW_H - 6) / ROW_H));
    }

    boolean opened() {
        return isOpen;
    }

    void close() {
        if (!isOpen) return;
        isOpen = false;
        searchBar.textField().setValue("");
        updateFilteredEntries();
    }

    @Override
    public void render(GuiContext ctx, int mouseX, int mouseY, boolean selected) {
        boolean open = isOpen;
        isOpen = false;
        super.render(ctx, mouseX, mouseY, selected);
        isOpen = open;
    }

    /** The open list, on top of what was drawn so far; does nothing while closed. */
    void drawList(GuiContext ctx, int mouseX, int mouseY) {
        if (isOpen) postRenderHovered(ctx, mouseX, mouseY, false);
    }

    void scroll(double vertical) {
        onMouseScrolledImpl(0, 0, 0, vertical);
    }
}
