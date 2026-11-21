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
 * side to move it by whole blocks, Shift + drag to pull a new box out of it, X or Delete to delete the box under the
 * pointer. One drag is one undo step. The object type owns the data (see {@code SceneObjectType.editBoxes}); this class
 * only picks sides, measures the drag and hands the result to the type.
 *
 * <p>While it is on, a press that does not hit a side falls through to normal picking, which ends the mode when it
 * selects something else. Esc, Enter or E leave it; Esc or a right click during a drag cancels only the drag.
 */
public final class BoxEdit {
    private static final double MAX_DIST = 512;
    private static final int MAX_HANDLES = 48;

    private final EditorSession s;
    private UUID target;

    private FaceEdit.Face hover;
    private boolean dragging, extrude;
    private FaceEdit.Face grab;
    private double t0;
    private double[] p0;
    private int blocks;
    private Box grabLocal;
    private Transform grabWorld;

    public BoxEdit(EditorSession s) {
        this.s = s;
    }

    // ------------------------------------------------------------------ queries

    public boolean active() {
        return target != null;
    }

    /** True while the pointer is on a side or a drag runs, so the pick hover outline stays off. */
    public boolean owningMouse() {
        return target != null && (dragging || hover != null);
    }

    /** The side under the pointer (or being dragged) as four world corners, or null. For the world overlay. */
    public double[][] hoverRect() {
        if (target == null) return null;
        FaceEdit.Face f = dragging ? grab : hover;
        if (f == null) return null;
        if (dragging && !extrude) {
            Box b = ghostWorld();
            if (b != null) return FaceEdit.corners(b, f.axis(), f.sign());
        }
        List<Box> w = worldBoxes();
        return f.box() < w.size() ? FaceEdit.corners(w.get(f.box()), f.axis(), f.sign()) : null;
    }

    /** The box the running drag would produce, in world blocks, or null. For the world overlay. */
    public Box ghostWorld() {
        Box l = ghostLocal();
        return l == null ? null : l.transformed(grabWorld);
    }

    private Box ghostLocal() {
        if (!dragging) return null;
        int[] side = FaceEdit.localSide(grabWorld, grab.axis(), grab.sign());
        return extrude ? FaceEdit.slab(grabLocal, side[0], side[1], blocks) : FaceEdit.resized(grabLocal, side[0], side[1], blocks);
    }

    // ------------------------------------------------------------------ entering and leaving

    private ExternalNode node() {
        if (target == null) return null;
        return s.scene().byId(target) instanceof ExternalNode x ? x : null;
    }

    private static SceneObjectType type(ExternalNode x) {
        return ObjectTypes.get(x.typeId());
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
        resetDrag();
        s.toast(ToastQueue.Level.INFO, "Edit boxes: drag a side, Shift + drag pulls out a new box, X deletes a box, E leaves");
    }

    public void exit() {
        target = null;
        resetDrag();
    }

    private void resetDrag() {
        dragging = false;
        extrude = false;
        hover = null;
        grab = null;
        grabLocal = null;
        grabWorld = null;
        blocks = 0;
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
        if (x == null || dragging) return x != null;
        double[] c = cursor(mc);
        FaceEdit.Face f = pickAt(mc, c);
        if (f == null) return false;
        List<Box> local = localBoxes(x);
        if (f.box() >= local.size()) return false;
        Transform w = s.scene().worldTransform(x);
        double[] ray = Picking.rayAt(mc, c[0], c[1]);
        double[] centre = FaceEdit.centre(local.get(f.box()).transformed(w), f.axis(), f.sign());
        double t = AxisProjection.axisParam(new double[] {ray[0], ray[1], ray[2]}, new double[] {ray[3], ray[4], ray[5]}, centre, f.axis());
        if (Double.isNaN(t)) {
            s.toast(ToastQueue.Level.INFO, "Look at the side from an angle to drag it");
            return true;
        }
        grab = f;
        grabLocal = local.get(f.box());
        grabWorld = w;
        p0 = centre;
        t0 = t;
        blocks = 0;
        extrude = shiftHeld(mc);
        dragging = true;
        return true;
    }

    public boolean onLeftRelease() {
        if (!dragging) return false;
        commit();
        return true;
    }

    /** A right press cancels a running drag. */
    public boolean onRightPress() {
        if (!dragging) return false;
        resetDrag();
        return true;
    }

    /** A key while the mode is on. True when it was used. */
    public boolean onKey(int key) {
        if (target == null) return false;
        switch (key) {
            case GLFW.GLFW_KEY_ESCAPE -> {
                if (dragging) resetDrag();
                else exit();
                return true;
            }
            case GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER, GLFW.GLFW_KEY_E -> {
                if (dragging) commit();
                exit();
                return true;
            }
            case GLFW.GLFW_KEY_X, GLFW.GLFW_KEY_DELETE -> {
                if (!dragging && hover != null) deleteBox(hover.box());
                return true;
            }
            default -> {
                return false;
            }
        }
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
        int index = grab == null ? -1 : grab.box();
        boolean changed = result != null && !result.equals(grabLocal);
        resetDrag();
        if (x == null || !changed) return;
        SceneObjectType t = type(x);
        SceneObjectType.EditResult r;
        try {
            r = add ? t.addBox(x.data(), index, result) : t.resizeBox(x.data(), index, result);
        } catch (RuntimeException | LinkageError e) {
            r = null;
        }
        if (r == null || r.data() == null) {
            s.toast(ToastQueue.Level.WARN, "This box cannot be changed that way");
            return;
        }
        s.actions.execute(new EditExternalBoxes(add ? "Add box to" : "Resize box of", x.id(), r.data(), r.shift()));
    }

    private void deleteBox(int index) {
        ExternalNode x = node();
        if (x == null) return;
        SceneObjectType.EditResult r;
        try {
            r = type(x).removeBox(x.data(), index);
        } catch (RuntimeException | LinkageError e) {
            r = null;
        }
        if (r == null || r.data() == null) {
            s.toast(ToastQueue.Level.WARN, "This box cannot be deleted (the last one stays)");
            return;
        }
        hover = null;
        s.actions.execute(new EditExternalBoxes("Delete box of", x.id(), r.data(), r.shift()));
    }

    // ------------------------------------------------------------------ per frame

    /** Validates the mode, follows the drag and draws the handles. Call once per frame with the editor on. */
    public void frame(Minecraft mc) {
        if (target == null) return;
        ExternalNode x = node();
        List<UUID> sel = s.selection.ids();
        if (!EditorMode.isActive() || x == null || sel.size() != 1 || !sel.get(0).equals(target) || localBoxes(x).isEmpty()) {
            exit();
            return;
        }
        EditorCamera cam = EditorCamera.get();
        double[] c = cursor(mc);
        boolean over = UiHost.imguiWantsMouse();
        if (dragging) {
            if (GLFW.glfwGetMouseButton(mc.getWindow().handle(), GLFW.GLFW_MOUSE_BUTTON_LEFT) != GLFW.GLFW_PRESS) {
                commit();                                                          // the release went to ImGui or was lost
            } else {
                double[] ray = Picking.rayAt(mc, c[0], c[1]);
                double t = AxisProjection.axisParam(new double[] {ray[0], ray[1], ray[2]}, new double[] {ray[3], ray[4], ray[5]}, p0, grab.axis());
                if (!Double.isNaN(t)) blocks = AxisProjection.blocks((t - t0) * grab.sign());
            }
        } else {
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
        SceneObjectType t = type(x);
        FaceEdit.Face live = dragging ? grab : hover;
        for (int i = 0; i < wb.size() && i < MAX_HANDLES; i++) {
            int rgb = 0xf2b35e;
            try {
                rgb = t.editColor(x.data(), i) & 0xFFFFFF;
            } catch (RuntimeException | LinkageError ignored) {
                // the default color
            }
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
        if (live != null && !UiHost.imguiWantsMouse()) UiHost.setCursor(UiHost.Cursor.RESIZE_ALL);
        if (dragging) {
            Box g = ghostLocal();
            String text;
            if (g == null) {
                text = extrude ? "pull out at least 1 block" : "";
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
