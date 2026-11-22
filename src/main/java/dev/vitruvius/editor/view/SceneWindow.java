package dev.vitruvius.editor.view;

import dev.cg.uihost.Theme;
import dev.cg.uihost.UiHost;
import dev.vitruvius.editor.EditorSession;
import dev.vitruvius.editor.core.ArrayCells;
import dev.vitruvius.editor.core.ArrayModifier;
import dev.vitruvius.editor.core.AssemblyNode;
import dev.vitruvius.editor.core.BooleanModifier;
import dev.vitruvius.editor.core.ExternalNode;
import dev.vitruvius.editor.core.FolderNode;
import dev.vitruvius.editor.core.HasFront;
import dev.vitruvius.editor.core.LooseNode;
import dev.vitruvius.editor.core.Modifier;
import dev.vitruvius.editor.core.Node;
import dev.vitruvius.editor.core.ObjectNode;
import dev.vitruvius.editor.core.ProjectNode;
import dev.vitruvius.editor.ops.AddObject;
import dev.vitruvius.editor.ops.Command;
import dev.vitruvius.editor.ops.MoveToProject;
import dev.vitruvius.editor.ops.Rename;
import dev.vitruvius.editor.ops.SetHidden;
import dev.vitruvius.editor.ui.TreeModel;
import dev.vitruvius.editor.ui.TreeModel.Chip;
import dev.vitruvius.editor.ui.TreeModel.Kind;
import dev.vitruvius.editor.ui.TreeModel.Row;
import dev.vitruvius.editor.ui.TreeRules;
import dev.vitruvius.editor.ui.ToastQueue;
import imgui.ImDrawList;
import imgui.ImGui;
import imgui.flag.ImGuiCol;
import imgui.flag.ImGuiDragDropFlags;
import imgui.flag.ImGuiFocusedFlags;
import imgui.flag.ImGuiInputTextFlags;
import imgui.flag.ImGuiKey;
import imgui.flag.ImGuiStyleVar;
import imgui.flag.ImGuiWindowFlags;
import imgui.type.ImBoolean;
import imgui.type.ImString;
import net.minecraft.client.Minecraft;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * The Scene window (DESIGN 7): search and filter chips, the project tree with badges, visibility toggles, inline
 * rename, drag and drop with the refusal shown under the cursor, Shift/Ctrl selection, and the footer with New
 * project and Open project. Everything it changes goes through commands on the undo stack.
 */
public final class SceneWindow {
    private SceneWindow() {}

    private static final ImBoolean OPEN = new ImBoolean(true);
    private static final ImString SEARCH = new ImString(128);
    private static final ImString RENAME = new ImString(128);
    private static final ImString NEW_NAME = new ImString("New project", 64);
    private static boolean focusNewName;

    private static UUID renaming;
    private static boolean renameFocus;
    private static List<UUID> dragIds;
    private static UUID expandCandidate;
    private static double expandSince;
    private static UUID deferredSelect;
    private static List<String> projectChoices = List.of();
    private static List<String> schematicChoices = List.of();
    private static List<String> schematicDirs = List.of();
    private static SchemFolder schematicTree = new SchemFolder();
    private static List<Path> lasmChoices = List.of();
    private static List<ProjectNode> linkChoices = List.of();
    private static UUID addParent;
    private static final ImString PICK_FILTER = new ImString(64);

    /** True while a row is being dragged (the status bar shows the tree-drag hints). */
    public static boolean isDragging() {
        return dragIds != null;
    }

    /** True while a row is being renamed. */
    public static boolean isRenaming() {
        return renaming != null;
    }

    // ------------------------------------------------------------------------------------------ window

    public static void draw(EditorSession s) {
        float sc = Ui.s();
        var vp = ImGui.getMainViewport();
        OPEN.set(s.showScene);
        ImGui.setNextWindowPos(vp.getPosX() + 12 * sc, vp.getPosY() + 56 * sc, (s.resetLayout ? imgui.flag.ImGuiCond.Always : imgui.flag.ImGuiCond.FirstUseEver));
        ImGui.setNextWindowSize(300 * sc, Math.max(200 * sc, vp.getSizeY() - 56 * sc - 24 * sc - 12 * sc), (s.resetLayout ? imgui.flag.ImGuiCond.Always : imgui.flag.ImGuiCond.FirstUseEver));
        ImGui.setNextWindowSizeConstraints(220 * sc, 160 * sc, 4000, 4000);
        boolean visible = Ui.beginWindow("01 SCENE###Scene", OPEN, 0, 0.96f);
        if (visible) {
            drawContent(s);
        }
        Ui.endWindow();
        s.showScene = OPEN.get();
    }

    private static void drawContent(EditorSession s) {
        float sc = Ui.s();
        TreeModel tree = s.tree;
        Set<UUID> issues = s.issues();

        // ---- search
        float clearW = SEARCH.isEmpty() ? 0 : 25 * sc;
        ImGui.setNextItemWidth(-1);
        if (s.focusFilter) {
            ImGui.setKeyboardFocusHere();
            s.focusFilter = false;
        }
        float sx = ImGui.getCursorScreenPosX(), sy = ImGui.getCursorScreenPosY();
        ImGui.pushStyleVar(ImGuiStyleVar.FramePadding, 22 * sc, 4.5f * sc);
        if (ImGui.inputTextWithHint("##filter", "filter... (Ctrl+F)", SEARCH)) tree.setSearch(SEARCH.get());
        ImGui.popStyleVar();
        float fh = ImGui.getItemRectSizeY(), fw = ImGui.getItemRectSizeX();
        Theme.push(UiHost.theme().mono());
        ImGui.getWindowDrawList().addText(sx + 8 * sc, sy + (fh - ImGui.getFontSize()) * 0.5f, Ui.c(Theme.ACCENT), ">");
        ImGui.popFont();
        if (!SEARCH.isEmpty()) {
            float bx = sx + fw - 26 * sc;
            ImGui.setCursorScreenPos(bx, sy + 2 * sc);
            if (ImGui.invisibleButton("##clearsearch", 24 * sc, fh - 4 * sc)) {
                SEARCH.set("");
                tree.setSearch("");
            }
            Theme.push(UiHost.theme().monoS());
            ImGui.getWindowDrawList().addText(bx + 2 * sc, sy + (fh - ImGui.getFontSize()) * 0.5f, Ui.c(ImGui.isItemHovered() ? Theme.ERR : Theme.GUTTER), "[×]");
            ImGui.popFont();
            ImGui.setCursorScreenPos(sx, sy + fh + ImGui.getStyle().getItemSpacingY());
        }

        // ---- chips
        drawChips(s, issues);

        // ---- tree
        float footerH = 25 * sc + 18 * sc;
        List<Row> rows = tree.rows(s.scene(), issues);
        ImGui.beginChild("##tree", 0, -footerH, false, ImGuiWindowFlags.NoNavInputs | ImGuiWindowFlags.NoNavFocus);
        boolean treeHovered = ImGui.isWindowHovered(imgui.flag.ImGuiHoveredFlags.ChildWindows);
        float childTop = ImGui.getWindowPosY(), childBottom = childTop + ImGui.getWindowHeight();
        if (rows.isEmpty() || (!tree.isFiltering() && s.scene().projects().isEmpty() && s.scene().loose().children().isEmpty())) {
            drawEmpty(s, tree);
        }
        boolean first = true;
        List<UUID> order = TreeModel.order(rows);
        for (int i = 0; i < rows.size(); i++) {
            Row r = rows.get(i);
            drawRow(s, tree, r, i, order, first);
            if (!tree.isFiltering() && !r.hasChildren() && (r.kind() == Kind.PROJECT || r.kind() == Kind.FOLDER) && tree.isOpen(r.node())) {
                float ind = (r.depth() + 1) * 14 * Ui.s() + 18 * Ui.s();
                // indent/unindent, not +/- on the cursor: a text item resets the cursor X to the line start, so
                // subtracting afterwards pushed every following row further left (the rename box landed over the icon).
                ImGui.indent(ind);
                Ui.smallDim("Empty. Right-click > Add, or drag objects here.");
                ImGui.unindent(ind);
            }
            first = false;
        }
        drawLegacyRows(s, tree);
        // drag scroll
        if (dragIds != null) {
            float my = ImGui.getMousePosY();
            if (my < childTop + 24 * sc && my > childTop - 20 * sc) ImGui.setScrollY(ImGui.getScrollY() - 8);
            else if (my > childBottom - 24 * sc && my < childBottom + 20 * sc) ImGui.setScrollY(ImGui.getScrollY() + 8);
        }
        if (dragIds != null && !ImGui.isMouseDown(0)) {
            dragIds = null;
            expandCandidate = null;
        }
        if (deferredSelect != null && ImGui.isMouseReleased(0)) {
            if (dragIds == null && Math.hypot(ImGui.getMouseDragDeltaX(0), ImGui.getMouseDragDeltaY(0)) < 4 * sc) s.selection.set(deferredSelect);
            deferredSelect = null;
        }
        ImGui.endChild();
        boolean focused = ImGui.isWindowFocused(ImGuiFocusedFlags.RootAndChildWindows);
        if (focused && renaming == null && !ImGui.getIO().getWantTextInput() && !s.popupOpen) treeKeys(s, tree, rows, order);

        // ---- footer
        ImDrawList dl = ImGui.getWindowDrawList();
        float fx = ImGui.getWindowPosX() + 1, fy = ImGui.getCursorScreenPosY() + 3 * sc;
        dl.addLine(fx, fy, fx + ImGui.getWindowWidth() - 2, fy, Ui.c(Theme.LINE2), 1f);
        ImGui.dummy(0, 8 * sc);
        float bw = (ImGui.getContentRegionAvailX() - ImGui.getStyle().getItemSpacingX()) * 0.5f;
        if (Ui.button("+ New project", true, true, bw, null) || s.newProjectRequest) {
            s.newProjectRequest = false;
            NEW_NAME.set("New project");
            focusNewName = true;
            ImGui.openPopup("New project##modal");
        }
        ImGui.sameLine();
        if (ImGui.button("Open project…", bw, 0) || s.openProjectRequest) {
            s.openProjectRequest = false;
            projectChoices = listProjects(s);
            ImGui.openPopup("Open project##modal");
        }
        if (s.addSchematicParent != null) {
            addParent = s.addSchematicParent;
            s.addSchematicParent = null;
            schematicChoices = listSchematics(s);
            schematicDirs = listSchematicDirs(s);
            schematicTree = SchemFolder.of(schematicChoices, schematicDirs);
            PICK_FILTER.set("");
            ImGui.openPopup("Add schematic##modal");
        }
        if (s.addAssemblyParent != null) {
            addParent = s.addAssemblyParent;
            s.addAssemblyParent = null;
            lasmChoices = s.listLasm();
            PICK_FILTER.set("");
            ImGui.openPopup("Add assembly##modal");
        }
        if (s.linkProjectParent != null) {
            addParent = s.linkProjectParent;
            s.linkProjectParent = null;
            linkChoices = listLinkable(s);
            ImGui.openPopup("Link project##modal");
        }
        drawModals(s);
    }

    private static boolean onlyEmptyRoots(List<Row> rows, EditorSession s) {
        return false;
    }

    // ------------------------------------------------------------------------------------------ chips

    private static void drawChips(EditorSession s, Set<UUID> issues) {
        float sc = Ui.s();
        TreeModel tree = s.tree;
        int nIssues = 0;
        for (UUID id : issues) if (s.scene().byId(id) != null) nIssues++;
        if (nIssues == 0 && tree.chip() == Chip.ISSUES) tree.setChip(Chip.ALL);
        List<Chip> chips = new ArrayList<>(List.of(Chip.ALL, Chip.SCHEMATICS, Chip.ASSEMBLIES, Chip.AREAS));
        if (nIssues > 0) chips.add(Chip.ISSUES);
        if (ImGui.getContentRegionAvailX() < 260 * sc) {
            ImGui.setNextItemWidth(-1);
            if (ImGui.beginCombo("##show", "Show: " + chipName(tree.chip(), nIssues))) {
                for (Chip c : chips) if (ImGui.selectable(chipName(c, nIssues), c == tree.chip())) tree.setChip(c);
                ImGui.endCombo();
            }
            return;
        }
        ImDrawList dl = ImGui.getWindowDrawList();
        float x = ImGui.getCursorScreenPosX(), y = ImGui.getCursorScreenPosY(), h = 22 * sc, gap = 4 * sc;
        float maxX = x + ImGui.getContentRegionAvailX();
        float cx = x;
        for (Chip c : chips) {
            String label = chipName(c, nIssues);
            float w = ImGui.calcTextSizeX(label) + 14 * sc;
            if (cx + w > maxX && cx > x) break;
            ImGui.setCursorScreenPos(cx, y);
            boolean hit = ImGui.invisibleButton("##chip" + c, w, h);
            boolean on = c == tree.chip(), hot = ImGui.isItemHovered();
            int fill = on ? (c == Chip.ISSUES ? Theme.ERR : Theme.SEL) : (hot ? Theme.SURFACE : Theme.BG);
            float fillA = on && c == Chip.ISSUES ? 0.22f : 1f;
            int brd = on ? (c == Chip.ISSUES ? Theme.ERR : Theme.ACCENT) : (hot ? Theme.BORDER_HI : Theme.LINE);
            dl.addRectFilled(cx, y, cx + w, y + h, Ui.c(fill, fillA), 0f);
            dl.addRect(cx, y, cx + w, y + h, Ui.c(brd), 0f);
            int rgb = on ? (c == Chip.ISSUES ? Theme.ERR : Theme.ACCENT) : hot ? Theme.FG : Theme.DIM;
            dl.addText(cx + 7 * sc, y + (h - ImGui.getFontSize()) * 0.5f, Ui.c(rgb), label);
            if (hit) tree.setChip(c);
            cx += w + gap;
        }
        ImGui.setCursorScreenPos(x, y + h + 4 * sc);
    }

    private static String chipName(Chip c, int issues) {
        return switch (c) {
            case ALL -> "All";
            case SCHEMATICS -> "Schematics";
            case ASSEMBLIES -> "Assemblies";
            case AREAS -> "Areas";
            case ISSUES -> "Issues (" + issues + ")";
        };
    }

    // ------------------------------------------------------------------------------------------ empty states

    private static void drawEmpty(EditorSession s, TreeModel tree) {
        float sc = Ui.s();
        boolean noProjects = s.scene().projects().isEmpty() && s.scene().loose().children().isEmpty();
        if (tree.isFiltering()) {
            ImGui.dummy(0, 24 * sc);
            centered("Nothing matches u201c" + (tree.search().isBlank() ? tree.chip().name().toLowerCase(Locale.ROOT) : tree.search()) + "u201d.", Theme.DIM);
            ImGui.dummy(0, 6 * sc);
            float cw = 120 * sc;
            ImGui.setCursorPosX((ImGui.getWindowWidth() - cw) * 0.5f);
            if (Ui.button("Clear filter", false, true, cw, null)) {
                SEARCH.set("");
                tree.setSearch("");
                tree.setChip(Chip.ALL);
            }
            return;
        }
        if (noProjects && !tree.isFiltering()) {
            ImGui.dummy(0, 30 * sc);
            centered("No projects open.", Theme.DIM);
            ImGui.dummy(0, 6 * sc);
            float bw = 150 * sc;
            ImGui.setCursorPosX((ImGui.getWindowWidth() - bw) * 0.5f);
            if (Ui.button("New project", true, true, bw, null)) s.newProjectRequest = true;
            ImGui.setCursorPosX((ImGui.getWindowWidth() - bw) * 0.5f);
            if (Ui.button("Open project…", false, true, bw, null)) s.openProjectRequest = true;
        }
    }

    private static void centered(String text, int rgb) {
        ImGui.setCursorPosX((ImGui.getWindowWidth() - ImGui.calcTextSizeX(text)) * 0.5f);
        ImGui.pushStyleColor(ImGuiCol.Text, Ui.c(rgb));
        ImGui.text(text);
        ImGui.popStyleColor();
    }

    // ------------------------------------------------------------------------------------------ rows

    /**
     * The rows other mods add through the 1.x list API ({@code VitruviusApi.registerListEntries}), under Loose:
     * read-only, dimmed, with their visibility shown. They are managed in Litematica's own placement list.
     */
    private static void drawLegacyRows(EditorSession s, TreeModel tree) {
        if (tree.isFiltering() || !tree.isOpen(s.scene().loose())) return;
        List<dev.vitruvius.api.ListEntry> entries = dev.vitruvius.api.VitruviusApi.allEntries();
        if (entries.isEmpty()) return;
        float sc = Ui.s();
        for (dev.vitruvius.api.ListEntry e : entries) {
            if (!e.shownInEditor()) continue;
            float rowH = 24 * sc;
            float x0 = ImGui.getCursorScreenPosX(), y0 = ImGui.getCursorScreenPosY(), w = ImGui.getContentRegionAvailX();
            ImDrawList dl = ImGui.getWindowDrawList();
            ImGui.pushID("legacy:" + e.id());
            ImGui.invisibleButton("##legacyrow", w, rowH);
            boolean hovered = ImGui.isItemHovered();
            if (hovered) dl.addRectFilled(x0, y0, x0 + w, y0 + rowH, Ui.c(Theme.SURFACE), 0f);
            float tx = x0 + 14 * sc, iconCx = tx + 16 * sc + 2 * sc + 7 * sc, nameX = iconCx + 7 * sc + 6 * sc;
            EditorIcons.draw(dl, "areas", iconCx, y0 + rowH * 0.5f, 7 * sc, Ui.c(Theme.GUTTER, 0.8f));
            float fh = ImGui.getFontSize();
            // right to left: the show/hide toggle, the cutaway ("slice") button when the entry has one, the 1.x tag
            String vis = e.isRenderingEnabled() ? "Shown" : "Hidden";
            float btnH = rowH - 4 * sc, by = y0 + 2 * sc, rx = x0 + w - 6 * sc;
            float visW = ImGui.calcTextSizeX(vis) + 14 * sc;
            rx -= visW;
            ImGui.setCursorScreenPos(rx, by);
            if (ImGui.invisibleButton("##legacyvis", visW, btnH)) e.setRenderingEnabled(!e.isRenderingEnabled());
            boolean visHot = ImGui.isItemHovered();
            legacyChip(dl, rx, by, visW, btnH, vis, e.isRenderingEnabled(), visHot, sc);
            if (visHot) ImGui.setTooltip("Show or hide this entry in the world");
            String cut = e.cutawayLabel();
            if (cut != null) {
                String cutText = "Slice: " + cut;
                float cw = ImGui.calcTextSizeX(cutText) + 14 * sc;
                rx -= cw + 4 * sc;
                ImGui.setCursorScreenPos(rx, by);
                if (ImGui.invisibleButton("##legacycut", cw, btnH)) e.cycleCutaway();
                boolean cutHot = ImGui.isItemHovered();
                legacyChip(dl, rx, by, cw, btnH, cutText, !"OFF".equals(cut), cutHot, sc);
                if (cutHot) ImGui.setTooltip("Terrain slice: ON (cut the terrain), WIREFRAME (outline only) or OFF. Click for the next.");
            }
            float rightEdge = rx - 8 * sc;
            dl.addText(nameX, y0 + (rowH - fh) * 0.5f, Ui.c(Theme.DIM, e.isRenderingEnabled() ? 1f : 0.6f), Ui.fitEnd(e.name(), Math.max(20 * sc, rightEdge - nameX)));
            if (hovered && !ImGui.isAnyItemActive()) ImGui.setTooltip("Added by another mod (1.x list). Its other settings live in Litematica's placement list.");
            ImGui.popID();
        }
    }

    private static void legacyChip(ImDrawList dl, float x, float y, float w, float h, String text, boolean on, boolean hot, float sc) {
        dl.addRectFilled(x, y, x + w, y + h, Ui.c(on ? Theme.SEL : (hot ? Theme.SURFACE : Theme.BG)), 0f);
        dl.addRect(x, y, x + w, y + h, Ui.c(on ? Theme.ACCENT : (hot ? Theme.BORDER_HI : Theme.LINE)), 0f);
        dl.addText(x + 7 * sc, y + (h - ImGui.getFontSize()) * 0.5f, Ui.c(on ? Theme.ACCENT : Theme.DIM), text);
    }

    private static int iconColor(Kind k) {
        return switch (k) {
            case PROJECT -> Theme.ACCENT;
            case FOLDER -> Ui.T_FOLDER;
            case OBJECT, ASSEMBLY -> Ui.T_OBJECT;
            case LINKED_ASSEMBLY -> Ui.LINK;
            case AREA -> Ui.AREA;
            case CUTTER -> Ui.BOOL;
            case LOOSE -> Theme.GUTTER;
        };
    }

    private static String iconName(Kind k) {
        return switch (k) {
            case PROJECT -> "project";
            case FOLDER -> "folder";
            case OBJECT -> "object";
            case ASSEMBLY, LINKED_ASSEMBLY -> "assembly";
            case AREA -> "areas";
            case CUTTER -> "cutter";
            case LOOSE -> "loose";
        };
    }

    private static boolean bold(Kind k) {
        return k == Kind.PROJECT || k == Kind.LOOSE;
    }

    /** A badge of a row, in the fixed order of DESIGN 7.2. */
    private record Badge(String icon, String text, int rgb, float alpha, int textRgb, boolean fill, String tip, boolean dot) {}

    private static List<Badge> badgesOf(EditorSession s, Row r) {
        List<Badge> out = new ArrayList<>();
        Node n = r.node();
        if (n instanceof ProjectNode p) {
            if (p.active()) out.add(new Badge("", "ACTIVE", Theme.ACCENT, 1f, Theme.ON_ACCENT, true, "The active project: new objects go here", false));
            if (p.sync() == ProjectNode.Sync.SHARED) out.add(new Badge("", "", Theme.OK, 1f, Theme.OK, false, "Shared on Syncmatica", true));
            return out;
        }
        if (r.missing() && n instanceof ExternalNode x) {
            out.add(new Badge("", "MOD", Theme.GUTTER, 0.3f, Theme.DIM, true, "Missing mod: " + x.typeId() + " is not installed. The object keeps its data "
                    + "and can still be moved, hidden and deleted.", false));
        } else if (r.missing()) {
            out.add(new Badge("", "!", Theme.ERR, 0.2f, Theme.ERR, true, "Placement not found. Litematica no longer has this placement. It comes back "
                    + "when the placement does; Delete removes it from the scene.", false));
        }
        if (n instanceof AssemblyNode a && a.isLinked()) {
            out.add(new Badge("link", "", Ui.LINK, 1f, Ui.LINK, false, "Linked: " + ((AssemblyNode.Link) a.source()).path(), false));
        }
        List<Modifier> mods = n instanceof ObjectNode o ? o.mods() : n instanceof AssemblyNode a ? a.mods() : List.of();
        if (!mods.isEmpty()) {
            List<ArrayModifier> arrays = new ArrayList<>();
            boolean anyBool = false, boolOn = false;
            StringBuilder tip = new StringBuilder("Modifiers, in order:");
            for (Modifier m : mods) {
                if (m instanceof ArrayModifier am) {
                    arrays.add(am);
                    tip.append("\nArray ×").append(am.count).append(am.enabled ? "" : " (off)");
                } else if (m instanceof BooleanModifier bm) {
                    anyBool = true;
                    boolOn |= bm.enabled;
                    tip.append("\nBoolean ").append(bm.op.name().toLowerCase(Locale.ROOT)).append(bm.enabled ? "" : " (off)");
                }
            }
            boolean arrOn = arrays.stream().anyMatch(a -> a.enabled);
            String text = arrays.isEmpty() ? "" : "×" + (arrays.stream().filter(a -> a.enabled).count() == 0 ? arrays.get(0).count : ArrayCells.copyCountOf(arrays) + 1);
            out.add(new Badge(arrays.isEmpty() ? "cutter" : "array", text, arrays.isEmpty() ? (boolOn ? Ui.BOOL : Theme.GUTTER) : (arrOn ? Theme.ACCENT : Theme.GUTTER),
                    1f, 0, true, tip.toString(), anyBool && !arrays.isEmpty()));
        }
        if (n instanceof HasFront f && !s.overlapsOf(n.id()).isEmpty()) {
            out.add(new Badge("", f.front() > 0 ? "P" + f.front() : "P-", Theme.FG, 0.12f, Theme.FG, true,
                    "Front order, not tree order, decides which object keeps overlapping blocks.", false));
        }
        var di = dev.vitruvius.editor.mc.DerivedFiles.shared().infoOf(n.id());
        if (di != null && di.status() == dev.vitruvius.editor.mc.DerivedFiles.Status.FAILED) {
            out.add(new Badge("", "!", Theme.ERR, 0.2f, Theme.ERR, true, "The derived file failed: " + di.error(), false));
        } else if (di != null && di.removed() > 0) {
            out.add(new Badge("", String.format(Locale.ROOT, "−%,d", di.removed()), Theme.ERR, 0.15f, Theme.ERR, true,
                    "Blocks removed by objects in front and Booleans", false));
        }
        return out;
    }

    private static float badgeWidth(Badge b, float sc) {
        if (b.dot()) return 7 * sc;
        Theme.push(UiHost.theme().monoS());
        float w = b.text().isEmpty() ? 0 : ImGui.calcTextSizeX(b.text());
        ImGui.popFont();
        if (!b.icon().isEmpty()) w += 11 * sc + (b.text().isEmpty() ? 0 : 3 * sc);
        return w + 10 * sc;
    }

    private static void drawRow(EditorSession s, TreeModel tree, Row r, int index, List<UUID> order, boolean first) {
        float sc = Ui.s();
        Node n = r.node();
        boolean root = r.kind() == Kind.PROJECT || r.kind() == Kind.LOOSE;
        if (root && !first) ImGui.dummy(0, 4 * sc);
        float rowH = (root ? 28 : 24) * sc;
        float x0 = ImGui.getCursorScreenPosX(), y0 = ImGui.getCursorScreenPosY(), w = ImGui.getContentRegionAvailX();
        ImDrawList dl = ImGui.getWindowDrawList();
        boolean selected = s.selection.isSelected(n.id()), active = n.id().equals(s.selection.active());
        boolean owner = false;
        if (!selected && s.selection.active() != null) {
            Node a = s.scene().byId(s.selection.active());
            owner = a != null && a != n && n instanceof AssemblyNode && isAncestor(n, a);
        }

        ImGui.pushID(n.id().toString());
        ImGui.setNextItemAllowOverlap();
        ImGui.invisibleButton("##row", w, rowH);
        boolean hovered = ImGui.isItemHovered();
        float mx = ImGui.getMousePosX(), my = ImGui.getMousePosY();
        float indent = r.depth() * 14 * sc;
        float tx = x0 + indent, iconCx = tx + 16 * sc + 2 * sc + 7 * sc, nameX = iconCx + 7 * sc + 6 * sc;
        float eyeX = x0 + w - 4 * sc - 20 * sc;
        boolean hasEye = r.kind() != Kind.LOOSE;
        boolean inTwisty = mx >= tx && mx < tx + 18 * sc, inEye = hasEye && mx >= eyeX && mx < eyeX + 20 * sc;
        boolean inIcon = mx >= tx + 18 * sc && mx < nameX - 4 * sc;

        // ---- background
        if (selected) {
            dl.addRectFilled(x0, y0, x0 + w, y0 + rowH, Ui.c(Theme.SEL, active ? 1f : 0.65f), 0f);
            if (active) dl.addRectFilled(x0, y0, x0 + 2 * sc, y0 + rowH, Ui.c(Theme.ACCENT), 0f);
        } else if (owner) {
            dl.addRectFilled(x0, y0, x0 + w, y0 + rowH, Ui.c(Theme.SEL, 0.35f), 0f);
        } else if (hovered && dragIds == null) {
            dl.addRectFilled(x0, y0, x0 + w, y0 + rowH, Ui.c(Theme.SURFACE), 0f);
        }

        // ---- input: press
        if (hovered && ImGui.isMouseClicked(0) && renaming == null) onPress(s, tree, r, order, inTwisty, inEye);
        if (hovered && ImGui.isMouseDoubleClicked(0) && !inTwisty && !inEye && renaming == null) {
            if (inIcon) s.actions.run("frame");
            else if (r.kind() != Kind.LOOSE && !r.locked()) beginRename(s, n);
        }
        if (hovered && ImGui.isMouseClicked(1)) {
            if (!selected) s.selection.set(n.id());
            ContextMenus.openForSelection(s, false);
        }

        // ---- twisty
        if (r.container() && r.hasChildren()) {
            EditorIcons.draw(dl, r.open() ? "chevdown" : "chev", tx + 8 * sc, y0 + rowH * 0.5f, 5 * sc, Ui.c(Theme.GUTTER));
        }

        // ---- icon
        float alpha = r.hidden() ? 0.45f : 1f;
        int ic = r.locked() ? Theme.GUTTER : iconColor(r.kind());
        if (selected && !r.locked()) ic = r.kind() == Kind.PROJECT ? Theme.ACCENT : ic;
        EditorIcons.draw(dl, iconName(r.kind()), iconCx, y0 + rowH * 0.5f, 7 * sc, Ui.c(ic, alpha));

        // ---- right side: eye / lock, then badges
        float right = x0 + w - 4 * sc;
        if (hasEye) {
            float ey = y0 + rowH * 0.5f;
            if (r.locked()) {
                EditorIcons.draw(dl, "lock", eyeX + 10 * sc, ey, 7 * sc, Ui.c(Theme.GUTTER));
            } else {
                boolean hot = hovered && inEye;
                String icon = n.hidden() ? "eyeoff" : "eye";
                int rgb = hot ? Theme.FG : n.hidden() ? Theme.DIM : Theme.GUTTER;
                EditorIcons.draw(dl, icon, eyeX + 10 * sc, ey, 7 * sc, Ui.c(rgb));
                if (hot) Ui.tooltip(n.hidden() ? "Show" : "Hide");
            }
            right = eyeX - 4 * sc;
        }
        List<Badge> badges = badgesOf(s, r);
        float totalW = 0;
        for (Badge b : badges) totalW += badgeWidth(b, sc) + 4 * sc;
        float nameRight = right - totalW - 4 * sc;
        if (nameRight - nameX < 56 * sc) {                                  // overflow: drop the quiet badges first
            while (!badges.isEmpty() && nameRight - nameX < 56 * sc && badges.size() > 1) {
                badges.remove(badges.size() > 2 ? 1 : 0);
                totalW = 0;
                for (Badge b : badges) totalW += badgeWidth(b, sc) + 4 * sc;
                nameRight = right - totalW - 4 * sc;
            }
        }
        float bx = right - totalW + 4 * sc;
        float by = y0 + (rowH - 17 * sc) * 0.5f;
        for (Badge b : badges) {
            float bw = badgeWidth(b, sc);
            drawBadge(dl, b, bx, by, bw, sc);
            if (hovered && mx >= bx && mx < bx + bw && !b.tip().isEmpty()) ImGui.setTooltip(b.tip());
            bx += bw + 4 * sc;
        }

        // ---- name
        int nameRgb = r.missing() ? Theme.DIM : r.locked() || r.ancestorOnly() ? Theme.DIM : selected && active ? 0xffffff : Theme.FG;
        float nameAlpha = r.missing() ? 0.6f : alpha;
        Theme.push(bold(r.kind()) ? UiHost.theme().uiBold() : UiHost.theme().ui());
        float fh = ImGui.getFontSize();
        float ty = y0 + (rowH - fh) * 0.5f;
        if (renaming != null && renaming.equals(n.id())) {
            ImGui.setCursorScreenPos(nameX - 4 * sc, y0 + 1 * sc);
            ImGui.setNextItemWidth(Math.max(40 * sc, nameRight - nameX + 4 * sc));
            if (renameFocus) {
                ImGui.setKeyboardFocusHere();
                renameFocus = false;
            }
            ImGui.pushStyleVar(ImGuiStyleVar.FramePadding, 6 * sc, 2 * sc);
            ImGui.pushStyleColor(ImGuiCol.FrameBg, Ui.c(Theme.PANEL2));
            ImGui.pushStyleColor(ImGuiCol.Border, Ui.c(Theme.ACCENT));
            ImGui.pushStyleVar(ImGuiStyleVar.FrameBorderSize, 1f);
            boolean enter = ImGui.inputText("##rename", RENAME, ImGuiInputTextFlags.EnterReturnsTrue | ImGuiInputTextFlags.AutoSelectAll);
            boolean lost = ImGui.isItemDeactivated();
            ImGui.popStyleVar(2);
            ImGui.popStyleColor(2);
            if (ImGui.isKeyPressed(ImGuiKey.Escape)) {
                renaming = null;
            } else if (enter || lost) {
                commitRename(s, n);
            }
        } else {
            dl.pushClipRect(nameX, y0, Math.max(nameX + 10, nameRight), y0 + rowH, true);
            drawName(dl, n.name(), nameX, ty, Ui.c(nameRgb, nameAlpha), r, sc);
            if (r.projectName() != null) {
                float nw = ImGui.calcTextSizeX(n.name());
                Theme.push(UiHost.theme().small());
                dl.addText(nameX + nw + 8 * sc, y0 + (rowH - ImGui.getFontSize()) * 0.5f, Ui.c(Theme.DIM), r.projectName());
                ImGui.popFont();
            }
            dl.popClipRect();
        }
        ImGui.popFont();

        if (hovered && r.missing() && n instanceof ObjectNode && !inEye) {
            boolean overBadge = false;
            if (!overBadge) ImGui.setTooltip("Placement not found: Litematica no longer has it. It is greyed out and not drawn. "
                    + "Right-click > Delete removes it from the scene.");
        }
        if (s.scrollToRow != null && s.scrollToRow.equals(n.id())) {
            ImGui.setScrollHereY(0.5f);
            s.scrollToRow = null;
        }
        if (s.renameRequest != null && s.renameRequest.equals(n.id())) {
            s.renameRequest = null;
            beginRename(s, n);
        }

        // ---- drag and drop
        dragAndDrop(s, tree, r, x0, y0, w, rowH, indent, sc);
        ImGui.popID();
    }

    private static boolean isAncestor(Node anc, Node n) {
        for (Node x = n.parent(); x != null; x = x.parent()) if (x == anc) return true;
        return false;
    }

    private static void drawBadge(ImDrawList dl, Badge b, float x, float y, float w, float sc) {
        float h = 17 * sc;
        if (b.dot()) {
            dl.addRectFilled(x, y + (h - 6 * sc) * 0.5f, x + 6 * sc, y + (h + 6 * sc) * 0.5f, Ui.c(b.rgb()), 0f);
            return;
        }
        if (b.fill()) {
            boolean mods = !b.icon().isEmpty();
            dl.addRectFilled(x, y, x + w, y + h, Ui.c(mods ? Theme.CHIP : b.rgb(), mods ? 1f : b.alpha()), 0f);
            dl.addRect(x, y, x + w, y + h, Ui.c(b.rgb(), mods ? 0.7f : 0.85f), 0f);
        }
        float cx = x + 5 * sc;
        if (!b.icon().isEmpty()) {
            EditorIcons.draw(dl, b.icon(), cx + 5.5f * sc, y + h * 0.5f, 5.5f * sc, Ui.c(b.rgb()));
            cx += 11 * sc + (b.text().isEmpty() ? 0 : 3 * sc);
        }
        if (!b.text().isEmpty()) {
            Theme.push(UiHost.theme().monoS());
            int rgb = b.textRgb() != 0 ? b.textRgb() : b.rgb();
            dl.addText(cx, y + (h - ImGui.getFontSize()) * 0.5f, Ui.c(rgb), b.text());
            ImGui.popFont();
        }
    }

    /** Draws a name; the search match in ACCENT (up to three runs). */
    private static void drawName(ImDrawList dl, String name, float x, float y, int col, Row r, float sc) {
        if (r.matchStart() < 0 || r.matchLen() <= 0 || r.matchStart() + r.matchLen() > name.length()) {
            dl.addText(x, y, col, name);
            return;
        }
        String a = name.substring(0, r.matchStart()), m = name.substring(r.matchStart(), r.matchStart() + r.matchLen()), z = name.substring(r.matchStart() + r.matchLen());
        dl.addText(x, y, col, a);
        float ax = x + ImGui.calcTextSizeX(a);
        dl.addText(ax, y, Ui.c(Theme.ACCENT), m);
        dl.addText(ax + ImGui.calcTextSizeX(m), y, col, z);
    }

    // ------------------------------------------------------------------------------------------ interaction

    private static void onPress(EditorSession s, TreeModel tree, Row r, List<UUID> order, boolean inTwisty, boolean inEye) {
        Node n = r.node();
        var io = ImGui.getIO();
        if (inTwisty && r.container() && r.hasChildren() && !tree.isFiltering()) {
            tree.setOpen(n, !tree.isOpen(n));
            if (n instanceof FolderNode f) f.setOpen(tree.isOpen(n));
            return;
        }
        if (inEye && r.kind() != Kind.LOOSE) {
            if (r.locked()) return;
            s.actions.execute(new SetHidden(List.of(n.id()), !n.hidden()));
            return;
        }
        // the host's own key state as well as ImGui's: ImGui's modifier flags are not always set by the host's input
        boolean shift = io.getKeyShift() || dev.cg.uihost.UiHost.isKeyDown(org.lwjgl.glfw.GLFW.GLFW_KEY_LEFT_SHIFT) || dev.cg.uihost.UiHost.isKeyDown(org.lwjgl.glfw.GLFW.GLFW_KEY_RIGHT_SHIFT);
        boolean ctrl = io.getKeyCtrl() || io.getKeySuper() || dev.cg.uihost.UiHost.isKeyDown(org.lwjgl.glfw.GLFW.GLFW_KEY_LEFT_CONTROL)
                || dev.cg.uihost.UiHost.isKeyDown(org.lwjgl.glfw.GLFW.GLFW_KEY_RIGHT_CONTROL) || dev.cg.uihost.UiHost.isKeyDown(org.lwjgl.glfw.GLFW.GLFW_KEY_LEFT_SUPER)
                || dev.cg.uihost.UiHost.isKeyDown(org.lwjgl.glfw.GLFW.GLFW_KEY_RIGHT_SUPER);
        if (shift) s.selection.range(order, n.id());                          // Shift: a range, as in a file manager
        else if (ctrl) s.selection.toggle(n.id());                            // Ctrl: add or remove one
        else if (s.selection.isSelected(n.id()) && s.selection.size() > 1) deferredSelect = n.id();
        else s.selection.set(n.id());
    }

    private static void beginRename(EditorSession s, Node n) {
        if (n instanceof LooseNode || TreeModel.lockedPart(n)) return;
        renaming = n.id();
        RENAME.set(n.name());
        renameFocus = true;
    }

    private static void commitRename(EditorSession s, Node n) {
        String text = RENAME.get().strip();
        renaming = null;
        if (text.isEmpty() || text.equals(n.name())) return;
        s.actions.execute(new Rename(n.id(), text));
        s.undoStack().breakMerge();
    }

    private static void dragAndDrop(EditorSession s, TreeModel tree, Row r, float x0, float y0, float w, float rowH, float indent, float sc) {
        Node n = r.node();
        boolean canDrag = r.kind() != Kind.PROJECT && r.kind() != Kind.LOOSE && !r.locked() && renaming == null;
        if (canDrag && ImGui.beginDragDropSource(ImGuiDragDropFlags.SourceNoHoldToOpenOthers)) {
            if (dragIds == null) dragIds = s.selection.isSelected(n.id()) ? s.selection.ids() : List.of(n.id());
            ImGui.setDragDropPayload("LA_NODES", dragIds);
            ImGui.text(dragIds.size() == 1 ? n.name() : dragIds.size() + " objects");
            ImGui.endDragDropSource();
        }
        if (dragIds == null || !ImGui.beginDragDropTarget()) return;
        Object payload = ImGui.acceptDragDropPayload("LA_NODES", ImGuiDragDropFlags.AcceptBeforeDelivery | ImGuiDragDropFlags.AcceptNoDrawDefaultRect);
        if (payload != null) {
            float frac = Math.max(0f, Math.min(1f, (ImGui.getMousePosY() - y0) / rowH));
            TreeRules.Zone zone = TreeRules.zone(frac, TreeRules.canContainRow(n));
            TreeRules.Plan plan = TreeRules.plan(s.context(), dragIds, n, zone);
            ImDrawList dl = ImGui.getWindowDrawList();
            if (!plan.valid()) {
                dl.addRectFilled(x0, y0, x0 + w, y0 + rowH, Ui.c(Theme.ERR, 0.14f), 0f);
                UiHost.setCursor(UiHost.Cursor.NOT_ALLOWED);
                ImGui.beginTooltip();
                ImGui.pushStyleColor(ImGuiCol.Text, Ui.c(Theme.ERR));
                ImGui.text(plan.refusal() == null ? "Cannot drop here" : plan.refusal());
                ImGui.popStyleColor();
                ImGui.endTooltip();
            } else if (zone == TreeRules.Zone.INTO) {
                dl.addRectFilled(x0, y0, x0 + w, y0 + rowH, Ui.c(Theme.SEL, 0.65f), 0f);
                dl.addRect(x0, y0, x0 + w, y0 + rowH, Ui.c(Theme.ACCENT), 0f);
                if (r.container() && r.hasChildren() && !tree.isOpen(n)) {
                    if (!n.id().equals(expandCandidate)) {
                        expandCandidate = n.id();
                        expandSince = ImGui.getTime();
                    } else if (ImGui.getTime() - expandSince > 0.6) {
                        tree.setOpen(n, true);
                    }
                }
            } else {
                float ly = zone == TreeRules.Zone.BEFORE ? y0 : y0 + rowH;
                float lx = x0 + indent + 18 * sc;
                dl.addLine(lx, ly, x0 + w, ly, Ui.c(Theme.ACCENT), 2f);
                dl.addCircle(lx, ly, 3 * sc, Ui.c(Theme.ACCENT), 12, 1.5f);
            }
            if (zone != TreeRules.Zone.INTO) expandCandidate = null;
            if (ImGui.isMouseReleased(0)) {
                if (plan.valid()) {
                    Command cmd = plan.command();
                    boolean ok = s.actions.execute(cmd);
                    if (ok && cmd instanceof MoveToProject) {
                        s.toasts.add(ToastQueue.Level.INFO, "Moved to another project. The file was moved, or copied when other objects use it", "Undo", s.actions::undo);
                    }
                    if (ok && zone == TreeRules.Zone.INTO) tree.setOpen(n, true);
                } else if (plan.refusal() != null) {
                    s.toasts.add(ToastQueue.Level.WARN, plan.refusal());
                }
                dragIds = null;
            }
        }
        ImGui.endDragDropTarget();
    }

    private static void treeKeys(EditorSession s, TreeModel tree, List<Row> rows, List<UUID> order) {
        UUID active = s.selection.active();
        int i = active == null ? -1 : order.indexOf(active);
        if (ImGui.isKeyPressed(ImGuiKey.DownArrow) && i + 1 < order.size()) {
            select(s, order.get(i + 1));
        } else if (ImGui.isKeyPressed(ImGuiKey.UpArrow) && i > 0) {
            select(s, order.get(i - 1));
        } else if (ImGui.isKeyPressed(ImGuiKey.RightArrow) && i >= 0) {
            Node n = rows.get(i).node();
            if (n.isContainer() && !tree.isOpen(n)) tree.setOpen(n, true);
            else if (i + 1 < order.size()) select(s, order.get(i + 1));
        } else if (ImGui.isKeyPressed(ImGuiKey.LeftArrow) && i >= 0) {
            Node n = rows.get(i).node();
            if (n.isContainer() && tree.isOpen(n) && !n.children().isEmpty()) tree.setOpen(n, false);
            else if (n.parent() != null) select(s, n.parent().id());
        } else if (ImGui.isKeyPressed(ImGuiKey.Space) && !s.selection.isEmpty()) {
            s.actions.run("hide");
        } else if (ImGui.isKeyPressed(ImGuiKey.Enter) && !s.selection.isEmpty()) {
            s.actions.run("frame");
        }
    }

    private static void select(EditorSession s, UUID id) {
        s.selection.set(id);
        s.scrollToRow = id;
    }

    // ------------------------------------------------------------------------------------------ modals

    /** "Shared projects": the [LA2] manifests Syncmatica lists on the server; a click downloads and opens one. */
    private static void drawSharedProjects(EditorSession s) {
        var sync = dev.vitruvius.editor.sync.SyncService.get();
        if (!sync.installed()) return;
        float sc = Ui.s();
        ImGui.dummy(0, 4 * sc);
        Ui.label("Shared projects (Syncmatica)");
        if (!sync.usable()) {
            Ui.dim(sync.reason());
            return;
        }
        if (!sync.connected()) {
            Ui.dim("Not connected to a server with Syncmatica.");
            return;
        }
        var list = sync.sharedProjectList();
        if (sync.receiving()) Ui.smallDim(sync.receiveStatus());
        if (list.isEmpty()) {
            Ui.dim("No shared projects on this server.");
            return;
        }
        ImGui.beginChild("##sharedlist", 320 * sc, Math.min(160 * sc, Math.max(40 * sc, list.size() * 28 * sc)), true);
        for (var r : list) {
            String name = dev.vitruvius.editor.sync.SyncManifest.projectName(r.name());
            if (ImGui.selectable(name + "##shared" + r.id()) && !sync.receiving()) {
                sync.receive(r, s.schematicsDir());
                ImGui.closeCurrentPopup();
            }
        }
        ImGui.endChild();
    }

    private static void drawModals(EditorSession s) {
        float sc = Ui.s();
        boolean any = false;
        ImGui.setNextWindowSizeConstraints(340 * sc, 0, 600 * sc, 600 * sc);
        if (ImGui.beginPopupModal("New project##modal", ImGuiWindowFlags.AlwaysAutoResize | ImGuiWindowFlags.NoSavedSettings)) {
            s.popupOpen = true;
            any = true;
            Ui.label("Name");
            ImGui.setNextItemWidth(-1);
            // focus the field until it is active (the first frame of a modal is hidden and swallows a one-shot request),
            // and select the proposed name so typing replaces it instead of appending to "New project"
            if (focusNewName) ImGui.setKeyboardFocusHere();
            boolean enter = ImGui.inputText("##pname", NEW_NAME, ImGuiInputTextFlags.EnterReturnsTrue | ImGuiInputTextFlags.AutoSelectAll);
            if (ImGui.isItemActive()) focusNewName = false;
            Ui.smallDim("The project is created in schematics/Projects and becomes the active project.");
            if (Ui.button("Create", true, !NEW_NAME.get().isBlank(), 100 * sc, "Give it a name") || (enter && !NEW_NAME.get().isBlank())) {
                try {
                    ProjectNode p = s.projects().newProject(NEW_NAME.get().strip());
                    s.projects().setActive(p.id());
                    s.selection.set(p.id());
                    s.toast(ToastQueue.Level.INFO, "Created project \"" + p.name() + "\"");
                } catch (RuntimeException e) {
                    s.toast(ToastQueue.Level.ERROR, "Cannot create the project: " + e.getMessage());
                }
                ImGui.closeCurrentPopup();
            }
            ImGui.sameLine();
            if (ImGui.button("Cancel", 100 * sc, 0) || ImGui.isKeyPressed(ImGuiKey.Escape)) ImGui.closeCurrentPopup();
            ImGui.endPopup();
        }
        ImGui.setNextWindowSizeConstraints(340 * sc, 0, 600 * sc, 600 * sc);
        if (ImGui.beginPopupModal("Open project##modal", ImGuiWindowFlags.AlwaysAutoResize | ImGuiWindowFlags.NoSavedSettings)) {
            s.popupOpen = true;
            Ui.label("Projects in schematics/Projects");
            if (projectChoices.isEmpty()) Ui.dim("No closed projects found.");
            ImGui.beginChild("##projlist", 320 * sc, Math.min(240 * sc, Math.max(40 * sc, projectChoices.size() * 28 * sc)), true);
            for (String name : projectChoices) {
                if (ImGui.selectable(name + "##p" + name)) {
                    try {
                        ProjectNode p = s.projects().open(s.schematicsDir().resolve("Projects").resolve(name));
                        s.selection.set(p.id());
                        s.toast(ToastQueue.Level.INFO, "Opened project \"" + p.name() + "\"");
                    } catch (IOException | RuntimeException e) {
                        s.toast(ToastQueue.Level.ERROR, "Cannot open \"" + name + "\": " + e.getMessage());
                    }
                    ImGui.closeCurrentPopup();
                }
            }
            ImGui.endChild();
            drawSharedProjects(s);
            if (ImGui.button("Cancel", 100 * sc, 0) || ImGui.isKeyPressed(ImGuiKey.Escape)) ImGui.closeCurrentPopup();
            ImGui.endPopup();
        }
        ImGui.setNextWindowSizeConstraints(420 * sc, 0, 700 * sc, 600 * sc);
        if (ImGui.beginPopupModal("Add schematic##modal", ImGuiWindowFlags.AlwaysAutoResize | ImGuiWindowFlags.NoSavedSettings)) {
            s.popupOpen = true;
            Ui.label("Schematics in Litematica's folder");
            ImGui.setNextItemWidth(-1);
            ImGui.inputTextWithHint("##pickfilter", "Filter", PICK_FILTER);
            String f = PICK_FILTER.get().toLowerCase(Locale.ROOT);
            ImGui.beginChild("##schemlist", 400 * sc, 260 * sc, true);
            int shown = 0;
            if (f.isEmpty()) {
                // folder tree: folders first (collapsed), then the files of each folder by name
                if (drawSchemFolder(s, schematicTree)) ImGui.closeCurrentPopup();
                shown = schematicChoices.size();
            } else {
                // filtering: a flat list of matching paths, so a match deep in a folder is found at once
                for (String path : schematicChoices) {
                    if (!path.toLowerCase(Locale.ROOT).contains(f)) continue;
                    if (++shown > 400) break;
                    if (ImGui.selectable(path + "##s" + path)) {
                        addSchematic(s, path);
                        ImGui.closeCurrentPopup();
                    }
                }
            }
            if (shown == 0) Ui.dim("No schematics found.");
            ImGui.endChild();
            if (ImGui.button("Cancel", 100 * sc, 0) || ImGui.isKeyPressed(ImGuiKey.Escape)) ImGui.closeCurrentPopup();
            ImGui.endPopup();
        }
        ImGui.setNextWindowSizeConstraints(420 * sc, 0, 700 * sc, 600 * sc);
        if (ImGui.beginPopupModal("Add assembly##modal", ImGuiWindowFlags.AlwaysAutoResize | ImGuiWindowFlags.NoSavedSettings)) {
            s.popupOpen = true;
            Ui.label("Assemblies (.lasm) in the active project and the schematics folder");
            ImGui.setNextItemWidth(-1);
            ImGui.inputTextWithHint("##lasmfilter", "Filter", PICK_FILTER);
            String f = PICK_FILTER.get().toLowerCase(Locale.ROOT);
            ImGui.beginChild("##lasmlist", 400 * sc, 260 * sc, true);
            int shown = 0;
            for (Path p : lasmChoices) {
                String label = s.schematicsDir().relativize(p).toString().replace(java.io.File.separatorChar, '/');
                if (!f.isEmpty() && !label.toLowerCase(Locale.ROOT).contains(f)) continue;
                if (++shown > 400) break;
                if (ImGui.selectable(label + "##l" + label)) {
                    UUID parent = addParent != null && s.scene().byId(addParent) != null ? addParent : activeOrLoose(s);
                    s.linkAssembly(parent, p);
                    ImGui.closeCurrentPopup();
                }
            }
            if (shown == 0) Ui.dim("No .lasm files found. Save an assembly as linked, or export one.");
            ImGui.endChild();
            Ui.smallDim("The assembly is linked: it updates when its file is saved. Make it local to edit it.");
            if (ImGui.button("Cancel", 100 * sc, 0) || ImGui.isKeyPressed(ImGuiKey.Escape)) ImGui.closeCurrentPopup();
            ImGui.endPopup();
        }
        ImGui.setNextWindowSizeConstraints(420 * sc, 0, 700 * sc, 600 * sc);
        if (ImGui.beginPopupModal("Link project##modal", ImGuiWindowFlags.AlwaysAutoResize | ImGuiWindowFlags.NoSavedSettings)) {
            s.popupOpen = true;
            Ui.label("Link a project as an assembly");
            if (linkChoices.isEmpty()) Ui.dim("There is no other project to link.");
            ImGui.beginChild("##linklist", 400 * sc, Math.min(260 * sc, Math.max(40 * sc, linkChoices.size() * 28 * sc)), true);
            for (ProjectNode p : linkChoices) {
                if (ImGui.selectable(p.name() + "##lp" + p.id())) {
                    UUID parent = addParent != null && s.scene().byId(addParent) != null ? addParent : activeOrLoose(s);
                    s.linkProject(parent, p);
                    ImGui.closeCurrentPopup();
                }
            }
            ImGui.endChild();
            if (ImGui.button("Cancel", 100 * sc, 0) || ImGui.isKeyPressed(ImGuiKey.Escape)) ImGui.closeCurrentPopup();
            ImGui.endPopup();
        }
    }

    private static UUID activeOrLoose(EditorSession s) {
        return s.scene().activeProject() != null ? s.scene().activeProject().id() : s.scene().loose().id();
    }

    /** Projects that can be linked into the parent's project: the open ones and the closed ones in schematics/Projects, but not that project itself. */
    private static List<ProjectNode> listLinkable(EditorSession s) {
        List<ProjectNode> out = new ArrayList<>();
        Node parent = addParent == null ? null : s.scene().byId(addParent);
        ProjectNode own = parent == null ? s.scene().activeProject() : s.scene().projectOf(parent);
        Set<Path> seen = new java.util.HashSet<>();
        for (ProjectNode p : s.scene().projects()) {
            if (p == own) continue;
            out.add(p);
            seen.add(s.projectDir(p));
        }
        Path root = s.schematicsDir().resolve("Projects");
        try (Stream<Path> st = Files.list(root)) {
            st.filter(Files::isDirectory).filter(d -> Files.exists(d.resolve("project.json"))).sorted().forEach(d -> {
                Path abs = d.toAbsolutePath().normalize();
                if (seen.contains(abs) || (own != null && s.projectDir(own).equals(abs))) return;
                try {
                    out.add(dev.vitruvius.editor.io.ProjectIo.read(d.resolve("project.json")));
                } catch (IOException | RuntimeException ignored) {
                    // an unreadable project cannot be linked
                }
            });
        } catch (IOException ignored) {
            // no Projects folder yet
        }
        return out;
    }

    /** A schematic, or a file type another mod registered (Heavymatica: .heavymatic). */
    private static boolean isAddable(String lowerName) {
        if (lowerName.endsWith(".litematic")) return true;
        for (var a : dev.vitruvius.api.VitruviusApi.assetTypes()) if (lowerName.endsWith(a.extension())) return true;
        return false;
    }

    private static void addSchematic(EditorSession s, String path) {
        String lower = path.toLowerCase(Locale.ROOT);
        for (var a : dev.vitruvius.api.VitruviusApi.assetTypes()) {
            if (!lower.endsWith(a.extension())) continue;
            var tg = dev.vitruvius.editor.cam.EditorCamera.get().target();
            try {
                a.place().accept(s.schematicsDir().resolve(path).normalize(), new net.minecraft.core.BlockPos((int) Math.floor(tg.x), (int) Math.floor(tg.y), (int) Math.floor(tg.z)));
                s.toast(ToastQueue.Level.INFO, "Placed \"" + dev.vitruvius.editor.ops.FileSystemPort.nameOf(path) + "\" at the camera target (" + a.label() + ")");
            } catch (RuntimeException e) {
                s.toast(ToastQueue.Level.WARN, "Could not place " + path + ": " + e.getMessage());
            }
            return;
        }
        var pos = dev.vitruvius.editor.cam.EditorCamera.get().target();
        dev.vitruvius.editor.core.Vec3i p = new dev.vitruvius.editor.core.Vec3i((int) Math.floor(pos.x), (int) Math.floor(pos.y), (int) Math.floor(pos.z));
        UUID parent = addParent != null && s.scene().byId(addParent) != null ? addParent : (s.scene().activeProject() != null ? s.scene().activeProject().id() : s.scene().loose().id());
        AddObject cmd = new AddObject(parent, -1, null, path, p);
        if (s.actions.execute(cmd)) {
            s.selection.set(cmd.objectId());
            s.actions.run("frame");
            Node n = s.scene().byId(cmd.objectId());
            if (n != null) s.tree.reveal(n);
            s.scrollToRow = cmd.objectId();
            s.toast(ToastQueue.Level.INFO, "Added \"" + (n == null ? path : n.name()) + "\" at the camera target");
        }
    }

    /** The folders in schematics/Projects that hold a project.json and are not open. */
    private static List<String> listProjects(EditorSession s) {
        List<String> out = new ArrayList<>();
        Path root = s.schematicsDir().resolve("Projects");
        try (Stream<Path> st = Files.list(root)) {
            st.filter(Files::isDirectory).filter(d -> Files.exists(d.resolve("project.json"))).sorted(Comparator.comparing(d -> d.getFileName().toString().toLowerCase(Locale.ROOT)))
                    .forEach(d -> {
                        boolean open = false;
                        for (ProjectNode p : s.scene().projects()) {
                            try {
                                if (Path.of(p.path()).toAbsolutePath().normalize().equals(d.toAbsolutePath().normalize())) open = true;
                                else if (s.schematicsDir().resolve(p.path()).normalize().equals(d.toAbsolutePath().normalize())) open = true;
                            } catch (RuntimeException ignored) {
                                // odd path: not the same
                            }
                        }
                        if (!open) out.add(d.getFileName().toString());
                    });
        } catch (IOException ignored) {
            // no Projects folder yet
        }
        return out;
    }

    /** .litematic files below the schematics folder (not inside Projects), as paths relative to it, up to 4 levels. */
    /** The Add schematic picker's folder tree: sub folders by name, then the schematic paths directly in it. */
    private static final class SchemFolder {
        final java.util.TreeMap<String, SchemFolder> dirs = new java.util.TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        final List<String> files = new ArrayList<>();
        int total;

        static SchemFolder of(List<String> paths, List<String> allDirs) {
            SchemFolder root = new SchemFolder();
            for (String dir : allDirs) {   // every folder on disk, also empty ones, like Litematica's own Load Schematic screen
                SchemFolder d = root;
                for (String part : dir.split("/")) d = d.dirs.computeIfAbsent(part, k -> new SchemFolder());
            }
            for (String p : paths) {
                String[] parts = p.split("/");
                SchemFolder d = root;
                d.total++;
                for (int i = 0; i < parts.length - 1; i++) {
                    d = d.dirs.computeIfAbsent(parts[i], k -> new SchemFolder());
                    d.total++;
                }
                d.files.add(p);
            }
            return root;
        }
    }

    /** Draws one folder level of the picker; true when a schematic was added (the caller closes the popup). */
    private static boolean drawSchemFolder(EditorSession s, SchemFolder folder) {
        boolean added = false;
        for (var e : folder.dirs.entrySet()) {
            String name = e.getKey();
            ImGui.pushID(name);
            boolean open = ImGui.treeNodeEx("##dir", imgui.flag.ImGuiTreeNodeFlags.SpanAvailWidth, name + "/");
            ImGui.sameLine();
            Ui.dim(String.valueOf(e.getValue().total));
            if (open) {
                added |= drawSchemFolder(s, e.getValue());
                ImGui.treePop();
            }
            ImGui.popID();
        }
        for (String path : folder.files) {
            String name = path.substring(path.lastIndexOf('/') + 1);
            if (ImGui.selectable(name + "##s" + path)) {
                addSchematic(s, path);
                added = true;
            }
        }
        return added;
    }

    /** Every folder below the schematics folder (not Projects, not hidden), relative, so the picker mirrors the disk. */
    private static List<String> listSchematicDirs(EditorSession s) {
        List<String> out = new ArrayList<>();
        Path root = s.schematicsDir();
        try (Stream<Path> st = Files.walk(root, 16)) {
            st.filter(Files::isDirectory).forEach(p -> {
                String rel = root.relativize(p).toString().replace(java.io.File.separatorChar, '/');
                if (!rel.isEmpty() && !rel.equals("Projects") && !rel.startsWith("Projects/") && !rel.startsWith(".") && !rel.contains("/.")) out.add(rel);
            });
        } catch (IOException ignored) {
            // unreadable folder: the picker then shows only folders that hold schematics
        }
        return out;
    }

    private static List<String> listSchematics(EditorSession s) {
        List<String> out = new ArrayList<>();
        Path root = s.schematicsDir();
        try (Stream<Path> st = Files.walk(root, 16)) {
            st.filter(p -> isAddable(p.getFileName().toString().toLowerCase(Locale.ROOT))).forEach(p -> {
                String rel = root.relativize(p).toString().replace(java.io.File.separatorChar, '/');
                if (!rel.startsWith("Projects/") && !rel.contains("/.")) out.add(rel);
            });
        } catch (IOException ignored) {
            // unreadable folder: an empty list says so
        }
        out.sort(String.CASE_INSENSITIVE_ORDER);
        return out;
    }
}
