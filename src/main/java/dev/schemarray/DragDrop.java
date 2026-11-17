package dev.schemarray;

import dev.schemarray.mixin.WidgetContainerAccessor;
import dev.schemarray.mixin.WidgetListBaseAccessor;
import dev.schemarray.api.ListEntry;
import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.malilib.gui.GuiBase;
import fi.dy.masa.malilib.gui.button.ButtonBase;
import fi.dy.masa.malilib.gui.widgets.WidgetBase;
import fi.dy.masa.malilib.gui.widgets.WidgetListBase;
import fi.dy.masa.malilib.gui.widgets.WidgetListEntryBase;
import fi.dy.masa.malilib.render.GuiContext;
import fi.dy.masa.malilib.render.RenderUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.input.MouseButtonEvent;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Multi-select and drag and drop in the placement list (M, Schematic placements). The mixins in
 * WidgetListBaseDragMixin feeds the press, the per-frame poll (drag/release), the release and key events and the drawing into here; everything
 * else is in this class.
 *
 * Selection: plain click = only that row, Ctrl = toggle, Shift = range from the anchor, Ctrl+Shift = add the range.
 * The selection is kept per placement (not per row), also across list refreshes and reopening the screen; rows that
 * are no longer in the list drop out of it. Folder rows are never selected.
 *
 * Drag: a press on a row (not on a button) that moves more than 4 px starts a drag. The click itself (selection of
 * an already selected row, folder open/close) is only done on release, when it turned out not to be a drag.
 */
public final class DragDrop {
    private DragDrop() {}

    private static final int THRESHOLD = 4;
    private static final int EDGE = 10;
    private static final long SCROLL_MS = 90;
    private static final int OK_COLOR = 0xFF80C0FF;
    private static final int BAD_COLOR = 0xFFC04040;

    private static final Set<Object> SELECTION = new LinkedHashSet<>();
    private static Object anchor;

    private static WidgetListBase<?, ?> pressList;
    private static boolean pressed, dragging, deferred;
    private static double startX, startY;
    private static Object pressPlacement;   // a SchematicPlacement or a ListEntry
    private static String pressFolder, pressFolderName;
    private static List<Object> dragPlacements = List.of();
    private static long lastScroll;

    private record Target(boolean valid, boolean inZone, String folder) {}

    /** The selected rows: SchematicPlacements and ListEntries. */
    public static Set<Object> selection() {
        return SELECTION;
    }

    public static boolean isActive(WidgetListBase<?, ?> list) {
        return pressed && list == pressList;
    }

    public static boolean isDragging(WidgetListBase<?, ?> list) {
        return dragging && list == pressList;
    }

    private static void reset() {
        pressed = dragging = deferred = false;
        pressPlacement = null;
        pressFolder = pressFolderName = null;
        pressList = null;
        dragPlacements = List.of();
    }

    public static void cancel() {
        reset();
    }

    // ---------------------------------------------------------------------------------------------- press

    /** The row under the mouse if the click is on the row itself and not on the scrollbar, search bar or one of its buttons. */
    public static WidgetListEntryBase<?> plainRowAt(WidgetListBase<?, ?> list, MouseButtonEvent e) {
        if (list.getScrollbar().wasMouseOver()) return null;
        int mx = (int) e.x(), my = (int) e.y();
        var search = list.getSearchBarWidget();
        if (search != null && search.isMouseOver(mx, my)) return null;
        WidgetListEntryBase<?> row = rowAt(list, mx, my);
        if (row == null) return null;
        Object entry = row.getEntry();
        if (!(entry instanceof Folders.Node) && !isItem(entry)) return null;
        if (isItem(entry) && !row.canSelectAt(e)) return null;   // the button area of the row
        for (WidgetBase w : ((WidgetContainerAccessor) row).schemarray$subWidgets()) {
            if (w instanceof ButtonBase && w.isMouseOver(mx, my)) return null;
        }
        return row;
    }

    /** True (and the click is consumed) if the left press is on a row outside its buttons; false = let malilib handle it. */
    public static boolean onPress(WidgetListBase<?, ?> list, MouseButtonEvent e) {
        reset();
        if (e.input() != 0) return false;
        int mx = (int) e.x(), my = (int) e.y();
        WidgetListEntryBase<?> row = plainRowAt(list, e);
        if (row == null) return false;
        Object entry = row.getEntry();

        pressList = list;
        pressed = true;
        startX = mx;
        startY = my;
        if (entry instanceof Folders.Node n) {
            pressFolder = n.id;
            pressFolderName = n.name;
            return true;
        }

        Object p = entry;
        pressPlacement = p;
        boolean ctrl = GuiBase.isCtrlDown(), shift = GuiBase.isShiftDown();
        var manager = DataManager.getSchematicPlacementManager();
        List<?> rows = list.getCurrentEntries();
        int from = anchor == null ? -1 : indexOf(rows, anchor), to = indexOf(rows, p);
        if (shift && from >= 0 && to >= 0) {
            if (!ctrl) SELECTION.clear();
            for (int i = Math.min(from, to); i <= Math.max(from, to); i++) {
                if (isItem(rows.get(i))) SELECTION.add(rows.get(i));   // folder rows are skipped
            }
            setCurrent(p);
        } else if (ctrl) {
            if (!SELECTION.remove(p)) {
                SELECTION.add(p);
                setCurrent(p);
            } else if (manager.getSelectedSchematicPlacement() == p) {
                manager.setSelectedSchematicPlacement(null);
            }
            anchor = p;
        } else if (SELECTION.contains(p)) {
            deferred = true;   // maybe the start of a drag of the whole selection: decided on release
        } else {
            SELECTION.clear();
            SELECTION.add(p);
            anchor = p;
            setCurrent(p);
        }
        return true;
    }

    /** A row that can be selected and dragged: a placement or an entry of another mod. */
    private static boolean isItem(Object o) {
        return o instanceof SchematicPlacement || o instanceof ListEntry;
    }

    /** Litematica's own "selected placement" follows the clicked row; an entry of another mod is not one, so it clears it. */
    private static void setCurrent(Object item) {
        DataManager.getSchematicPlacementManager().setSelectedSchematicPlacement(item instanceof SchematicPlacement p ? p : null);
    }

    private static int indexOf(List<?> rows, Object o) {
        for (int i = 0; i < rows.size(); i++) if (rows.get(i) == o) return i;
        return -1;
    }

    private static WidgetListEntryBase<?> rowAt(WidgetListBase<?, ?> list, int mx, int my) {
        for (Object o : ((WidgetListBaseAccessor) list).schemarray$listWidgets()) {
            WidgetListEntryBase<?> w = (WidgetListEntryBase<?>) o;
            if (w.isMouseOver(mx, my)) return w;
        }
        return null;
    }

    // ---------------------------------------------------------------------------------------------- drag

    /** Polled every frame (mouse events of a consumed press are not forwarded as drags): start, finish or continue. */
    private static boolean leftDown() {
        return Compat.leftDown();
    }

    public static void poll(WidgetListBase<?, ?> list, int mx, int my) {
        if (!pressed || list != pressList) return;
        if (!leftDown()) {
            finish(list, mx, my);
            return;
        }
        if (dragging) return;
        double dx = mx - startX, dy = my - startY;
        if (dx * dx + dy * dy <= THRESHOLD * THRESHOLD) return;
        dragging = true;
        deferred = false;
        if (pressPlacement != null) {
            if (!SELECTION.contains(pressPlacement)) {   // e.g. Ctrl+click took it out of the selection
                SELECTION.clear();
                SELECTION.add(pressPlacement);
                anchor = pressPlacement;
                setCurrent(pressPlacement);
            }
            List<Object> out = new ArrayList<>();
            for (Object o : pressList.getCurrentEntries()) {
                if (isItem(o) && SELECTION.contains(o)) out.add(o);
            }
            dragPlacements = out;
        }
    }

    private static Target target(WidgetListBase<?, ?> list, int mx, int my) {
        WidgetListBaseAccessor acc = (WidgetListBaseAccessor) list;
        int x0 = acc.schemarray$posX(), y0 = acc.schemarray$posY();
        boolean inZone = mx >= x0 && mx < x0 + acc.schemarray$browserWidth() && my >= y0 - 20 && my < y0 + acc.schemarray$browserHeight();
        if (!inZone) return new Target(false, false, null);
        String folder = null;
        WidgetListEntryBase<?> row = rowAt(list, mx, my);
        if (row != null) {
            Object entry = row.getEntry();
            if (entry instanceof Folders.Node n) folder = n.id;
            else if (isItem(entry)) folder = Folders.folderOf(entry);
        }
        boolean valid = pressFolder == null || folder == null || !Folders.isInside(folder, pressFolder);
        return new Target(valid, true, folder);
    }

    // ---------------------------------------------------------------------------------------------- release

    /** Fast path from the release event; true if it ended a drag (malilib must not see it). poll() does the same without it. */
    public static boolean onRelease(WidgetListBase<?, ?> list, MouseButtonEvent e) {
        if (e.input() != 0 || !pressed) return false;
        if (list != pressList) {
            reset();
            return false;
        }
        return finish(list, (int) e.x(), (int) e.y());
    }

    /** Ends the press: the drop, or the click when there was no drag. The state is cleared first, so it can only run once. */
    private static boolean finish(WidgetListBase<?, ?> list, int mx, int my) {
        boolean wasDrag = dragging, wasDeferred = deferred;
        Object p = pressPlacement;
        String folder = pressFolder;
        List<Object> moving = dragPlacements;
        Target t = wasDrag ? target(list, mx, my) : null;   // needs pressFolder
        reset();
        if (wasDrag) {
            if (t.inZone && t.valid) {
                if (folder != null) Folders.moveFolder(folder, t.folder);
                else if (!moving.isEmpty()) Folders.moveAll(moving, t.folder);
                ListRefresh.later();
            }
            return true;
        }
        if (folder != null) {
            Folders.toggleOpen(folder);
            ListRefresh.later();
        } else if (wasDeferred && p != null) {
            var manager = DataManager.getSchematicPlacementManager();
            if (SELECTION.size() == 1 && manager.getSelectedSchematicPlacement() == p) {
                SELECTION.clear();   // a click on the one selected row again: deselect, as Litematica always did
                manager.setSelectedSchematicPlacement(null);
            } else {
                SELECTION.clear();
                SELECTION.add(p);
                anchor = p;
                setCurrent(p);
            }
        }
        return false;
    }

    // ---------------------------------------------------------------------------------------------- list sync and drawing

    /** Rows that are no longer in the list drop out of the selection. */
    public static void prune(WidgetListBase<?, ?> list) {
        Set<Object> visible = new HashSet<>(list.getCurrentEntries());
        SELECTION.removeIf(p -> !visible.contains(p));
        if (anchor != null && !visible.contains(anchor)) anchor = null;
    }

    /** malilib highlights the rows that are in its selectedEntries (allowMultiSelection): mirror our selection into it. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static void sync(WidgetListBase<?, ?> list) {
        Set set = list.getSelectedEntries();
        if (set.size() == SELECTION.size() && set.containsAll(SELECTION)) return;
        set.clear();
        set.addAll(SELECTION);
    }

    /** After the list is drawn: auto-scroll, the target highlight and the label at the cursor. */
    public static void render(WidgetListBase<?, ?> list, GuiContext ctx, int mx, int my) {
        if (!dragging || list != pressList) return;
        WidgetListBaseAccessor acc = (WidgetListBaseAccessor) list;
        int x0 = acc.schemarray$posX(), y0 = acc.schemarray$posY(), w = acc.schemarray$browserWidth(), h = acc.schemarray$browserHeight();

        // auto-scroll near the top and bottom edge of the rows
        int rowsTop = acc.schemarray$entriesStartY() + acc.schemarray$entriesOffsetY();
        long now = System.currentTimeMillis();
        if (mx >= x0 && mx < x0 + w && now - lastScroll >= SCROLL_MS) {
            if (my < rowsTop + EDGE && my >= y0 - 20) {
                list.getScrollbar().offsetValue(-1);
                lastScroll = now;
            } else if (my > y0 + h - EDGE && my < y0 + h + 20) {
                list.getScrollbar().offsetValue(1);
                lastScroll = now;
            }
        }

        // drop target
        Target t = target(list, mx, my);
        boolean ok = t.inZone && t.valid;
        if (ok) {
            WidgetListEntryBase<?> folderRow = null;
            if (t.folder != null) {
                for (Object o : acc.schemarray$listWidgets()) {
                    WidgetListEntryBase<?> r = (WidgetListEntryBase<?>) o;
                    if (r.getEntry() instanceof Folders.Node n && n.id.equals(t.folder)) folderRow = r;
                }
            }
            if (folderRow != null) {
                RenderUtils.drawRect(ctx, folderRow.getX(), folderRow.getY(), folderRow.getWidth(), folderRow.getHeight(), 0x4080C0FF);
                RenderUtils.drawOutline(ctx, folderRow.getX(), folderRow.getY(), folderRow.getWidth(), folderRow.getHeight(), OK_COLOR);
            } else if (t.folder == null) {
                RenderUtils.drawOutline(ctx, x0, y0, w, h, OK_COLOR);   // top level: the whole list
            }
        }

        // label next to the cursor
        String text = pressFolder != null ? pressFolderName
                : dragPlacements.size() == 1 ? Folders.nameOf(dragPlacements.get(0)) : dragPlacements.size() + " rows";
        text = (ok ? "§f" : "§c") + text;
        int tw = list.getStringWidth(text);
        int lx = mx + 10, ly = my + 4;
        RenderUtils.drawRect(ctx, lx - 3, ly - 3, tw + 6, 14, 0xE0000000);
        RenderUtils.drawOutline(ctx, lx - 3, ly - 3, tw + 6, 14, ok ? OK_COLOR : BAD_COLOR);
        list.drawStringWithShadow(ctx, text, lx, ly, 0xFFFFFFFF);
    }
}
