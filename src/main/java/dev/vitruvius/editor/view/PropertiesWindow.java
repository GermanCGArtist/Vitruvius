package dev.vitruvius.editor.view;

import dev.cg.uihost.Theme;
import dev.cg.uihost.UiHost;
import dev.vitruvius.editor.EditorSession;
import dev.vitruvius.editor.core.ArrayCells;
import dev.vitruvius.editor.core.ArrayModifier;
import dev.vitruvius.editor.core.AssemblyNode;
import dev.vitruvius.editor.core.BooleanModifier;
import dev.vitruvius.editor.mc.DerivedFiles;
import dev.vitruvius.editor.core.Box;
import dev.vitruvius.editor.core.ExternalNode;
import dev.vitruvius.editor.core.FolderNode;
import dev.vitruvius.editor.core.HasFront;
import dev.vitruvius.editor.core.LooseNode;
import dev.vitruvius.editor.core.Mirror;
import dev.vitruvius.editor.core.Modifier;
import dev.vitruvius.editor.core.Node;
import dev.vitruvius.editor.core.ObjectNode;
import dev.vitruvius.editor.core.Overlaps;
import dev.vitruvius.editor.core.ProjectNode;
import dev.vitruvius.editor.core.Transform;
import dev.vitruvius.editor.core.Vec3i;
import dev.vitruvius.editor.mc.SchematicInfo;
import dev.vitruvius.editor.ops.ModifierEdits;
import dev.vitruvius.editor.ops.Rename;
import dev.vitruvius.editor.ui.MenuModel;
import dev.vitruvius.editor.ui.ToastQueue;
import dev.vitruvius.editor.ui.TreeModel;
import imgui.ImDrawList;
import imgui.ImGui;
import imgui.flag.ImGuiCol;
import imgui.flag.ImGuiInputTextFlags;
import imgui.flag.ImGuiStyleVar;
import imgui.flag.ImGuiWindowFlags;
import imgui.type.ImBoolean;
import imgui.type.ImString;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * The Properties window (DESIGN 8): shows the active item with sections per kind: header with rename, Transform
 * (Location rows with steppers, rotation segments, Mirror), Blocks, Front order, the modifier stack, and the batch
 * view for several objects. Nothing is selected: the empty state with the "From Litematica?" card. Every change is
 * a command on the undo stack; held steppers and scrubbing merge into one undo step.
 */
public final class PropertiesWindow {
    private PropertiesWindow() {}

    private static final ImBoolean OPEN = new ImBoolean(true);
    private static final ImString NAME = new ImString(128);
    private static UUID nameFor;
    private static final Set<String> COLLAPSED = new HashSet<>();
    private static final String[] ROT = {"0°", "90°", "180°", "270°"};
    private static final String[] AXES = {"X", "Y", "Z"};
    private static final int[] AXIS_COL = {Ui.AX_X, Ui.AX_Y, Ui.AX_Z};

    public static void draw(EditorSession s) {
        float sc = Ui.s();
        var vp = ImGui.getMainViewport();
        OPEN.set(s.showProperties);
        float h = vp.getSizeY() - 92 * sc;
        ImGui.setNextWindowPos(vp.getPosX() + vp.getSizeX() - 332 * sc, vp.getPosY() + 56 * sc, (s.resetLayout ? imgui.flag.ImGuiCond.Always : imgui.flag.ImGuiCond.FirstUseEver));
        ImGui.setNextWindowSize(320 * sc, Math.max(220 * sc, 0.58f * h), (s.resetLayout ? imgui.flag.ImGuiCond.Always : imgui.flag.ImGuiCond.FirstUseEver));
        ImGui.setNextWindowSizeConstraints(260 * sc, 160 * sc, 4000, 4000);
        if (Ui.beginWindow("Properties", OPEN, 0, 0.96f)) {
            drawContent(s);
        }
        Ui.endWindow();
        s.showProperties = OPEN.get();
    }

    private static Node activeNode(EditorSession s) {
        UUID a = s.selection.active();
        return a == null ? null : s.scene().byId(a);
    }

    private static void drawContent(EditorSession s) {
        Node n = activeNode(s);
        if (n == null) {
            drawEmpty(s);
            return;
        }
        if (s.selection.size() > 1) {
            drawBatch(s);
            return;
        }
        boolean locked = TreeModel.lockedPart(n);
        drawHeader(s, n, locked);
        ImGui.beginChild("##props_body", 0, 0, false, ImGuiWindowFlags.NoNavInputs);
        if (locked) lockedBanner(s, n);
        if (locked) ImGui.beginDisabled();
        if (n instanceof ProjectNode p) projectBody(s, p);
        else if (n instanceof LooseNode l) looseBody(s, l);
        else if (n instanceof FolderNode f) folderBody(s, f);
        else if (n instanceof ObjectNode o) objectBody(s, o, locked);
        else if (n instanceof AssemblyNode a) assemblyBody(s, a, locked);
        else if (n instanceof ExternalNode e) areaBody(s, e, locked);
        if (locked) ImGui.endDisabled();
        ImGui.endChild();
    }

    // ------------------------------------------------------------------------------------------ empty state

    private static void drawEmpty(EditorSession s) {
        float sc = Ui.s();
        ImGui.dummy(0, 16 * sc);
        centeredDim("Click an object in the world or in Scene.");
        ImGui.dummy(0, 2 * sc);
        ImDrawList dl = ImGui.getWindowDrawList();
        String[][] caps = {{"Shift"}, {"Alt"}, {"B"}};
        String[] verbs = {"add", "cycle overlapping", "box select"};
        Theme.push(UiHost.theme().small());
        float total = 0;
        for (int i = 0; i < caps.length; i++) total += Ui.capWidth(caps[i][0]) + 4 * sc + ImGui.calcTextSizeX(verbs[i]) + 12 * sc;
        float x = ImGui.getCursorScreenPosX() + Math.max(0, (ImGui.getContentRegionAvailX() - total) * 0.5f), y = ImGui.getCursorScreenPosY();
        for (int i = 0; i < caps.length; i++) {
            x = Ui.keyCap(dl, x, y, caps[i][0], Theme.DIM) + 4 * sc;
            dl.addText(x, y + (18 * sc - ImGui.getFontSize()) * 0.5f, Ui.c(Theme.DIM), verbs[i]);
            x += ImGui.calcTextSizeX(verbs[i]) + 12 * sc;
        }
        ImGui.popFont();
        ImGui.dummy(0, 26 * sc);
        if (!s.flag("hideFromLitematica", false)) fromLitematicaCard(s);
    }

    private static void centeredDim(String text) {
        ImGui.setCursorPosX(Math.max(0, (ImGui.getWindowWidth() - ImGui.calcTextSizeX(text)) * 0.5f));
        ImGui.pushStyleColor(ImGuiCol.Text, Ui.c(Theme.DIM));
        ImGui.text(text);
        ImGui.popStyleColor();
    }

    private static void fromLitematicaCard(EditorSession s) {
        float sc = Ui.s();
        String[][] rows = {
                {"Placement", "Object (G move, R rotate 90°)"},
                {"Overlapping placements", "Front order (P1 keeps the blocks)"},
                {"Repeated copies", "Array modifier (stays editable)"},
                {"Camera", "MMB orbit · hold RMB + WASD fly"}};
        ImDrawList dl = ImGui.getWindowDrawList();
        float x0 = ImGui.getCursorScreenPosX(), y0 = ImGui.getCursorScreenPosY(), w = ImGui.getContentRegionAvailX();
        Theme.push(UiHost.theme().small());
        float lh = ImGui.getFontSize() + 5 * sc, pad = 10 * sc;
        float h = pad * 2 + ImGui.getFontSize() + 6 * sc + rows.length * lh;
        dl.addRectFilled(x0, y0, x0 + w, y0 + h, Ui.c(Theme.PANEL2), 6 * sc);
        dl.addRect(x0, y0, x0 + w, y0 + h, Ui.c(Theme.LINE), 6 * sc);
        dl.addText(x0 + pad, y0 + pad, Ui.c(Theme.DIM), "FROM LITEMATICA?");
        float y = y0 + pad + ImGui.getFontSize() + 6 * sc;
        float leftW = 0;
        for (String[] r : rows) leftW = Math.max(leftW, ImGui.calcTextSizeX(r[0]));
        leftW = Math.min(leftW, w * 0.42f);
        for (String[] r : rows) {
            dl.pushClipRect(x0 + pad, y, x0 + pad + leftW, y + lh, true);
            dl.addText(x0 + pad, y, Ui.c(Theme.DIM), r[0]);
            dl.popClipRect();
            dl.pushClipRect(x0 + pad + leftW + 6 * sc, y, x0 + w - pad, y + lh, true);
            dl.addText(x0 + pad + leftW + 6 * sc, y, Ui.c(Theme.FG), "→ " + r[1]);
            dl.popClipRect();
            y += lh;
        }
        ImGui.popFont();
        ImGui.dummy(w, h);
        if (ImGui.smallButton("Hide this")) s.setFlag("hideFromLitematica", true);
        Ui.tooltip("The ? guide in the toolbar keeps this content");
    }

    // ------------------------------------------------------------------------------------------ header

    private static String typeIcon(Node n) {
        return switch (TreeModel.kindOf(n)) {
            case PROJECT -> "project";
            case LOOSE -> "loose";
            case FOLDER -> "folder";
            case OBJECT -> "object";
            case CUTTER -> "cutter";
            case ASSEMBLY, LINKED_ASSEMBLY -> "assembly";
            case AREA -> "areas";
        };
    }

    private static int typeColor(Node n) {
        return switch (TreeModel.kindOf(n)) {
            case PROJECT -> Theme.ACCENT;
            case LOOSE -> Theme.GUTTER;
            case FOLDER -> Ui.T_FOLDER;
            case OBJECT, ASSEMBLY -> Ui.T_OBJECT;
            case CUTTER -> Ui.BOOL;
            case LINKED_ASSEMBLY -> Ui.LINK;
            case AREA -> Ui.AREA;
        };
    }

    private static void drawHeader(EditorSession s, Node n, boolean locked) {
        float sc = Ui.s();
        ImDrawList dl = ImGui.getWindowDrawList();
        float x = ImGui.getCursorScreenPosX(), y = ImGui.getCursorScreenPosY();
        EditorIcons.draw(dl, typeIcon(n), x + 8 * sc, y + 12.5f * sc, 8 * sc, Ui.c(locked ? Theme.GUTTER : typeColor(n)));
        ImGui.setCursorScreenPos(x + 22 * sc, y);
        ImGui.setNextItemWidth(-1);
        if (n instanceof LooseNode) {
            ImGui.alignTextToFramePadding();
            Theme.push(UiHost.theme().uiBold());
            ImGui.text("Loose placements");
            ImGui.popFont();
        } else {
            if (!n.id().equals(nameFor)) {
                nameFor = n.id();
                NAME.set(n.name());
            } else if (!ImGui.isAnyItemActive() && !NAME.get().equals(n.name())) {
                NAME.set(n.name());
            }
            if (locked) ImGui.beginDisabled();
            Theme.push(UiHost.theme().uiBold());
            boolean enter = ImGui.inputText("##name", NAME, ImGuiInputTextFlags.EnterReturnsTrue | ImGuiInputTextFlags.AutoSelectAll);
            boolean lost = ImGui.isItemDeactivatedAfterEdit();
            ImGui.popFont();
            if (locked) ImGui.endDisabled();
            if (enter || lost) {
                String t = NAME.get().strip();
                if (!t.isEmpty() && !t.equals(n.name())) s.actions.execute(new Rename(n.id(), t));
                else NAME.set(n.name());
            }
        }
        // source line
        String src = sourceLine(s, n);
        if (src != null) {
            Theme.push(UiHost.theme().monoS());
            boolean linked = n instanceof AssemblyNode a && a.isLinked();
            float tx = ImGui.getCursorScreenPosX();
            String shown = Ui.fitMiddle(src, ImGui.getContentRegionAvailX() - (linked ? 18 * sc : 0));
            ImGui.pushStyleColor(ImGuiCol.Text, Ui.c(linked ? Ui.LINK : Theme.DIM));
            if (linked) {
                EditorIcons.draw(ImGui.getWindowDrawList(), "link", tx + 6 * sc, ImGui.getCursorScreenPosY() + ImGui.getFontSize() * 0.6f, 6 * sc, Ui.c(Ui.LINK));
                ImGui.setCursorScreenPos(tx + 16 * sc, ImGui.getCursorScreenPosY());
            }
            ImGui.text(shown);
            ImGui.popStyleColor();
            ImGui.popFont();
            if (ImGui.isItemHovered()) {
                ImGui.setTooltip(src);
                if (ImGui.isMouseClicked(0)) {
                    ImGui.setClipboardText(src);
                    s.toast(ToastQueue.Level.INFO, "Path copied");
                }
            }
        }
        ImGui.dummy(0, 2 * sc);
        ImGui.separator();
    }

    private static String sourceLine(EditorSession s, Node n) {
        if (n instanceof ObjectNode o) return o.file().isEmpty() ? null : o.file();
        if (n instanceof AssemblyNode a) return a.isLinked() ? ((AssemblyNode.Link) a.source()).path() : "Saved in this project";
        if (n instanceof ProjectNode p) return p.path();
        if (n instanceof ExternalNode e) return e.typeId();
        return null;
    }

    private static void lockedBanner(EditorSession s, Node part) {
        float sc = Ui.s();
        Node owner = s.scene().ownerOf(part);
        String path = owner instanceof AssemblyNode a && a.isLinked() ? ((AssemblyNode.Link) a.source()).path() : "";
        ImDrawList dl = ImGui.getWindowDrawList();
        float x0 = ImGui.getCursorScreenPosX(), y0 = ImGui.getCursorScreenPosY(), w = ImGui.getContentRegionAvailX();
        String text = "Part of linked assembly " + owner.name() + " (" + path + "). Read-only here.";
        float tw = w - 36 * sc;
        float lineH = ImGui.getFontSize();
        int lines = Math.max(1, (int) Math.ceil(ImGui.calcTextSizeX(text) / tw));
        float h = lines * lineH + 16 * sc;
        dl.addRectFilled(x0, y0, x0 + w, y0 + h, Ui.c(Theme.PANEL2), 6 * sc);
        dl.addRect(x0, y0, x0 + w, y0 + h, Ui.c(Ui.LINK, 0.5f), 6 * sc);
        EditorIcons.draw(dl, "lock", x0 + 16 * sc, y0 + 14 * sc, 7 * sc, Ui.c(Ui.LINK));
        ImGui.setCursorScreenPos(x0 + 30 * sc, y0 + 8 * sc);
        ImGui.pushTextWrapPos(ImGui.getCursorPosX() + tw);
        ImGui.text(text);
        ImGui.popTextWrapPos();
        ImGui.setCursorScreenPos(x0, y0 + h + 6 * sc);
        if (ImGui.button("Select assembly")) s.actions.run("part.select.owner");
        ImGui.sameLine();
        if (ImGui.button("Make assembly local")) s.actions.run("part.makelocal");
        ImGui.dummy(0, 4 * sc);
    }

    // ------------------------------------------------------------------------------------------ transform

    private static Box boxOf(EditorSession s, List<UUID> ids) {
        return s.boxes().unionOf(s.scene(), ids);
    }

    private static void transformSection(EditorSession s, Node n, List<UUID> ids, boolean single) {
        float sc = Ui.s();
        Ui.section("Transform");
        Box box = boxOf(s, ids);
        Vec3i loc = box != null ? box.min() : s.scene().worldTransform(n).pos();
        int[] cur = {loc.x(), loc.y(), loc.z()};
        boolean narrow = ImGui.getContentRegionAvailX() < 300 * sc;
        float labelW = narrow ? 0 : 80 * sc;
        for (int a = 0; a < 3; a++) {
            ImGui.pushID(a);
            if (a == 0) {
                if (narrow) Ui.label(single ? "Location" : "Pivot location");
                else {
                    ImGui.alignTextToFramePadding();
                    ImGui.pushStyleColor(ImGuiCol.Text, Ui.c(Theme.DIM));
                    ImGui.text(single ? "Location" : "Pivot");
                    ImGui.popStyleColor();
                    ImGui.sameLine(labelW);
                }
            } else if (!narrow) {
                ImGui.dummy(labelW, 0);
                ImGui.sameLine();
            }
            axisRow(s, a, cur);
            ImGui.popID();
        }
        if (single && !(n instanceof LooseNode)) {
            Transform t = n instanceof ObjectNode o ? o.transform() : n instanceof AssemblyNode as ? as.transform() : n instanceof ExternalNode e ? e.transform() : Transform.IDENTITY;
            ImGui.alignTextToFramePadding();
            ImGui.pushStyleColor(ImGuiCol.Text, Ui.c(Theme.DIM));
            ImGui.text("Rotation Y");
            ImGui.popStyleColor();
            if (!narrow) ImGui.sameLine(labelW);
            int hit = Ui.segmented("##rot", ROT, t.rot().steps(), ImGui.getContentRegionAvailX(), true);
            if (hit >= 0 && hit != t.rot().steps()) s.actions.rotate(hit - t.rot().steps());
            ImGui.alignTextToFramePadding();
            ImGui.pushStyleColor(ImGuiCol.Text, Ui.c(Theme.DIM));
            ImGui.text("Mirror");
            ImGui.popStyleColor();
            if (!narrow) ImGui.sameLine(labelW);
            int m = Ui.segmented("##mir", new String[] {"Mirror X"}, t.mirror() != Mirror.NONE ? 0 : -1, ImGui.getContentRegionAvailX(), true);
            Ui.tooltip("Mirror Z is Mirror X + 180°; it is in the Transform menu");
            if (m >= 0) s.actions.run("t.mirror");
        } else if (!single) {
            if (ImGui.button("↺ 90°")) s.actions.rotate(-1);
            ImGui.sameLine();
            if (ImGui.button("↻ 90°")) s.actions.rotate(1);
            ImGui.sameLine();
            if (ImGui.button("Mirror X")) s.actions.run("t.mirror");
        }
    }

    /** One axis row: [tag][drag field][-][+]. Changes are Move commands that merge into one undo step. */
    private static void axisRow(EditorSession s, int axis, int[] cur) {
        float sc = Ui.s();
        float btn = 25 * sc, tag = 18 * sc, gap = ImGui.getStyle().getItemSpacingX();
        float fieldW = Math.max(60 * sc, ImGui.getContentRegionAvailX() - tag - 2 * btn - 3 * gap);
        ImDrawList dl = ImGui.getWindowDrawList();
        float x = ImGui.getCursorScreenPosX(), y = ImGui.getCursorScreenPosY(), h = 25 * sc;
        dl.addRectFilled(x, y, x + tag, y + h, Ui.c(AXIS_COL[axis], 0.13f), 4 * sc);
        Theme.push(UiHost.theme().monoS());
        dl.addText(x + (tag - ImGui.calcTextSizeX(AXES[axis])) * 0.5f, y + (h - ImGui.getFontSize()) * 0.5f, Ui.c(AXIS_COL[axis]), AXES[axis]);
        ImGui.popFont();
        ImGui.dummy(tag, h);
        ImGui.sameLine();
        ImGui.setNextItemWidth(fieldW);
        int[] v = {cur[axis]};
        Theme.push(UiHost.theme().mono());
        boolean changed = ImGui.dragInt("##v", v, 0.25f);
        ImGui.popFont();
        boolean done = ImGui.isItemDeactivated();
        if (changed && v[0] != cur[axis]) s.actions.move(delta(axis, v[0] - cur[axis]));
        if (done) s.undoStack().breakMerge();
        ImGui.sameLine();
        ImGui.pushButtonRepeat(true);
        boolean minus = ImGui.button("−", btn, 0);
        boolean minusDone = ImGui.isItemDeactivated();
        ImGui.sameLine();
        boolean plus = ImGui.button("+", btn, 0);
        boolean plusDone = ImGui.isItemDeactivated();
        ImGui.popButtonRepeat();
        int step = ImGui.getIO().getKeyShift() ? 16 : 1;
        if (minus) s.actions.move(delta(axis, -step));
        if (plus) s.actions.move(delta(axis, step));
        if (minusDone || plusDone) s.undoStack().breakMerge();
    }

    private static Vec3i delta(int axis, int d) {
        return new Vec3i(axis == 0 ? d : 0, axis == 1 ? d : 0, axis == 2 ? d : 0);
    }

    // ------------------------------------------------------------------------------------------ object, assembly, area

    private static void objectBody(EditorSession s, ObjectNode o, boolean locked) {
        List<UUID> ids = List.of(o.id());
        transformSection(s, o, ids, true);
        blocksSection(s, o);
        if (!o.wire()) frontSection(s, o);
        else cutterSection(s, o);
        modifiersSection(s, o, o.mods(), true, o.wire() ? false : true, locked);
    }

    private static void assemblyBody(EditorSession s, AssemblyNode a, boolean locked) {
        float sc = Ui.s();
        transformSection(s, a, List.of(a.id()), true);
        Ui.section("Assembly");
        var linkedParts = a.isLinked() ? s.linkedParts(a, null) : null;
        ImGui.text("Parts: " + (a.isLinked() ? (linkedParts == null ? "(file unreadable)" : String.valueOf(linkedParts.parts().size())) : String.valueOf(a.children().size())));
        if (a.isLinked()) {
            ImGui.pushStyleColor(ImGuiCol.Text, Ui.c(Ui.LINK));
            ImGui.textWrapped(((AssemblyNode.Link) a.source()).path());
            ImGui.popStyleColor();
        } else {
            Ui.dim("Saved in this project");
        }
        if (a.isLinked() && ImGui.button("Make local")) s.actions.run("asm.makelocal");
        if (a.isLinked()) ImGui.sameLine();
        if (ImGui.button("Break up")) s.actions.run("asm.breakup");
        ImGui.sameLine();
        if (ImGui.button("Realise")) s.actions.run("asm.realise");
        Ui.tooltip("Break up into independent objects with their own file copies");
        if (a.isLinked()) {
            ImGui.sameLine();
            if (ImGui.button("Open source")) s.actions.run("asm.open");
            ImGui.sameLine();
            if (ImGui.button("Reload")) s.actions.run("asm.reload");
            Ui.tooltip("Read the linked file again");
        }
        if (!a.isLinked()) {
            ImGui.sameLine();
            if (ImGui.button("Save as linked")) s.actions.run("asm.savelasm");
            Ui.tooltip("Write the parts to files/assemblies/ as a .lasm and link it");
        }
        if (ImGui.button("Export .zip")) s.actions.run("export.zip");
        ImGui.sameLine();
        if (ImGui.button("Bake to .litematic")) s.actions.run("export.bake");
        frontSection(s, a);
        modifiersSection(s, a, a.mods(), true, true, locked);
    }

    private static void areaBody(EditorSession s, ExternalNode e, boolean locked) {
        transformSection(s, e, List.of(e.id()), true);
        var type = dev.vitruvius.api.ObjectTypes.get(e.typeId());
        Ui.section(type != null ? type.displayName() : "Missing mod");
        if (type == null) {
            Ui.colored(Theme.GUTTER, "The mod that provides \"" + e.typeId() + "\" is not installed.");
            Ui.smallDim("The object keeps its data and saves it unchanged. It can still be moved, hidden and deleted.");
        } else {
            com.google.gson.JsonObject work = e.data().deepCopy();
            ImPropertyUi ui = new ImPropertyUi();
            try {
                type.drawProperties(work, ui);
            } catch (RuntimeException | LinkageError ex) {
                Ui.colored(Theme.ERR, "The properties of " + e.typeId() + " failed: " + ex.getClass().getSimpleName());
            }
            if (ui.changed()) s.actions.execute(new dev.vitruvius.editor.ops.SetExternalData(e.id(), work));
        }
        modifiersSection(s, e, e.mods(), true, false, locked);
        ImGui.dummy(0, 6);
        ImGui.pushStyleColor(ImGuiCol.Text, Ui.c(Ui.AREA));
        ImGui.textWrapped("This kind of object never changes schematic blocks and takes no part in Booleans or the front order.");
        ImGui.popStyleColor();
    }

    /** The widgets of {@link dev.vitruvius.api.PropertyUi} with ImGui. */
    private static final class ImPropertyUi implements dev.vitruvius.api.PropertyUi {
        private boolean changed;

        private void labelRow(String label) {
            ImGui.alignTextToFramePadding();
            ImGui.pushStyleColor(ImGuiCol.Text, Ui.c(Theme.DIM));
            ImGui.text(label);
            ImGui.popStyleColor();
            ImGui.sameLine(110 * Ui.s());
            ImGui.setNextItemWidth(-1);
        }

        @Override
        public void section(String title) {
            Ui.section(title);
        }

        @Override
        public void text(String text) {
            ImGui.textWrapped(text);
        }

        @Override
        public void note(String text) {
            Ui.smallDim(text);
        }

        @Override
        public int intField(String label, int value) {
            labelRow(label);
            imgui.type.ImInt v = new imgui.type.ImInt(value);
            if (ImGui.inputInt("##" + label, v) && v.get() != value) {
                changed = true;
                return v.get();
            }
            return value;
        }

        @Override
        public double floatField(String label, double value) {
            labelRow(label);
            imgui.type.ImDouble v = new imgui.type.ImDouble(value);
            if (ImGui.inputDouble("##" + label, v) && v.get() != value) {
                changed = true;
                return v.get();
            }
            return value;
        }

        @Override
        public boolean checkbox(String label, boolean value) {
            ImBoolean v = new ImBoolean(value);
            if (ImGui.checkbox(label, v) && v.get() != value) {
                changed = true;
                return v.get();
            }
            return value;
        }

        @Override
        public String textField(String label, String value) {
            labelRow(label);
            ImString v = new ImString(value == null ? "" : value, 256);
            if (ImGui.inputText("##" + label, v) && !v.get().equals(value)) {
                changed = true;
                return v.get();
            }
            return value;
        }

        @Override
        public int combo(String label, int index, String... options) {
            labelRow(label);
            imgui.type.ImInt v = new imgui.type.ImInt(index);
            if (ImGui.combo("##" + label, v, options) && v.get() != index) {
                changed = true;
                return v.get();
            }
            return index;
        }

        @Override
        public boolean button(String label) {
            return ImGui.button(label);
        }

        @Override
        public boolean changed() {
            return changed;
        }

        @Override
        public void markChanged() {
            changed = true;
        }
    }

    private static void blocksSection(EditorSession s, ObjectNode o) {
        Ui.section("Blocks");
        Path f = s.fileOf(o);
        SchematicInfo.Info info = null;
        if (f != null) {
            try {
                info = SchematicInfo.shared().of(f);
            } catch (RuntimeException ignored) {
                // unreadable: shown as unknown below
            }
        }
        if (o.missing()) {
            Ui.colored(Theme.ERR, "Placement not found in Litematica.");
            return;
        }
        if (info == null) {
            Ui.dim(f == null ? "No file" : "The file cannot be read");
            return;
        }
        Theme.push(UiHost.theme().numM());
        long blocks = info.blockCount();                 // counted on a worker: -1 until it is ready
        ImGui.text(blocks < 0 ? "…" : String.format(Locale.ROOT, "%,d", blocks));
        ImGui.popFont();
        ImGui.sameLine();
        Ui.smallDim((blocks < 0 ? "counting · " : "counted · ") + info.size().x() + "×" + info.size().y() + "×" + info.size().z());
        DerivedFiles.Info di = DerivedFiles.shared().infoOf(o.id());
        if (di != null && di.status() == DerivedFiles.Status.BUILDING) {
            Ui.smallDim("Rebuilding the trimmed file…");
        } else if (di != null && di.removed() > 0) {
            Ui.colored(Theme.ERR, String.format(Locale.ROOT, "−%,d removed by front objects and Booleans", di.removed()));
        }
    }

    private static void frontSection(EditorSession s, Node n) {
        float sc = Ui.s();
        Ui.section("Front order");
        List<Overlaps.Overlap> ovs = s.overlapsOf(n.id());
        int front = n instanceof HasFront f ? f.front() : 0;
        if (!ovs.isEmpty()) {
            ImDrawList dl = ImGui.getWindowDrawList();
            Ui.badge(dl, ImGui.getCursorScreenPosX() + ImGui.getContentRegionAvailX() - 34 * sc, ImGui.getCursorScreenPosY() - 22 * sc,
                    front > 0 ? "P" + front : "P-", Theme.FG, 0.12f, Theme.FG);
        }
        float sq = 25 * sc;
        if (ImGui.button("Front")) s.actions.run("front.front");
        ImGui.sameLine();
        if (ImGui.button("↑", sq, 0)) s.actions.run("front.forward");
        ImGui.sameLine();
        if (ImGui.button("↓", sq, 0)) s.actions.run("front.backward");
        ImGui.sameLine();
        if (ImGui.button("Back")) s.actions.run("front.back");
        if (ovs.isEmpty()) {
            Ui.smallDim("No overlaps. When objects overlap, the one in front (P1) keeps the blocks.");
            return;
        }
        for (Overlaps.Overlap ov : ovs) {
            boolean loser = ov.loser().equals(n.id());
            UUID other = loser ? ov.winner() : ov.loser();
            Node on = s.scene().byId(other);
            if (on == null) continue;
            long vol = ov.box().volume();
            long real = loser ? s.lostTo(n.id(), other) : s.lostTo(other, n.id());
            String text = (loser ? "Loses " : "Wins ") + (real >= 0 ? "" : "up to ") + String.format(Locale.ROOT, "%,d", real >= 0 ? real : vol) + " blocks "
                    + (loser ? "to " : "over ");
            ImGui.pushStyleColor(ImGuiCol.Text, Ui.c(loser ? Theme.ERR : Theme.DIM));
            ImGui.text(text);
            ImGui.popStyleColor();
            ImGui.sameLine(0, 0);
            ImGui.pushStyleColor(ImGuiCol.Text, Ui.c(Theme.ACCENT));
            ImGui.text(on.name());
            ImGui.popStyleColor();
            if (ImGui.isItemHovered()) {
                UiHost.setCursor(UiHost.Cursor.HAND);
                ImGui.setTooltip("Select " + on.name() + " (the number counts the overlapping box, not non-air blocks)");
                if (ImGui.isMouseClicked(0)) {
                    s.selection.set(other);
                    s.tree.reveal(on);
                    s.scrollToRow = other;
                }
            }
        }
    }

    private static void cutterSection(EditorSession s, ObjectNode o) {
        Ui.section("Cutter");
        boolean any = false;
        for (Node x : s.scene().allNodes()) {
            List<Modifier> mods = x instanceof ObjectNode ox ? ox.mods() : x instanceof AssemblyNode ax ? ax.mods() : List.of();
            for (Modifier m : mods) {
                if (m instanceof BooleanModifier bm && o.id().equals(bm.targetId)) {
                    any = true;
                    ImGui.text("Cuts ");
                    ImGui.sameLine(0, 0);
                    ImGui.pushStyleColor(ImGuiCol.Text, Ui.c(Theme.ACCENT));
                    ImGui.text(x.name());
                    ImGui.popStyleColor();
                    if (ImGui.isItemHovered() && ImGui.isMouseClicked(0)) {
                        s.selection.set(x.id());
                        s.scrollToRow = x.id();
                    }
                    ImGui.sameLine(0, 0);
                    ImGui.text(" (" + bm.op.name().charAt(0) + bm.op.name().substring(1).toLowerCase(Locale.ROOT) + ")");
                }
            }
        }
        if (!any) Ui.dim("Nothing uses it as a cutter.");
        Ui.smallDim("Its own blocks are not counted, printed or pushed.");
        if (ImGui.smallButton("Show blocks (no longer a wire cutter)##unwire")) s.actions.execute(new dev.vitruvius.editor.ops.SetWire(o.id(), false));
    }

    // ------------------------------------------------------------------------------------------ project, folder, loose

    private static void projectBody(EditorSession s, ProjectNode p) {
        float sc = Ui.s();
        int objects = 0;
        for (Node x : p.subtree()) if (x instanceof ObjectNode) objects++;
        Theme.push(UiHost.theme().numM());
        ImGui.text(String.valueOf(objects));
        ImGui.popFont();
        ImGui.sameLine();
        Ui.smallDim("objects");
        Ui.section("Project");
        ImGui.text("World: " + (p.world().isEmpty() ? "(none)" : p.world()));
        ImGui.text("Origin: " + p.origin().x() + ", " + p.origin().y() + ", " + p.origin().z());
        ImGui.text("Syncmatica: " + (p.sync() == ProjectNode.Sync.SHARED ? "shared" : "local only"));
        ImGui.dummy(0, 4 * sc);
        if (p.active()) {
            Ui.colored(Theme.ACCENT, "Active project");
        } else if (ImGui.button("Make active")) {
            s.actions.run("proj.active");
        }
        ImGui.sameLine();
        var sync = dev.vitruvius.editor.sync.SyncService.get();
        if (sync.installed()) {
            boolean shared = p.sync() == ProjectNode.Sync.SHARED;
            if (shared) {
                int pend = sync.pending(p);
                if (Ui.button(pend > 0 ? "Push (" + pend + ")" : "Push", false, sync.usable() && sync.connected() && !sync.busy(),
                        0, sync.busy() ? "A push is running" : sync.reason())) s.actions.run("proj.push");
                ImGui.sameLine();
                boolean auto = sync.autoPush(p);
                if (ImGui.checkbox("Auto##syncauto", auto)) sync.setAutoPush(p, !auto);
                if (ImGui.isItemHovered()) ImGui.setTooltip("Push by itself a moment after you stop editing, and merge the changes of other players as they arrive");
                ImGui.sameLine();
            }
            if (Ui.button(shared ? "Stop sharing" : "Share", false, shared || sync.usable(), 0, sync.reason())) s.actions.run("proj.share");
        }
        ImGui.sameLine();
        if (ImGui.button("Export ▸")) ImGui.openPopup("##projexport");
        if (ImGui.beginPopup("##projexport")) {
            if (ImGui.menuItem("Project (.zip)")) s.actions.run("export.zip");
            if (ImGui.menuItem("Bake to one .litematic")) s.actions.run("export.bake");
            ImGui.endPopup();
        }
        ImGui.dummy(0, 4 * sc);
        Ui.smallDim("New objects go into the active project. Linking this project into another one places it there as an assembly.");
    }

    private static void folderBody(EditorSession s, FolderNode f) {
        float sc = Ui.s();
        int count = 0;
        for (Node x : f.subtree()) if (x instanceof ObjectNode) count++;
        Ui.section("Folder");
        ImGui.text("Items: " + f.children().size() + " (" + count + " objects)");
        ImGui.dummy(0, 4 * sc);
        if (ImGui.button("Make assembly")) s.actions.run("assembly.create");
        ImGui.sameLine();
        if (ImGui.button("Convert to project")) s.actions.run("folder.toproject");
        ImGui.sameLine();
        if (Ui.button("Export .zip", false, !f.children().isEmpty(), 0, "The folder is empty")) s.actions.run("export.zip");
        ImGui.dummy(0, 4 * sc);
        Ui.smallDim("Folders only organise. Front order decides which blocks count.");
    }

    private static void looseBody(EditorSession s, LooseNode l) {
        float sc = Ui.s();
        Ui.section("Loose");
        ImGui.text("Items: " + l.children().size());
        ImGui.dummy(0, 4 * sc);
        Ui.smallDim("Placements that belong to no project, as in plain Litematica. Drag them into a project, or convert this into one.");
        ImGui.dummy(0, 4 * sc);
        if (Ui.button("Convert to project…", false, !l.children().isEmpty(), 0, "Nothing is loose")) s.actions.run("loose.toproject");
    }

    // ------------------------------------------------------------------------------------------ modifiers

    private static void modifiersSection(EditorSession s, Node n, List<Modifier> mods, boolean allowArray, boolean allowBoolean, boolean locked) {
        float sc = Ui.s();
        Ui.section("Modifiers");
        for (int i = 0; i < mods.size(); i++) {
            ImGui.pushID(i);
            modifierCard(s, n, mods, i);
            ImGui.dummy(0, 2 * sc);
            ImGui.popID();
        }
        boolean hasArray = mods.stream().anyMatch(m -> m instanceof ArrayModifier);
        if (allowArray && Ui.button("+ Array", false, !hasArray, 0, "It has an array already")) s.actions.run("mod.array");
        if (allowArray && allowBoolean) ImGui.sameLine();
        if (allowBoolean && ImGui.button("+ Boolean")) s.actions.run("mod.boolean");
        if (!allowArray && !allowBoolean) Ui.dim("None");
    }

    private static void modifierCard(EditorSession s, Node n, List<Modifier> mods, int idx) {
        float sc = Ui.s();
        Modifier m = mods.get(idx);
        boolean isArray = m instanceof ArrayModifier;
        String key = n.id() + ":" + idx;
        boolean collapsed = COLLAPSED.contains(key);
        ImDrawList dl = ImGui.getWindowDrawList();
        dl.channelsSplit(2);
        dl.channelsSetCurrent(1);
        float x0 = ImGui.getCursorScreenPosX(), y0 = ImGui.getCursorScreenPosY(), w = ImGui.getContentRegionAvailX();
        ImGui.beginGroup();
        // ---- header (30 high)
        float hh = 30 * sc;
        boolean enabled = isArray ? ((ArrayModifier) m).enabled : ((BooleanModifier) m).enabled;
        int accent = enabled ? (isArray ? Theme.ACCENT : Ui.BOOL) : Theme.GUTTER;
        ImGui.setCursorScreenPos(x0 + 6 * sc, y0);
        ImGui.invisibleButton("##hdr", w - 80 * sc, hh);
        if (ImGui.isItemClicked(0)) {
            if (collapsed) COLLAPSED.remove(key);
            else COLLAPSED.add(key);
        }
        EditorIcons.draw(dl, "grip", x0 + 12 * sc, y0 + hh * 0.5f, 5 * sc, Ui.c(Theme.GUTTER));
        EditorIcons.draw(dl, collapsed ? "chev" : "chevdown", x0 + 26 * sc, y0 + hh * 0.5f, 5 * sc, Ui.c(Theme.GUTTER));
        EditorIcons.draw(dl, isArray ? "array" : "cutter", x0 + 44 * sc, y0 + hh * 0.5f, 7.5f * sc, Ui.c(accent));
        Theme.push(UiHost.theme().uiBold());
        String title = isArray ? "Array" : "Boolean";
        dl.addText(x0 + 58 * sc, y0 + (hh - ImGui.getFontSize()) * 0.5f, Ui.c(enabled ? Theme.FG : Theme.DIM), title);
        float tw = ImGui.calcTextSizeX(title);
        ImGui.popFont();
        if (collapsed) {
            String summary = isArray ? "×" + ((ArrayModifier) m).count + " along " + axisSummary((ArrayModifier) m)
                    : cap(((BooleanModifier) m).op.name()) + " · " + targetName(s, (BooleanModifier) m);
            Theme.push(UiHost.theme().small());
            dl.addText(x0 + 58 * sc + tw + 8 * sc, y0 + (hh - ImGui.getFontSize()) * 0.5f, Ui.c(Theme.DIM), Ui.fitEnd(summary, w - 58 * sc - tw - 100 * sc));
            ImGui.popFont();
        }
        ImGui.setCursorScreenPos(x0 + w - 62 * sc, y0 + (hh - 14 * sc) * 0.5f);
        if (Ui.toggle("##en", enabled)) {
            s.actions.execute(new ModifierEdits.SetModifierEnabled(n.id(), idx, !enabled));
            s.undoStack().breakMerge();
        }
        Ui.tooltip("Show this modifier in the world");
        ImGui.setCursorScreenPos(x0 + w - 30 * sc, y0 + (hh - 24 * sc) * 0.5f);
        if (Ui.iconButton("##more", "kebab", 24 * sc, false, true, null)) ImGui.openPopup("##modmenu");
        if (ImGui.beginPopup("##modmenu")) {
            if (isArray) {
                if (ImGui.menuItem("Realise into objects")) s.actions.run("mod.realise");
            } else {
                if (n instanceof ObjectNode on && ImGui.menuItem("Apply → new .litematic", "", false, !s.applying(on.id()))) s.applyBoolean(on, idx);
            }
            if (ImGui.menuItem("Move up", "", false, idx > 0)) s.actions.execute(new ModifierEdits.ReorderModifier(n.id(), idx, idx - 1));
            if (ImGui.menuItem("Move down", "", false, idx < mods.size() - 1)) s.actions.execute(new ModifierEdits.ReorderModifier(n.id(), idx, idx + 1));
            if (ImGui.menuItem("Duplicate", "", false, !isArray)) {
                s.actions.execute(new ModifierEdits.AddModifier(n.id(), ((BooleanModifier) m).copy(), idx + 1));
            }
            ImGui.separator();
            ImGui.pushStyleColor(ImGuiCol.Text, Ui.c(Theme.ERR));
            if (ImGui.menuItem("Remove")) s.actions.execute(new ModifierEdits.RemoveModifier(n.id(), idx));
            ImGui.popStyleColor();
            ImGui.endPopup();
        }
        ImGui.setCursorScreenPos(x0, y0 + hh);

        // ---- body
        if (!collapsed) {
            ImGui.indent(10 * sc);
            ImGui.dummy(0, 2 * sc);
            if (isArray) arrayBody(s, n, idx, (ArrayModifier) m);
            else booleanBody(s, n, idx, (BooleanModifier) m);
            ImGui.unindent(10 * sc);
            ImGui.dummy(0, 4 * sc);
        }
        ImGui.endGroup();
        float y1 = ImGui.getItemRectMaxY();
        dl.channelsSetCurrent(0);
        dl.addRectFilled(x0, y0, x0 + w, y1, Ui.c(Theme.PANEL2), 6 * sc);
        dl.addRect(x0, y0, x0 + w, y1, Ui.c(Theme.LINE), 6 * sc);
        dl.addRectFilled(x0, y0 + 2 * sc, x0 + 2 * sc, y1 - 2 * sc, Ui.c(accent), 1 * sc);
        dl.channelsMerge();
        ImGui.setCursorScreenPos(x0, y1);
        ImGui.dummy(w, 0);
    }

    private static String cap(String s) {
        return s.charAt(0) + s.substring(1).toLowerCase(Locale.ROOT);
    }

    private static String axisSummary(ArrayModifier a) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 3; i++) {
            double v = a.useRelative ? Math.abs(a.relative[i]) : 0;
            double c = a.useConstant ? Math.abs(a.constant[i]) : 0;
            if (v > 0 || c > 0) sb.append(AXES[i]);
        }
        return sb.isEmpty() ? "-" : sb.toString();
    }

    private static String targetName(EditorSession s, BooleanModifier b) {
        Node t = b.targetId == null ? null : s.scene().byId(b.targetId);
        return t == null ? "no object" : t.name();
    }

    private static void arrayBody(EditorSession s, Node n, int idx, ArrayModifier a) {
        float sc = Ui.s();
        // count
        int[] cnt = {a.count};
        ImGui.alignTextToFramePadding();
        ImGui.text("Count");
        ImGui.sameLine(70 * sc);
        float btn = ImGui.getFrameHeight();
        float gap = ImGui.getStyle().getItemInnerSpacingX();
        ImGui.setNextItemWidth(ImGui.getContentRegionAvailX() - 10 * sc - 2 * (btn + gap));
        if (ImGui.dragInt("##count", cnt, 0.2f, 1, ArrayCells.MAX_COPIES)) {
            ArrayModifier c = a.copy();
            c.count = Math.max(1, Math.min(ArrayCells.MAX_COPIES, cnt[0]));
            s.actions.execute(new ModifierEdits.EditModifier(n.id(), idx, "count", c));
        }
        if (ImGui.isItemDeactivated()) s.undoStack().breakMerge();
        int step = 0;
        ImGui.sameLine(0, gap);
        ImGui.beginDisabled(a.count <= 1);
        if (ImGui.button("-##countdec", btn, btn)) step = -1;
        ImGui.endDisabled();
        Ui.tooltip("One copy less");
        ImGui.sameLine(0, gap);
        ImGui.beginDisabled(a.count >= ArrayCells.MAX_COPIES);
        if (ImGui.button("+##countinc", btn, btn)) step = 1;
        ImGui.endDisabled();
        Ui.tooltip("One copy more");
        if (step != 0) {
            ArrayModifier c = a.copy();
            c.count = Math.max(1, Math.min(ArrayCells.MAX_COPIES, a.count + step));
            s.actions.execute(new ModifierEdits.EditModifier(n.id(), idx, "count", c));
            s.undoStack().breakMerge();
        }
        // mode
        ImGui.alignTextToFramePadding();
        ImGui.text("Offset");
        ImGui.sameLine(70 * sc);
        int mode = Ui.segmented("##mode", new String[] {"Relative", "Blocks"}, a.useRelative && !a.useConstant ? 0 : 1, ImGui.getContentRegionAvailX() - 10 * sc, true);
        if (mode >= 0) {
            ArrayModifier c = a.copy();
            c.useRelative = mode == 0;
            c.useConstant = mode == 1;
            s.actions.execute(new ModifierEdits.EditModifier(n.id(), idx, "mode", c));
            s.undoStack().breakMerge();
        }
        boolean rel = a.useRelative && !a.useConstant;
        for (int ax = 0; ax < 3; ax++) {
            ImGui.pushID(ax);
            ImDrawList dl = ImGui.getWindowDrawList();
            float x = ImGui.getCursorScreenPosX(), y = ImGui.getCursorScreenPosY(), tag = 18 * sc, h = 25 * sc;
            dl.addRectFilled(x, y, x + tag, y + h, Ui.c(AXIS_COL[ax], 0.13f), 4 * sc);
            Theme.push(UiHost.theme().monoS());
            dl.addText(x + (tag - ImGui.calcTextSizeX(AXES[ax])) * 0.5f, y + (h - ImGui.getFontSize()) * 0.5f, Ui.c(AXIS_COL[ax]), AXES[ax]);
            ImGui.popFont();
            ImGui.dummy(tag, h);
            ImGui.sameLine();
            ImGui.setNextItemWidth(ImGui.getContentRegionAvailX() - 10 * sc);
            Theme.push(UiHost.theme().mono());
            if (rel) {
                float[] f = {(float) a.relative[ax]};
                if (ImGui.dragFloat("##o", f, 0.02f, -64f, 64f, "%.1f")) {
                    ArrayModifier c = a.copy();
                    c.relative[ax] = Math.round(f[0] * 2.0) / 2.0;
                    s.actions.execute(new ModifierEdits.EditModifier(n.id(), idx, "relative" + ax, c));
                }
            } else {
                int[] v = {a.constant[ax]};
                if (ImGui.dragInt("##o", v, 0.25f)) {
                    ArrayModifier c = a.copy();
                    c.constant[ax] = v[0];
                    s.actions.execute(new ModifierEdits.EditModifier(n.id(), idx, "constant" + ax, c));
                }
            }
            ImGui.popFont();
            if (ImGui.isItemDeactivated()) s.undoStack().breakMerge();
            ImGui.popID();
        }
        // result line
        Vec3i size = sizeOfNode(s, n);
        if (size != null) {
            List<Vec3i> steps = ArrayCells.steps(List.of(a), size);
            Vec3i st = steps.isEmpty() ? Vec3i.ZERO : steps.get(0);
            int[] tot = new int[3];
            for (int i = 0; i < 3; i++) tot[i] = size.get(i) + Math.abs(st.get(i)) * (Math.max(1, a.count) - 1);
            Ui.smallDim("= " + st.x() + ", " + st.y() + ", " + st.z() + " blocks per copy · " + tot[0] + " × " + tot[1] + " × " + tot[2] + " total");
        }
        if (ImGui.button("Realise into " + a.count + " objects")) s.actions.run("mod.realise");
    }

    private static Vec3i sizeOfNode(EditorSession s, Node n) {
        if (n instanceof ObjectNode o) return s.sizeOf(o);
        if (n instanceof AssemblyNode a) return s.assemblySize(a);
        Box b = boxOf(s, List.of(n.id()));
        return b == null ? null : b.size();
    }

    private static void booleanBody(EditorSession s, Node n, int idx, BooleanModifier b) {
        float sc = Ui.s();
        ImGui.alignTextToFramePadding();
        ImGui.text("Operation");
        ImGui.sameLine(70 * sc);
        int op = Ui.segmented("##op", new String[] {"Difference", "Intersect"}, b.op.ordinal(), ImGui.getContentRegionAvailX() - 10 * sc, true);
        if (op >= 0 && op != b.op.ordinal()) {
            BooleanModifier c = b.copy();
            c.op = BooleanModifier.Op.values()[op];
            s.actions.execute(new ModifierEdits.EditModifier(n.id(), idx, "operation", c));
            s.undoStack().breakMerge();
        }
        ImGui.alignTextToFramePadding();
        ImGui.text("Object");
        ImGui.sameLine(70 * sc);
        ImGui.setNextItemWidth(ImGui.getContentRegionAvailX() - 40 * sc);
        if (ImGui.beginCombo("##target", targetName(s, b))) {
            ProjectNode proj = s.scene().projectOf(n);
            for (Node x : s.scene().allNodes()) {
                if (!(x instanceof ObjectNode o) || o == n || s.scene().projectOf(o) != proj) continue;
                boolean bad = false;
                for (Node a = n; a != null; a = a.parent()) if (a == o) bad = true;
                for (Node a = o.parent(); a != null; a = a.parent()) if (a == n) bad = true;
                if (bad) continue;
                if (ImGui.selectable(o.name() + "##t" + o.id(), o.id().equals(b.targetId))) {
                    BooleanModifier c = b.copy();
                    c.targetId = o.id();
                    s.actions.execute(new ModifierEdits.EditModifier(n.id(), idx, "target", c));
                    s.undoStack().breakMerge();
                }
            }
            ImGui.endCombo();
        }
        ImGui.sameLine();
        boolean armed = s.eyedropping();
        if (Ui.iconButton("##eyedrop", "capture", 25 * sc, armed, true, "Pick the cutter in the world: click an object, Esc cancels")) {
            if (armed) s.cancelEyedrop();
            else s.startEyedrop(n.id(), idx);
        }
        // status
        Node t = b.targetId == null ? null : s.scene().byId(b.targetId);
        boolean touches = true;
        if (t == null) {
            Ui.colored(Theme.WARN, "The cutter object is missing: nothing is removed.");
            touches = false;
        } else if (!b.enabled) {
            Ui.smallDim("Off: the cut is not shown in the world.");
        } else {
            Box own = boxOf(s, List.of(n.id())), cut = boxOf(s, List.of(t.id()));
            DerivedFiles.Info info = DerivedFiles.shared().infoOf(n.id());
            if (own != null && cut != null && own.intersect(cut).isEmpty()) {
                Ui.colored(Theme.WARN, "The cutter doesn't touch this object: nothing is removed.");
                touches = false;
            } else if (info != null && info.status() == DerivedFiles.Status.FAILED) {
                Ui.colored(Theme.ERR, "Rebuild failed" + (info.error() == null ? "" : ": " + info.error()));
                if (ImGui.smallButton("Retry")) DerivedFiles.shared().retryFailed();
            } else if (info == null || info.booleanRemoved() < 0) {
                Ui.smallDim("Rebuilding…");
            } else if (info.booleanRemoved() == 0) {
                Ui.colored(Theme.WARN, "The cutter doesn't touch this object: nothing is removed.");
                touches = false;
            } else {
                Ui.colored(Theme.OK, String.format(Locale.ROOT, "Removes ~%,d blocks", info.booleanRemoved()));
            }
        }
        if (t instanceof ObjectNode to && !(n instanceof AssemblyNode)) {
            String label = to.wire() ? "Show cutter blocks" : "Draw cutter as wire";
            if (ImGui.smallButton(label + "##wire")) s.actions.execute(new dev.vitruvius.editor.ops.SetWire(to.id(), !to.wire()));
            Ui.tooltip(to.wire() ? "The cutter's blocks are shown and counted again" : "Shows the cutter as a dotted outline only; its blocks are not placed, counted or trimmed");
        }
        if (n instanceof ObjectNode on) {
            boolean busy = s.applying(on.id());
            if (Ui.button(busy ? "Building…" : "Apply → new .litematic", true, !busy && t != null && touches && b.enabled,
                    0, busy ? "Building the file…" : "Needs an enabled Boolean whose cutter touches this object")) {
                s.applyBoolean(on, idx);
            }
            Ui.tooltip("Writes " + stem(on) + "_bool.litematic and replaces this object's file with it. Ctrl Z undoes.");
        }
    }

    private static String stem(ObjectNode o) {
        String f = o.file();
        int sl = Math.max(f.lastIndexOf('/'), f.lastIndexOf('\\'));
        if (sl >= 0) f = f.substring(sl + 1);
        int dot = f.lastIndexOf('.');
        return dot > 0 ? f.substring(0, dot) : f;
    }

    // ------------------------------------------------------------------------------------------ batch

    private static void drawBatch(EditorSession s) {
        float sc = Ui.s();
        List<UUID> ids = s.selection.ids();
        Box box = boxOf(s, ids);
        ImDrawList dl = ImGui.getWindowDrawList();
        Theme.push(UiHost.theme().uiBold());
        ImGui.text(ids.size() + " objects");
        ImGui.popFont();
        if (box != null) {
            Ui.smallDim(String.format(Locale.ROOT, "bounds %d × %d × %d", box.size().x(), box.size().y(), box.size().z()));
        }
        ImGui.separator();
        ImGui.beginChild("##props_batch", 0, 0, false, ImGuiWindowFlags.NoNavInputs);
        Node first = s.scene().byId(ids.get(0));
        transformSection(s, first == null ? s.scene().loose() : first, ids, false);
        Ui.section("Front order");
        float sq = 25 * sc;
        if (ImGui.button("Front")) s.actions.run("front.front");
        ImGui.sameLine();
        if (ImGui.button("↑", sq, 0)) s.actions.run("front.forward");
        ImGui.sameLine();
        if (ImGui.button("↓", sq, 0)) s.actions.run("front.backward");
        ImGui.sameLine();
        if (ImGui.button("Back")) s.actions.run("front.back");
        ImGui.dummy(0, 4 * sc);
        if (ImGui.button("Create assembly")) s.actions.run("assembly.create");
        ImGui.sameLine();
        if (ImGui.button("Hide")) s.actions.run("hide");
        ImGui.sameLine();
        ImGui.pushStyleColor(ImGuiCol.Text, Ui.c(Theme.ERR));
        if (ImGui.button("Delete")) s.actions.run("delete");
        ImGui.popStyleColor();
        Ui.section("Selection");
        for (UUID id : ids) {
            Node n = s.scene().byId(id);
            if (n == null) continue;
            boolean active = id.equals(s.selection.active());
            ImGui.pushStyleColor(ImGuiCol.Text, Ui.c(active ? Theme.ACCENT : Theme.FG));
            if (ImGui.selectable(n.name() + "##sel" + id, active)) s.selection.makeActive(id);
            ImGui.popStyleColor();
        }
        ImGui.endChild();
    }
}
