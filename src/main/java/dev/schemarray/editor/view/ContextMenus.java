package dev.schemarray.editor.view;

import dev.cg.uihost.Theme;
import dev.cg.uihost.UiHost;
import dev.schemarray.editor.EditorSession;
import dev.schemarray.editor.core.Node;
import dev.schemarray.editor.ui.EditorActions;
import dev.schemarray.editor.ui.MenuModel;
import dev.schemarray.editor.ui.MenuModel.Item;
import imgui.ImDrawList;
import imgui.ImGui;
import imgui.flag.ImGuiCol;
import imgui.flag.ImGuiStyleVar;
import imgui.flag.ImGuiWindowFlags;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The right-click menus (DESIGN 10) drawn from {@link MenuModel}. A menu is built once when it opens, with the
 * refusal of every command that is available in principle (so a refused entry shows its reason as a tooltip), and
 * drawn as an ImGui popup. Entries run through {@link EditorActions#run}, whose refusals become toasts.
 */
public final class ContextMenus {
    private ContextMenus() {}

    private static final String POPUP = "##la_context";

    private static List<Item> menu = List.of();
    private static final Map<String, String> refusals = new HashMap<>();
    private static boolean openRequest;
    private static boolean hasAnchor;
    private static float anchorX, anchorY;

    /** The next menu opens at this screen position instead of at the pointer (toolbar buttons). */
    public static void anchor(float x, float y) {
        hasAnchor = true;
        anchorX = x;
        anchorY = y;
    }

    /** Opens the menu for the current selection at the pointer, or the viewport menu when nothing is selected. */
    public static void openForSelection(EditorSession s, boolean viewport) {
        build(s, viewport);
        openRequest = true;
    }

    /** Opens the Add menu (Shift A, the toolbar Add button) at the pointer. */
    public static void openAdd(EditorSession s) {
        List<Item> items = new ArrayList<>(MenuModel.addItems(true, ""));
        menu = items;
        refusals.clear();
        for (Item i : items) {
            if (i.enabled() && EditorActions.handles(i.id())) s.actions.refusal(i.id()).ifPresent(r -> refusals.put(i.id(), r));
        }
        openRequest = true;
    }

    private static void build(EditorSession s, boolean viewport) {
        List<Node> nodes = new ArrayList<>();
        {
            for (var id : s.selection.ids()) {
                Node n = s.scene().byId(id);
                if (n != null) nodes.add(n);
            }
        }
        boolean hasHidden = false;
        for (Node n : s.scene().allNodes()) if (n.hidden()) hasHidden = true;
        var undo = s.undoStack();
        MenuModel.Ctx ctx = new MenuModel.Ctx(s.scene(), nodes, undo.undoLabel().orElse(null), undo.redoLabel().orElse(null), hasHidden, s.localView);
        menu = viewport ? MenuModel.viewport(ctx) : MenuModel.forSelection(ctx);
        refusals.clear();
        for (Item i : MenuModel.flatten(menu)) {
            if (i.enabled() && !i.submenu() && !i.separator() && !i.header() && EditorActions.handles(i.id())
                    && !i.id().equals("undo") && !i.id().equals("redo")) {
                s.actions.refusal(i.id()).ifPresent(r -> refusals.put(i.id(), r));
            }
        }
    }

    /** Draws the popup; call once per frame from the layer, outside any window. */
    public static void draw(EditorSession s) {
        float sc = Ui.s();
        if (openRequest) {
            openRequest = false;
            ImGui.openPopup(POPUP);
            if (hasAnchor) ImGui.setNextWindowPos(anchorX, anchorY, imgui.flag.ImGuiCond.Always);
            hasAnchor = false;
        }
        ImGui.pushStyleVar(ImGuiStyleVar.PopupRounding, 7 * sc);
        ImGui.pushStyleVar(ImGuiStyleVar.WindowPadding, 4 * sc, 4 * sc);
        ImGui.pushStyleVar(ImGuiStyleVar.ItemSpacing, 8 * sc, 2 * sc);
        ImGui.pushStyleVar(ImGuiStyleVar.FramePadding, 9 * sc, 5 * sc);
        ImGui.pushStyleColor(ImGuiCol.PopupBg, Ui.c(Theme.PANEL2));
        ImGui.pushStyleColor(ImGuiCol.Border, Ui.c(Theme.LINE));
        ImGui.setNextWindowSizeConstraints(240 * sc, 0, 4000, 4000);
        if (ImGui.beginPopup(POPUP, ImGuiWindowFlags.NoSavedSettings)) {
            s.popupOpen = true;
            drawItems(s, menu);
            ImGui.endPopup();
        }
        ImGui.popStyleColor(2);
        ImGui.popStyleVar(4);
    }

    private static void drawItems(EditorSession s, List<Item> items) {
        float sc = Ui.s();
        for (Item it : items) {
            if (it.separator()) {
                ImGui.separator();
            } else if (it.header()) {
                Theme.push(UiHost.theme().small());
                ImGui.pushStyleColor(ImGuiCol.Text, Ui.c(Theme.DIM));
                ImGui.text(" " + it.label());
                ImGui.popStyleColor();
                ImGui.popFont();
            } else if (it.submenu()) {
                float y = ImGui.getCursorScreenPosY(), x = ImGui.getCursorScreenPosX();
                boolean open = ImGui.beginMenu(pad(it.label()), it.enabled());
                icon(it, x, y, ImGui.getItemRectSizeY());
                if (open) {
                    drawItems(s, it.children());
                    ImGui.endMenu();
                }
            } else {
                String refusal = refusals.get(it.id());
                boolean enabled = it.enabled() && refusal == null;
                float x = ImGui.getCursorScreenPosX(), y = ImGui.getCursorScreenPosY();
                if (it.danger()) ImGui.pushStyleColor(ImGuiCol.Text, Ui.c(Theme.ERR));
                boolean hit = ImGui.menuItem(pad(it.label()), it.shortcut(), false, enabled);
                if (it.danger()) ImGui.popStyleColor();
                icon(it, x, y, ImGui.getItemRectSizeY());
                if (!enabled) {
                    String tip = refusal != null ? refusal : it.tip();
                    if (!tip.isEmpty()) Ui.tooltip(tip);
                }
                if (hit) {
                    ImGui.closeCurrentPopup();
                    s.actions.run(it.id());
                }
            }
        }
    }

    /**
     * Labels get leading space for the 14 px icon column (DESIGN 10). The icon spans x+8..x+22, so the label must start
     * at about x+30; the number of spaces is measured with the current font, since a space is only a few pixels wide.
     */
    private static String pad(String label) {
        float sc = Ui.s();
        float space = Math.max(1f, ImGui.calcTextSizeX(" "));
        int n = (int) Math.ceil(26 * sc / space);
        return " ".repeat(Math.max(6, n)) + label;
    }

    private static void icon(Item it, float x, float y, float h) {
        if (it.icon().isEmpty()) return;
        float sc = Ui.s();
        ImDrawList dl = ImGui.getWindowDrawList();
        int rgb = it.danger() ? Theme.ERR : it.enabled() ? Theme.DIM : Theme.GUTTER;
        EditorIcons.draw(dl, it.icon(), x + 15 * sc, y + h * 0.5f, 7 * sc, Ui.c(rgb));
    }

    /** True while the menu popup is open (the input layer then leaves arrows and Enter to it). */
    public static boolean isOpen() {
        return ImGui.isPopupOpen(POPUP);
    }
}
