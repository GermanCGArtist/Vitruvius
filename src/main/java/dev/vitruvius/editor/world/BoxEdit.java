package dev.vitruvius.editor.world;

import dev.cg.uihost.Theme;
import dev.cg.uihost.UiHost;
import dev.vitruvius.api.ObjectTypes;
import dev.vitruvius.api.SceneObjectType;
import dev.vitruvius.editor.EditorMode;
import dev.vitruvius.editor.EditorSession;
import dev.vitruvius.editor.cam.EditorCamera;
import dev.vitruvius.editor.core.Box;
import dev.vitruvius.editor.core.ExternalNode;
import dev.vitruvius.editor.core.Transform;
import dev.vitruvius.editor.core.Vec3i;
import dev.vitruvius.editor.gizmo.AxisProjection;
import dev.vitruvius.editor.gizmo.FaceEdit;
import dev.vitruvius.editor.mc.Picking;
import dev.vitruvius.editor.ops.EditExternalBoxes;
import dev.vitruvius.editor.pick.ViewMath;
import dev.vitruvius.editor.ui.ToastQueue;
import dev.vitruvius.editor.view.Ui;
import imgui.ImDrawList;
import imgui.ImGui;
import net.minecraft.client.Minecraft;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Edit mode for objects of other mods (E): the boxes of the selected external object get handles on every side. Drag a
 * side to move it by whole blocks, Shift + drag to pull a new box out of it, click or hover a side and type a number to
 * set its exact length (or {@code +N}/{@code -N} to adjust relatively), X or Delete to delete the box under the pointer.
 * One drag or typed edit is one undo step. The object type owns the data (see {@code SceneObjectType.editBoxes}); this
 * class only picks sides, measures the drag or typed length and hands the result to the type.
 *
 * <p>While it is on, a press that does not hit a side falls through to normal picking, which ends the mode when it
 * selects something else. Esc, Enter or E leave it; Esc or a right click during a drag or typed edit cancels only that
 * edit.
 */
public final class BoxEdit {
    private static final double MAX_DIST = 512;
    private static final int MAX_HANDLES = 48;
    private static final String[] AXES = {"X", "Y", "Z"};

    private final EditorSession s;
    private UUID target;

    private FaceEdit.Face hover;
    private FaceEdit.Face selected;
    private boolean dragging, typing, extrude;
    private FaceEdit.Face grab;
    private double t0;
    private double[] p0;
    private int blocks;
    private Box grabLocal;
    private Transform grabWorld;
    private final FaceEdit.TypedLength typed = new FaceEdit.TypedLength();

    public BoxEdit(EditorSession s) {
        this.s = s;
    }

    // ------------------------------------------------------------------ queries

    public boolean active() {
        return target != null;
    }

    public boolean typedActive() {
        return target != null && (typing || (dragging && !typed.isEmpty()));
    }

    /** True while the pointer is on a side or a drag runs, so the pick hover outline stays off. */
    public boolean owningMouse() {
        return target != null && (dragging || hover != null);
    }

    /** Index of the currently active/selected box in edit mode, or -1. */
    public int selectedBox() {
        if (target == null) return -1;
        FaceEdit.Face f = activeFace();
        return f == null ? -1 : f.box();
    }

    /** Selects a box from the side menu (highlights its +X side if another side of that box isn't already selected). */
    public void selectBox(int index) {
        ExternalNode x = node();
        if (x == null) return;
        List<Box> b = localBoxes(x);
        if (index < 0 || index >= b.size()) return;
        if (selected != null && selected.box() == index) return;
        selected = new FaceEdit.Face(index, 0, 1, 0);
    }

    /** Switches the axis of the currently typed face (when clicking an X/Y/Z chip in the HUD). */
    public void clickAxis(int axisBit) {
        if (!typing || grab == null) return;
        int axis = axisBit == 1 ? 0 : axisBit == 2 ? 1 : 2;
        grab = new FaceEdit.Face(grab.box(), axis, grab.sign(), grab.t());
        selected = grab;
    }

    private FaceEdit.Face activeFace() {
        if (dragging || typing) return grab;
        if (hover != null) return hover;
        return selected;
    }

    /** The side under the pointer (or being dragged / typed / selected) as four world corners, or null. For the world overlay. */
    public double[][] hoverRect() {
        if (target == null) return null;
        FaceEdit.Face f = activeFace();
        if (f == null) return null;
        if ((dragging || typing) && !extrude) {
            Box b = ghostWorld();
            if (b != null) return FaceEdit.corners(b, f.axis(), f.sign());
        }
        List<Box> w = worldBoxes();
        return f.box() < w.size() ? FaceEdit.corners(w.get(f.box()), f.axis(), f.sign()) : null;
    }

    /** The box the running drag or typed edit would produce, in world blocks, or null. For the world overlay. */
    public Box ghostWorld() {
        Box l = ghostLocal();
        return l == null ? null : l.transformed(grabWorld);
    }

    private Box ghostLocal() {
        if ((!dragging && !typing) || grab == null || grabLocal == null || grabWorld == null) return null;
        int[] side = FaceEdit.localSide(grabWorld, grab.axis(), grab.sign());
        if (!typed.isEmpty()) return typed.apply(grabLocal, side[0], side[1], extrude);
        if (dragging) return extrude ? FaceEdit.slab(grabLocal, side[0], side[1], blocks) : FaceEdit.resized(grabLocal, side[0], side[1], blocks);
        return null;
    }

    /** HUD pill data for {@link dev.vitruvius.editor.view.TransformHud} while typing or dragging a box side. */
    public Gizmo.Hud hud() {
        if (target == null || grab == null || grabLocal == null) return null;
        if (!typing && !dragging) return null;
        boolean isTyped = typing || !typed.isEmpty();
        String sideLabel = (grab.sign() > 0 ? "+" : "−") + AXES[grab.axis()];
        String verb = "Box #" + (grab.box() + 1) + (extrude ? " · New " : " · Side ") + sideLabel;
        Box g = ghostLocal();
        String amount;
        String tail;
        if (isTyped) {
            amount = typed.text();
            if (g != null) {
                Vec3i sz = g.size();
                tail = "→ " + sz.x() + "×" + sz.y() + "×" + sz.z() + " · Tab " + (extrude ? "resize" : "new box");
            } else {
                tail = extrude ? "length ≥ 1 · Tab resize" : "length ≥ 1 (or ±delta) · Tab new box";
            }
        } else {
            Vec3i sz = g != null ? g.size() : grabLocal.size();
            amount = g == null ? "0" : sz.x() + "×" + sz.y() + "×" + sz.z();
            tail = extrude ? "pull out ≥ 1 · type length" : "type length or ±delta";
        }
        return new Gizmo.Hud(verb, 1 << grab.axis(), isTyped, amount, tail, null);
    }

    // ------------------------------------------------------------------ entering and leaving

    private ExternalNode node() {
        if (target == null) return null;
        return s.scene().byId(target) instanceof ExternalNode x ? x : null;
    }

    private static SceneObjectType type(ExternalNode x) {
        return ObjectTypes.get(x.typeId());
    }

    /** Local boxes of an external node (empty when the type has no edit mode). */
    public static List<Box> boxesOf(ExternalNode x) {
        return localBoxes(x);
    }

    /** Handle/swatch RGB color of box {@code index} on an external node. */
    public static int boxColor(ExternalNode x, int index) {
        SceneObjectType t = type(x);
        if (t == null) return 0xf2b35e;
        try {
            return t.editColor(x.data(), index) & 0xFFFFFF;
        } catch (RuntimeException | LinkageError e) {
            return 0xf2b35e;
        }
    }

    private static List<Box> localBoxes(ExternalNode x) {
        SceneObjectType t = type(x);
        if (t == null) return List.of();
        try {
            List<Box> b = t.editBoxes(x.data());
            return b == null ? List.of() : b;
        } catch (RuntimeException | LinkageError e) {
            return List.of();
        }
    }

    private List<Box> worldBoxes() {
        ExternalNode x = node();
        if (x == null) return List.of();
        Transform w = s.scene().worldTransform(x);
        List<Box> out = new ArrayList<>();
        for (Box b : localBoxes(x)) out.add(b.transformed(w));
        return out;
    }

    /** Whether an object has an edit mode at all (the Properties button shows only then). */
    public static boolean canEdit(ExternalNode x) {
        return !localBoxes(x).isEmpty();
    }

    /** E: leaves the mode, or enters it for the one selected object that has an edit mode. */
    public void toggle() {
        if (active()) exit();
        else enter();
    }

    public void enter() {
        List<UUID> ids = s.selection.ids();
        if (ids.size() != 1 || !(s.scene().byId(ids.get(0)) instanceof ExternalNode x) || !canEdit(x)) {
            s.toast(ToastQueue.Level.WARN, "Select one object with editable boxes (a heavymatic) to edit its boxes");
            return;
        }
        if (s.selectionLocked()) {
            s.toast(ToastQueue.Level.WARN, "Cannot edit: " + s.selectionLockReason());
            return;
        }
        s.gizmo.cancel();
        target = x.id();
        selected = null;
        resetGesture();
        s.toast(ToastQueue.Level.INFO, "Edit boxes: drag a side, or click/hover and type length (or ±delta). Shift + drag adds a box, X deletes, E leaves.");
    }

    public void exit() {
        target = null;
        selected = null;
        resetGesture();
    }

    private void resetGesture() {
        dragging = false;
        typing = false;
        extrude = false;
        hover = null;
        grab = null;
        grabLocal = null;
        grabWorld = null;
        blocks = 0;
        typed.clear();
    }

    // ------------------------------------------------------------------ mouse and keys

    private static double[] cursor(Minecraft mc) {
        double[] x = new double[1], y = new double[1];
        GLFW.glfwGetCursorPos(mc.getWindow().handle(), x, y);
        return new double[] {x[0], y[0]};
    }

    private static boolean shiftHeld(Minecraft mc) {
        long w = mc.getWindow().handle();
        return GLFW.glfwGetKey(w, GLFW.GLFW_KEY_LEFT_SHIFT) == GLFW.GLFW_PRESS || GLFW.glfwGetKey(w, GLFW.GLFW_KEY_RIGHT_SHIFT) == GLFW.GLFW_PRESS;
    }

    /** A left press on the viewport. True when it grabbed a side (picking must not see it). */
    public boolean onLeftPress(Minecraft mc) {
        ExternalNode x = node();
        if (x == null) return false;
        if (typing) {
            commit();
            return true;
        }
        if (dragging) return true;
        double[] c = cursor(mc);
        FaceEdit.Face f = pickAt(mc, c);
        if (f == null) {
            selected = null;
            return false;
        }
        List<Box> local = localBoxes(x);
        if (f.box() >= local.size()) return false;
        Transform w = s.scene().worldTransform(x);
        double[] ray = Picking.rayAt(mc, c[0], c[1]);
        double[] centre = FaceEdit.centre(local.get(f.box()).transformed(w), f.axis(), f.sign());
        double t = AxisProjection.axisParam(new double[] {ray[0], ray[1], ray[2]}, new double[] {ray[3], ray[4], ray[5]}, centre, f.axis());
        if (Double.isNaN(t)) {
            // Even when looking straight at a face, select it so the user can type its length immediately
            selected = f;
            s.toast(ToastQueue.Level.INFO, "Selected side " + (f.sign() > 0 ? "+" : "−") + AXES[f.axis()] + " — type a length (or look from an angle to drag)");
            return true;
        }
        grab = f;
        selected = f;
        grabLocal = local.get(f.box());
        grabWorld = w;
        p0 = centre;
        t0 = t;
        blocks = 0;
        typed.clear();
        extrude = shiftHeld(mc);
        dragging = true;
        return true;
    }

    public boolean onLeftRelease() {
        if (!dragging) return false;
        // A quick click without dragging or typing keeps the face selected for typing
        if (blocks == 0 && typed.isEmpty()) {
            FaceEdit.Face keep = grab;
            resetGesture();
            selected = keep;
            return true;
        }
        commit();
        return true;
    }

    /** A right press cancels a running drag or typed edit. */
    public boolean onRightPress() {
        if (!dragging && !typing) return false;
        resetGesture();
        return true;
    }

    /** A key while the mode is on. True when it was used. */
    public boolean onKey(int key, int mods) {
        if (target == null) return false;
        boolean shift = (mods & GLFW.GLFW_MOD_SHIFT) != 0;

        int digit = digitOf(key);
        char sign = signOf(key, shift);
        if (digit >= 0 || sign != 0) {
            if (!dragging && !typing) {
                if (!startTyping(shift)) return false;
            }
            if (digit >= 0) typed.digit(digit);
            else typed.toggleSign(sign);
            return true;
        }

        if (typing || (dragging && !typed.isEmpty())) {
            switch (key) {
                case GLFW.GLFW_KEY_BACKSPACE -> {
                    typed.backspace();
                    if (typing && !dragging && typed.isEmpty()) resetGesture();
                    return true;
                }
                case GLFW.GLFW_KEY_TAB -> {
                    extrude = !extrude;
                    return true;
                }
                case GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER -> {
                    commit();
                    return true;
                }
                case GLFW.GLFW_KEY_ESCAPE -> {
                    resetGesture();
                    return true;
                }
                default -> {
                    return true;
                }
            }
        }

        switch (key) {
            case GLFW.GLFW_KEY_ESCAPE -> {
                if (dragging) resetGesture();
                else exit();
                return true;
            }
            case GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER, GLFW.GLFW_KEY_E -> {
                if (dragging) commit();
                exit();
                return true;
            }
            case GLFW.GLFW_KEY_X, GLFW.GLFW_KEY_DELETE -> {
                FaceEdit.Face f = hover != null ? hover : selected;
                if (!dragging && f != null) deleteBox(f.box());
                return true;
            }
            default -> {
                return false;
            }
        }
    }

    private boolean startTyping(boolean shift) {
        ExternalNode x = node();
        if (x == null) return false;
        FaceEdit.Face f = hover != null ? hover : selected;
        if (f == null) return false;
        List<Box> local = localBoxes(x);
        if (f.box() >= local.size()) return false;
        grab = f;
        selected = f;
        grabLocal = local.get(f.box());
        grabWorld = s.scene().worldTransform(x);
        blocks = 0;
        typed.clear();
        extrude = shift;
        typing = true;
        return true;
    }

    private static int digitOf(int key) {
        if (key >= GLFW.GLFW_KEY_0 && key <= GLFW.GLFW_KEY_9) return key - GLFW.GLFW_KEY_0;
        if (key >= GLFW.GLFW_KEY_KP_0 && key <= GLFW.GLFW_KEY_KP_9) return key - GLFW.GLFW_KEY_KP_0;
        return -1;
    }

    private static char signOf(int key, boolean shift) {
        if (key == GLFW.GLFW_KEY_MINUS || key == GLFW.GLFW_KEY_KP_SUBTRACT) return '-';
        if (key == GLFW.GLFW_KEY_KP_ADD || (key == GLFW.GLFW_KEY_EQUAL && shift)) return '+';
        return 0;
    }

    private FaceEdit.Face pickAt(Minecraft mc, double[] c) {
        double[] ray = Picking.rayAt(mc, c[0], c[1]);
        return FaceEdit.pick(worldBoxes(), new double[] {ray[0], ray[1], ray[2]}, new double[] {ray[3], ray[4], ray[5]}, MAX_DIST);
    }

    // ------------------------------------------------------------------ commands

    private void commit() {
        ExternalNode x = node();
        Box result = ghostLocal();
        boolean add = extrude;
        FaceEdit.Face keep = grab;
        int index = grab == null ? -1 : grab.box();
        boolean changed = result != null && !result.equals(grabLocal);
        resetGesture();
        selected = keep;
        if (x == null || !changed) return;
        applyEdit(x, index, result, add);
    }

    /** Resizes one box of an external node (used by both the 3D viewport and the Properties side menu). */
    public boolean resizeBox(ExternalNode x, int index, Box newLocal) {
        return applyEdit(x, index, newLocal, false);
    }

    private boolean applyEdit(ExternalNode x, int index, Box result, boolean add) {
        SceneObjectType t = type(x);
        if (t == null) return false;
        SceneObjectType.EditResult r;
        try {
            r = add ? t.addBox(x.data(), index, result) : t.resizeBox(x.data(), index, result);
        } catch (RuntimeException | LinkageError e) {
            r = null;
        }
        if (r == null || r.data() == null) {
            s.toast(ToastQueue.Level.WARN, "This box cannot be changed that way");
            return false;
        }
        return s.actions.execute(new EditExternalBoxes(add ? "Add box to" : "Resize box of", x.id(), r.data(), r.shift()));
    }

    /** Deletes one box of an external node (used by both the 3D viewport and the Properties side menu). */
    public boolean deleteBox(ExternalNode x, int index) {
        SceneObjectType t = type(x);
        if (t == null) return false;
        SceneObjectType.EditResult r;
        try {
            r = t.removeBox(x.data(), index);
        } catch (RuntimeException | LinkageError e) {
            r = null;
        }
        if (r == null || r.data() == null) {
            s.toast(ToastQueue.Level.WARN, "This box cannot be deleted (the last one stays)");
            return false;
        }
        hover = null;
        if (selected != null && selected.box() == index) selected = null;
        return s.actions.execute(new EditExternalBoxes("Delete box of", x.id(), r.data(), r.shift()));
    }

    private void deleteBox(int index) {
        ExternalNode x = node();
        if (x != null) deleteBox(x, index);
    }

    // ------------------------------------------------------------------ per frame

    /** Validates the mode, follows the drag and draws the handles. Call once per frame with the editor on. */
    public void frame(Minecraft mc) {
        if (target == null) return;
        ExternalNode x = node();
        List<UUID> sel = s.selection.ids();
        List<Box> local = x == null ? List.of() : localBoxes(x);
        if (!EditorMode.isActive() || x == null || sel.size() != 1 || !sel.get(0).equals(target) || local.isEmpty()) {
            exit();
            return;
        }
        if (selected != null && selected.box() >= local.size()) selected = null;
        EditorCamera cam = EditorCamera.get();
        double[] c = cursor(mc);
        boolean over = UiHost.imguiWantsMouse();
        if (dragging) {
            if (GLFW.glfwGetMouseButton(mc.getWindow().handle(), GLFW.GLFW_MOUSE_BUTTON_LEFT) != GLFW.GLFW_PRESS) {
                commit();                                                          // the release went to ImGui or was lost
            } else if (typed.isEmpty()) {
                double[] ray = Picking.rayAt(mc, c[0], c[1]);
                double t = AxisProjection.axisParam(new double[] {ray[0], ray[1], ray[2]}, new double[] {ray[3], ray[4], ray[5]}, p0, grab.axis());
                if (!Double.isNaN(t)) blocks = AxisProjection.blocks((t - t0) * grab.sign());
            }
        } else if (!typing) {
            hover = over || cam.isNavigating() ? null : pickAt(mc, c);
        }
        if (target != null) draw(mc, x, c);
    }

    private void draw(Minecraft mc, ExternalNode x, double[] c) {
        EditorCamera cam = EditorCamera.get();
        var p = cam.position();
        ViewMath.Basis basis = ViewMath.Basis.of(cam.yaw(), cam.pitch());
        double[] cp = {p.x, p.y, p.z};
        double fov = mc.options.fov().get();
        double w = Math.max(1, mc.getWindow().getScreenWidth()), h = Math.max(1, mc.getWindow().getScreenHeight());
        float sc = UiHost.scale();
        ImDrawList dl = ImGui.getBackgroundDrawList();
        List<Box> wb = worldBoxes();
        FaceEdit.Face live = activeFace();
        for (int i = 0; i < wb.size() && i < MAX_HANDLES; i++) {
            int rgb = boxColor(x, i);
            for (int axis = 0; axis < 3; axis++) {
                for (int sign = -1; sign <= 1; sign += 2) {
                    double[] fc = FaceEdit.centre(wb.get(i), axis, sign);
                    double[] sp = ViewMath.project(basis, cp, fov, w, h, fc[0], fc[1], fc[2]);
                    if (sp == null) continue;
                    boolean on = live != null && live.box() == i && live.axis() == axis && live.sign() == sign;
                    float r = (on ? 6f : 4f) * sc, x0 = (float) sp[0], y0 = (float) sp[1];
                    dl.addQuadFilled(x0, y0 - r, x0 + r, y0, x0, y0 + r, x0 - r, y0, Ui.c(on ? Theme.ACCENT : rgb, on ? 1f : 0.85f));
                    dl.addQuad(x0, y0 - r, x0 + r, y0, x0, y0 + r, x0 - r, y0, Ui.c(0x14161a, 0.9f), 1f);
                }
            }
        }
        if (live != null && !UiHost.imguiWantsMouse() && !typing) UiHost.setCursor(UiHost.Cursor.RESIZE_ALL);
        if (dragging || typing) {
            Box g = ghostLocal();
            String text;
            if (g == null) {
                text = extrude ? "pull out at least 1 block" : (typed.isEmpty() ? "" : typed.text());
            } else {
                Vec3i sz = g.size();
                text = (extrude ? "new box " : "") + sz.x() + " x " + sz.y() + " x " + sz.z();
            }
            if (!text.isEmpty()) {
                Theme.push(UiHost.theme().monoS());
                float tw = ImGui.calcTextSizeX(text) + 12 * sc, th = 18 * sc;
                float x0 = (float) c[0] + 16 * sc, y0 = (float) c[1] + 12 * sc;
                dl.addRectFilled(x0, y0, x0 + tw, y0 + th, Ui.c(0x14161a, 0.85f), 4 * sc);
                dl.addText(x0 + 6 * sc, y0 + (th - ImGui.getFontSize()) * 0.5f, Ui.c(Theme.ACCENT), text);
                ImGui.popFont();
            }
        }
    }
}
