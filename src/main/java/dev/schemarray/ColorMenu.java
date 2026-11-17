package dev.schemarray;

import dev.schemarray.api.ListEntry;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.malilib.gui.widgets.WidgetListBase;
import fi.dy.masa.malilib.gui.widgets.WidgetListEntryBase;
import fi.dy.masa.malilib.render.GuiContext;
import fi.dy.masa.malilib.render.RenderUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.input.MouseButtonEvent;

import java.util.ArrayList;
import java.util.List;

/**
 * The right-click color menu of the placement list: a "Color" popup at the cursor with the 16 chat colors as a 4x4
 * grid and a "No color" line. While it is open it takes every click (GuiBaseMenuMixin gets them first); a click
 * outside, Escape or a scroll closes it unchanged. Drawn last in the list's drawContents.
 */
public final class ColorMenu {
    private ColorMenu() {}

    private static final int CELL = 12, GAP = 2, PAD = 6, GRID = 4 * CELL + 3 * GAP, NONE_H = 12;
    private static final int HEIGHT = 28 + GRID + 4 + NONE_H + 4;

    private static boolean open;
    private static WidgetListBase<?, ?> list;
    private static int x, y, w;
    private static String subtitle;
    private static String folderId;                       // set: a folder row
    private static List<Object> placements;   // else: these placements / entries
    private static int current;

    public static boolean isOpen() {
        return open;
    }

    public static void close() {
        open = false;
        list = null;
        folderId = null;
        placements = null;
    }

    /** Right press on a row outside its buttons: opens the menu for it. */
    public static boolean tryOpen(WidgetListBase<?, ?> l, MouseButtonEvent e) {
        WidgetListEntryBase<?> row = DragDrop.plainRowAt(l, e);
        if (row == null) return false;
        close();
        Object entry = row.getEntry();
        if (entry instanceof Folders.Node n) {
            folderId = n.id;
            current = n.color;
            subtitle = n.name;
        } else if (entry instanceof SchematicPlacement || entry instanceof ListEntry) {
            Object p = entry;
            placements = new ArrayList<>();
            if (DragDrop.selection().contains(p)) {
                for (Object q : DragDrop.selection()) if (!(q instanceof SchematicPlacement sp && ArraySync.isCopy(sp))) placements.add(q);
            }
            if (placements.isEmpty()) placements.add(p);
            current = Folders.ownColor(p);
            subtitle = placements.size() == 1 ? Folders.nameOf(p) : placements.size() + " rows";
        } else {
            return false;
        }
        list = l;
        w = Math.max(GRID + 2 * PAD, Math.max(l.getStringWidth("Color"), l.getStringWidth(subtitle)) + 2 * PAD);
        var window = Minecraft.getInstance().getWindow();
        x = Math.max(2, Math.min((int) e.x(), window.getGuiScaledWidth() - w - 2));
        y = Math.max(2, Math.min((int) e.y(), window.getGuiScaledHeight() - HEIGHT - 2));
        open = true;
        return true;
    }

    private static int gridX() {
        return x + PAD;
    }

    private static int gridY() {
        return y + 28;
    }

    /** The color index under the point: 0-15, -1 = the "No color" line, -2 = nothing. */
    private static int hit(int mx, int my) {
        for (int i = 0; i < 16; i++) {
            int cx = gridX() + (i % 4) * (CELL + GAP), cy = gridY() + (i / 4) * (CELL + GAP);
            if (mx >= cx && mx < cx + CELL && my >= cy && my < cy + CELL) return i;
        }
        int ny = gridY() + GRID + 4;
        if (mx >= x + 2 && mx < x + w - 2 && my >= ny && my < ny + NONE_H) return -1;
        return -2;
    }

    /** Every click while open ends up here: a left click on a color applies it; anything else closes the menu. */
    public static void onClick(MouseButtonEvent e) {
        if (!open) return;
        int c = e.input() == 0 ? hit((int) e.x(), (int) e.y()) : -2;
        if (c > -2) {
            if (folderId != null) Folders.setFolderColor(folderId, c);
            else if (placements != null) Folders.setPlacementColors(placements, c);
            ListRefresh.later();
        }
        close();
    }

    public static void render(WidgetListBase<?, ?> l, GuiContext ctx, int mx, int my) {
        if (!open || l != list) return;
        ctx.nextStratum();   // a layer of its own, above the rows and their buttons
        RenderUtils.drawRect(ctx, x, y, w, HEIGHT, 0xFF101010);
        RenderUtils.drawOutline(ctx, x, y, w, HEIGHT, 0xFFC0C0C0);
        l.drawStringWithShadow(ctx, "Color", x + PAD, y + 4, 0xFFFFFFFF);
        l.drawStringWithShadow(ctx, subtitle, x + PAD, y + 15, 0xFFAAAAAA);

        int hover = hit(mx, my);
        for (int i = 0; i < 16; i++) {
            int cx = gridX() + (i % 4) * (CELL + GAP), cy = gridY() + (i / 4) * (CELL + GAP);
            RenderUtils.drawRect(ctx, cx, cy, CELL, CELL, 0xFF000000 | Folders.COLORS[i]);
            RenderUtils.drawOutline(ctx, cx, cy, CELL, CELL, i == current ? 0xFFFFFFFF : 0xFF505050);
            if (i == hover) {
                RenderUtils.drawRect(ctx, cx, cy, CELL, CELL, 0x50FFFFFF);
                RenderUtils.drawOutline(ctx, cx - 1, cy - 1, CELL + 2, CELL + 2, 0xFFFFFF55);
            }
        }
        int ny = gridY() + GRID + 4;
        if (hover == -1) RenderUtils.drawRect(ctx, x + 2, ny, w - 4, NONE_H, 0xFF505050);
        else if (current < 0) RenderUtils.drawOutline(ctx, x + 2, ny, w - 4, NONE_H, 0xFFFFFFFF);
        l.drawStringWithShadow(ctx, "No color", x + PAD, ny + 2, 0xFFFFFFFF);
    }
}
