package dev.vitruvius;

import dev.vitruvius.api.FolderActionContext;
import dev.vitruvius.api.ListEntry;
import dev.vitruvius.api.VitruviusApi;
import fi.dy.masa.litematica.gui.Icons;
import fi.dy.masa.malilib.gui.GuiBase;
import fi.dy.masa.malilib.gui.GuiTextInput;
import fi.dy.masa.malilib.gui.button.ButtonGeneric;
import fi.dy.masa.malilib.gui.widgets.WidgetListEntryBase;
import fi.dy.masa.malilib.render.GuiContext;
import fi.dy.masa.malilib.render.RenderUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.input.MouseButtonEvent;

import java.util.ArrayList;
import java.util.List;

/**
 * A folder row in Litematica's placement list: open/closed arrow, name and count, and on the right the buttons
 * [buttons of other mods], Visible/Hidden, +, Rename and Delete. A click anywhere else on the row opens or closes the folder (see DragDrop: it is done on release, so the row can also be dragged).
 */
public final class WidgetFolderRow extends WidgetListEntryBase<Folders.Node> {
    private final boolean isOdd;

    public WidgetFolderRow(int x, int y, int width, int height, boolean isOdd, Folders.Node node, int listIndex) {
        super(x, y, width, height, node, listIndex);
        this.isOdd = isOdd;
        int bx = x + width - 2, by = y + 1;
        if (node.editor) return;   // mirrors the editor's tree: change it in the editor (F6)
        List<VitruviusApi.FolderAction> actions = VitruviusApi.folderActions();

        ButtonGeneric del = new ButtonGeneric(bx, by, -1, true, "Delete");
        del.setHoverStrings("Hold Shift and click to delete the folder.", "Its placements and subfolders move up; no placement is removed.");
        addButton(del, (b, mouse) -> {
            if (!GuiBase.isShiftDown()) return;
            Folders.delete(node.id);
            ListRefresh.later();
        });
        bx = del.getX() - 1;

        ButtonGeneric rename = new ButtonGeneric(bx, by, -1, true, "Rename");
        addButton(rename, (b, mouse) -> GuiBase.openGui(new GuiTextInput(64, "Rename folder", node.name, Compat.screen(), s -> {
            String name = s.trim();
            if (!name.isEmpty()) Folders.rename(node.id, name);
        })));
        bx = rename.getX() - 1;

        ButtonGeneric add = new ButtonGeneric(bx, by, -1, true, "+");
        add.setHoverStrings("New subfolder");
        addButton(add, (b, mouse) -> GuiBase.openGui(new GuiTextInput(64, "New subfolder", "", Compat.screen(), s -> {
            String name = s.trim();
            if (!name.isEmpty()) Folders.create(name, node.id);
        })));
        bx = add.getX() - 1;

        ButtonGeneric vis = new ButtonGeneric(bx, by, -1, true, node.hidden ? "§cHidden" : "§aVisible");
        vis.setHoverStrings("Hidden switches the rendering of everything in this folder off.", "Placements you switched off yourself stay off when shown again.");
        addButton(vis, (b, mouse) -> {
            Folders.toggleHidden(node.id);
            ListRefresh.later();
        });
        bx = vis.getX() - 1;

        for (VitruviusApi.FolderAction fa : actions) {
            ButtonGeneric b = new ButtonGeneric(bx, by, -1, true, fa.label());
            if (fa.hover() != null) b.setHoverStrings(fa.hover());
            addButton(b, (btn, mouse) -> runFolderAction(fa, node));
            bx = b.getX() - 1;
        }
    }

    private static void runFolderAction(VitruviusApi.FolderAction fa, Folders.Node node) {
        try {
            List<String> ids = new ArrayList<>();
            for (ListEntry e : Folders.entriesIn(node.id, VitruviusApi.allEntries())) ids.add(e.id());
            fa.action().accept(new FolderActionContext(node.id, node.name, List.copyOf(ids)));
        } catch (Throwable t) {
            System.out.println("[Vitruvius] folder action '" + fa.label() + "' failed: " + t);
        }
    }

    @Override
    public boolean canSelectAt(MouseButtonEvent event) {
        return false;
    }

    @Override
    public void render(GuiContext ctx, int mouseX, int mouseY, boolean selected) {
        int color = isMouseOver(mouseX, mouseY) ? 0xA0707070 : isOdd ? 0xA0101010 : 0xA0303030;
        RenderUtils.drawRect(ctx, x, y, width, height, color);
        boolean colored = entry.color >= 0 && entry.color < Folders.COLORS.length;
        int rgb = colored ? Folders.COLORS[entry.color] : 0;
        if (colored) RenderUtils.drawRect(ctx, x, y, width, height, 0x50000000 | rgb);
        int tx = x + 4;
        drawString(ctx, tx, y + 7, 0xFFFFFFFF, entry.open ? "v" : ">");
        Icons.FILE_ICON_DIR.renderAt(ctx, tx + 10, y + 5, zLevel, false, false);
        String label = entry.name + " (" + entry.count + ")";
        drawString(ctx, tx + 28, y + 7, colored ? 0xFF000000 | rgb : entry.hidden ? 0xFF909090 : 0xFFFFFFFF, label);
        if (entry.editor) {
            String note = entry.hidden ? "hidden in the editor" : "editor";
            drawString(ctx, x + width - 6 - getStringWidth(note), y + 7, 0xFF808080, note);
        }
        super.render(ctx, mouseX, mouseY, selected);
    }
}
