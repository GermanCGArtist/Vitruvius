package dev.vitruvius.editor.world;

import dev.cg.uihost.Theme;
import dev.cg.uihost.UiHost;
import dev.vitruvius.editor.EditorMode;
import dev.vitruvius.editor.EditorSession;
import dev.vitruvius.editor.EditorSession.Tool;
import dev.vitruvius.editor.cam.EditorCamera;
import dev.vitruvius.editor.core.Box;
import dev.vitruvius.editor.core.Node;
import dev.vitruvius.editor.core.Vec3i;
import dev.vitruvius.editor.gizmo.AxisProjection;
import dev.vitruvius.editor.gizmo.BoxSelect;
import dev.vitruvius.editor.gizmo.FaceSnap;
import dev.vitruvius.editor.gizmo.GizmoAnchor;
import dev.vitruvius.editor.gizmo.GizmoLayout;
import dev.vitruvius.editor.gizmo.GizmoLayout.Part;
import dev.vitruvius.editor.gizmo.TurnMath;
import dev.vitruvius.editor.gizmo.TypedTransform;
import dev.vitruvius.editor.mc.Picking;
import dev.vitruvius.editor.ops.Duplicate;
import dev.vitruvius.editor.ops.LinkedDuplicate;
import dev.vitruvius.editor.ops.Move;
import dev.vitruvius.editor.ops.Rotate;
import dev.vitruvius.editor.pick.ViewMath;
import dev.vitruvius.editor.pick.WorldBoxes;
import dev.vitruvius.editor.ui.ToastQueue;
import dev.vitruvius.editor.view.Ui;
import imgui.ImDrawList;
import imgui.ImGui;
import net.minecraft.client.Minecraft;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * The viewport tools of M2 (DESIGN 11.8 to 11.11 and 12): the Move gizmo, the Y ring, typed transforms, face
 * snapping and box select. One object per session; {@link EditorInputLayer} feeds it mouse and key events and calls
 * {@link #frame} once per frame, which advances the running gesture, draws the screen-space parts (arrows, ring,
 * snap label, selection rectangle) with ImGui's background draw list and keeps the {@link Preview} the world overlay
 * draws as dashed ghost boxes.
 *
 * <p>While a gesture runs the real placement never moves: the gesture only produces a ghost, and one command inside
 * one undo transaction is committed on release or Enter. Esc, or a right click, cancels. The maths is in
 * {@code dev.vitruvius.editor.gizmo} (pure, unit-tested); this class is the glue to Minecraft and ImGui.
 */
public final class Gizmo {
    private enum Mode { IDLE, AXIS, PLANE, RING, TYPED, BOX }

    /** What the world overlay draws for the running gesture. */
    public record Preview(List<Box> before, List<Box> after, double[] from, double[] to, FaceSnap.Face face, boolean copy) {}

    /**
     * What the HUD pill shows. {@code chips} is the lit axis mask of a typed move, or -1 for no chips; {@code typed}
     * says the chips are clickable and a caret blinks after the amount.
     */
    public record Hud(String verb, int chips, boolean typed, String amount, String tail, String snap) {}

    /** Which status-bar hint line applies. */
    public enum Hint { NONE, HOVER_AXIS, HOVER_RING, DRAG_MOVE, DRAG_RING, TYPED, BOX }

    private static final double CLICK_PX = 5;

    private final EditorSession s;
    private Mode mode = Mode.IDLE;

    // snapshot of the selection for the running gesture
    private List<UUID> gIds = List.of();
    private int gVersion;
    private Vec3i gPivot = Vec3i.ZERO;
    /** Where the move gizmo sits during a drag (see {@link GizmoAnchor}); the pivot is only for rotating. */
    private Vec3i gAnchor = Vec3i.ZERO;
    private List<Box> gBoxes = List.of();
    private Box gUnion;
    private List<FaceSnap.Target> gTargets = List.of();

    // drags
    private int axis;
    private double t0;
    private double[] p0 = new double[3];
    private double[] plane0 = new double[2];
    private Vec3i delta = Vec3i.ZERO;
    private FaceSnap.Result snapX, snapZ;
    private double ang0, angLast, angAcc;
    private int quarter;
    private double pressX, pressY;

    // typed transform
    private TypedTransform typed;
    private int copyKind;               // 0 move, 1 duplicate, 2 linked duplicate

    // box select
    private boolean armed, pending, boxActive;
    private double bx0, by0, bx1, by1;
    private BoxSelect.Mode boxMode = BoxSelect.Mode.REPLACE;
    private List<UUID> boxHits = List.of();

    // per frame results
    private Part hover = Part.NONE;
    private boolean ringHover;
    private Preview preview;
    private Hud hud;

    public Gizmo(EditorSession s) {
        this.s = s;
    }

    // ------------------------------------------------------------------ queries

    /** True while any gesture runs or box select is armed: its keys are checked before the editor shortcuts. */
    public boolean modal() {
        return mode != Mode.IDLE || armed || s.eyedropping();
    }

    /** True when the gizmo owns the mouse (dragging or hovering a handle), so the pick hover outline stays off. */
    public boolean owningMouse() {
        return mode != Mode.IDLE || armed || hover != Part.NONE || ringHover;
    }

    public Preview preview() {
        return preview;
    }

    public Hud hud() {
        return hud;
    }

    /** Owners a running box select will select (hover outlines). */
    public List<UUID> boxHits() {
        return mode == Mode.BOX ? boxHits : List.of();
    }

    public boolean typedActive() {
        return mode == Mode.TYPED;
    }

    public Hint hint() {
        return switch (mode) {
            case AXIS, PLANE -> Hint.DRAG_MOVE;
            case RING -> Hint.DRAG_RING;
            case TYPED -> Hint.TYPED;
            case BOX -> Hint.BOX;
            default -> armed ? Hint.BOX : ringHover ? Hint.HOVER_RING : hover != Part.NONE ? Hint.HOVER_AXIS : Hint.NONE;
        };
    }

    // ------------------------------------------------------------------ helpers

    private static boolean held(Minecraft mc, int... keys) {
        for (int k : keys) if (GLFW.glfwGetKey(mc.getWindow().handle(), k) == GLFW.GLFW_PRESS) return true;
        return false;
    }

    private static boolean ctrlHeld(Minecraft mc) {
        return held(mc, GLFW.GLFW_KEY_LEFT_CONTROL, GLFW.GLFW_KEY_RIGHT_CONTROL, GLFW.GLFW_KEY_LEFT_SUPER, GLFW.GLFW_KEY_RIGHT_SUPER);
    }

    private static boolean shiftHeld(Minecraft mc) {
        return held(mc, GLFW.GLFW_KEY_LEFT_SHIFT, GLFW.GLFW_KEY_RIGHT_SHIFT);
    }

    private static double[] cursor(Minecraft mc) {
        double[] x = new double[1], y = new double[1];
        GLFW.glfwGetCursorPos(mc.getWindow().handle(), x, y);
        return new double[] {x[0], y[0]};
    }

    private record View(ViewMath.Basis basis, double[] cam, double fov, double w, double h) {}

    private static View view(Minecraft mc) {
        EditorCamera cam = EditorCamera.get();
        var p = cam.position();
        return new View(ViewMath.Basis.of(cam.yaw(), cam.pitch()), new double[] {p.x, p.y, p.z}, mc.options.fov().get(),
                Math.max(1, mc.getWindow().getScreenWidth()), Math.max(1, mc.getWindow().getScreenHeight()));
    }

    /** The block the move gizmo is drawn on: the pivot, or a block of a big selection that is close to the camera. */
    private static Vec3i moveAnchor(View v, Vec3i pivot, Box union) {
        return GizmoAnchor.choose(v.basis(), v.cam(), v.fov(), v.w(), v.h(), pivot, union);
    }

    private static double[] centre(Vec3i pivot) {
        return new double[] {pivot.x() + 0.5, pivot.y() + 0.5, pivot.z() + 0.5};
    }

    private String label(String verb) {
        if (gIds.size() == 1) {
            Node n = s.scene().byId(gIds.get(0));
            if (n != null) return verb + " " + n.name();
        }
        return verb + " " + gIds.size() + " objects";
    }

    /** Fills the snapshot of the selection for a gesture; false (with a toast when asked) when nothing can be moved. */
    private boolean snapshot(boolean toast) {
        List<UUID> ids = s.selection.ids();
        if (ids.isEmpty()) return false;
        if (s.selectionLocked()) {
            if (toast) s.toast(ToastQueue.Level.WARN, "Cannot move the selection: " + s.selectionLockReason());
            return false;
        }
        WorldBoxes wb = s.boxes();
        List<Box> boxes = new ArrayList<>();
        Box union = null;
        for (UUID id : ids) {
            Box b = wb.unionOf(s.scene(), List.of(id));
            if (b == null) continue;
            boxes.add(b);
            union = union == null ? b : union.union(b);
        }
        if (union == null) {
            if (toast) s.toast(ToastQueue.Level.WARN, "Nothing to move: the selection has no visible objects");
            return false;
        }
        gIds = ids;
        gVersion = s.selection.version();
        gBoxes = boxes;
        gUnion = union;
        gPivot = s.pivotOf(ids);
        gTargets = targets(wb, ids);
        return true;
    }

    private List<FaceSnap.Target> targets(WorldBoxes wb, List<UUID> ids) {
        Set<UUID> mine = new HashSet<>();
        for (UUID id : ids) {
            Node n = s.scene().byId(id);
            if (n != null) mine.add(s.scene().ownerOf(n).id());
        }
        List<FaceSnap.Target> out = new ArrayList<>();
        for (WorldBoxes.Owned o : wb.owners().values()) {
            boolean skip = mine.contains(o.owner().id());
            for (Node x = o.owner(); x != null && !skip; x = x.parent()) if (s.selection.isSelected(x.id())) skip = true;
            if (!skip) out.add(new FaceSnap.Target(o.owner().name(), o.union()));
        }
        return out;
    }

    // ------------------------------------------------------------------ starting and ending

    /** Esc, a right click, leaving the editor, a new world: drops the running gesture without changing anything. */
    public void cancel() {
        mode = Mode.IDLE;
        armed = false;
        s.cancelEyedrop();
        pending = false;
        boxActive = false;
        typed = null;
        delta = Vec3i.ZERO;
        quarter = 0;
        snapX = snapZ = null;
        boxHits = List.of();
        preview = null;
        hud = null;
    }

    /** G / Shift+D / Alt+D: a typed move with a live preview; {@code copyKind} 1 duplicates, 2 duplicates linked. */
    public void startTypedMove(int copyKind) {
        if (modal()) cancel();
        if (!snapshot(true)) return;
        this.copyKind = copyKind;
        typed = TypedTransform.move(TypedTransform.X);
        mode = Mode.TYPED;
    }

    /** R: a typed rotate about Y. */
    public void startTypedRotate() {
        if (modal()) cancel();
        if (!snapshot(true)) return;
        copyKind = 0;
        typed = TypedTransform.rotate();
        mode = Mode.TYPED;
    }

    /** B: the next left drag is a box select. */
    public void armBox() {
        if (modal()) cancel();
        armed = true;
    }

    /** A click on a HUD axis chip during a typed move. */
    public void clickAxis(int bit) {
        if (mode == Mode.TYPED && typed != null) typed.clickAxis(bit);
    }

    public TypedTransform typed() {
        return typed;
    }

    private void commit() {
        TypedTransform t = typed;
        if (t == null) return;
        List<UUID> ids = gIds;
        if (t.kind() == TypedTransform.Kind.ROTATE) {
            int q = t.quarterTurns();
            cancel();
            if (Math.floorMod(q, 4) != 0) {
                s.actions.transaction(label("Rotate"), List.of(new Rotate(ids, gPivot, q)));
            }
            return;
        }
        Vec3i d = t.delta();
        int kind = copyKind;
        cancel();
        if (kind != 0) {
            Duplicate dup = kind == 2 ? new LinkedDuplicate(ids, d) : new Duplicate(ids, d);
            if (s.actions.execute(dup)) {
                List<UUID> created = dup.createdIds();
                if (!created.isEmpty()) s.selection.setAll(created);
            }
        } else if (d.x() != 0 || d.y() != 0 || d.z() != 0) {
            s.actions.transaction(label("Move"), List.of(new Move(ids, d)));
        }
    }

    private void commitDrag() {
        List<UUID> ids = gIds;
        Vec3i d = delta;
        int q = quarter;
        Mode m = mode;
        cancel();
        if (m == Mode.RING) {
            if (Math.floorMod(q, 4) != 0) s.actions.transaction("Rotate", List.of(new Rotate(ids, gPivot, q)));
        } else if (d.x() != 0 || d.y() != 0 || d.z() != 0) {
            gIds = ids;
            String moveLabel = label("Move");
            gIds = List.of();
            s.actions.transaction(moveLabel, List.of(new Move(ids, d)));
        }
    }

    // ------------------------------------------------------------------ mouse events

    /** A left press on the viewport. Returns true when the gizmo or box select took it (picking must not). */
    public boolean onLeftPress(Minecraft mc) {
        if (s.eyedropping()) {
            double[] ec = cursor(mc);
            s.eyedropPick(Picking.pickAt(s, mc, ec[0], ec[1]).owner());
            return true;
        }
        if (mode == Mode.TYPED || mode == Mode.AXIS || mode == Mode.PLANE || mode == Mode.RING) return true;
        double[] c = cursor(mc);
        if (armed) {
            armed = false;
            beginBox(c, false);
            return true;
        }
        EditorCamera cam = EditorCamera.get();
        if (cam.isNavigating()) return false;
        Tool tool = s.tool();
        if ((tool == Tool.MOVE || tool == Tool.ROTATE) && !s.selection.isEmpty() && !s.selectionLocked() && snapshot(false)) {
            View v = view(mc);
            gAnchor = tool == Tool.MOVE ? moveAnchor(v, gPivot, gUnion) : gPivot;
            GizmoLayout g = GizmoLayout.of(v.basis(), v.cam(), v.fov(), v.w(), v.h(), centre(gAnchor), UiHost.scale());
            if (g != null) {
                if (tool == Tool.MOVE) {
                    Part p = g.hit(c[0], c[1], true);
                    if (p != Part.NONE && beginMoveDrag(mc, p, c)) return true;
                } else if (ringDistance(v, g, centre(gPivot), c) <= GizmoLayout.RING_HIT * g.sc && beginRing(mc, c)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** After a press that picking handled: remember it so a drag from empty space becomes a box select. */
    public void onEmptyPress(Minecraft mc) {
        double[] c = cursor(mc);
        pending = mode == Mode.IDLE && !armed && Picking.pickAt(s, mc, c[0], c[1]).owner() == null;
        bx0 = c[0];
        by0 = c[1];
    }

    private boolean beginMoveDrag(Minecraft mc, Part part, double[] c) {
        double[] ray = Picking.rayAt(mc, c[0], c[1]);
        double[] o = {ray[0], ray[1], ray[2]}, d = {ray[3], ray[4], ray[5]};
        p0 = centre(gAnchor);
        delta = Vec3i.ZERO;
        snapX = snapZ = null;
        if (part == Part.PLANE) {
            double[] h = AxisProjection.planeHit(o, d, p0, 1);
            if (h == null) return false;
            plane0 = h;
            mode = Mode.PLANE;
        } else {
            axis = part == Part.X ? 0 : part == Part.Y ? 1 : 2;
            t0 = AxisProjection.axisParam(o, d, p0, axis);
            if (Double.isNaN(t0)) return false;
            mode = Mode.AXIS;
        }
        pressX = c[0];
        pressY = c[1];
        return true;
    }

    private static double ringDistance(View v, GizmoLayout g, double[] pivotCentre, double[] c) {
        double r = GizmoLayout.worldLen(GizmoLayout.RING_RADIUS * g.sc, g.dist, v.fov(), v.h());
        return GizmoLayout.distToPolyline(GizmoLayout.ring(v.basis(), v.cam(), v.fov(), v.w(), v.h(), pivotCentre, r, 64), c[0], c[1]);
    }

    private double ringAngle(Minecraft mc, double[] c) {
        double[] ray = Picking.rayAt(mc, c[0], c[1]);
        double[] h = AxisProjection.planeHit(new double[] {ray[0], ray[1], ray[2]}, new double[] {ray[3], ray[4], ray[5]}, p0, 1);
        return h == null ? Double.NaN : Math.atan2(h[1] - p0[2], h[0] - p0[0]);
    }

    private boolean beginRing(Minecraft mc, double[] c) {
        p0 = centre(gPivot);
        double a = ringAngle(mc, c);
        pressX = c[0];
        pressY = c[1];
        mode = Mode.RING;
        ang0 = angLast = Double.isNaN(a) ? 0 : a;
        angAcc = 0;
        quarter = 0;
        return true;
    }

    private void beginBox(double[] c, boolean fromDrag) {
        mode = Mode.BOX;
        boxActive = true;
        pending = false;
        bx0 = c[0];
        by0 = c[1];
        bx1 = c[0];
        by1 = c[1];
        boxHits = List.of();
    }

    /** The left button went up. Returns true when it ended a gizmo or box gesture. */
    public boolean onLeftRelease(Minecraft mc, int mods) {
        pending = false;
        if (mode == Mode.AXIS || mode == Mode.PLANE) {
            commitDrag();
            return true;
        }
        if (mode == Mode.RING) {
            double[] c = cursor(mc);
            boolean click = Math.hypot(c[0] - pressX, c[1] - pressY) < CLICK_PX;
            if (click) quarter = (mods & GLFW.GLFW_MOD_SHIFT) != 0 ? -1 : 1;
            commitDrag();
            return true;
        }
        if (mode == Mode.BOX) {
            double[] c = cursor(mc);
            bx1 = c[0];
            by1 = c[1];
            boolean tiny = Math.hypot(bx1 - bx0, by1 - by0) < CLICK_PX;
            if (!tiny) applyBox(mc);
            cancel();
            return true;
        }
        return false;
    }

    /** A right press: cancels a running gesture (and the press is not handed to the camera). */
    public boolean onRightPress() {
        if (!modal()) return false;
        cancel();
        return true;
    }

    // ------------------------------------------------------------------ keys

    /** A key while {@link #modal()}: the modal keys of DESIGN 12.4. */
    public void onKey(int key, int mods) {
        if (key == GLFW.GLFW_KEY_ESCAPE) {
            cancel();
            return;
        }
        if (mode == Mode.TYPED && typed != null) {
            typedKey(key, mods);
            return;
        }
        int digit = digitOf(key);
        boolean minus = key == GLFW.GLFW_KEY_MINUS || key == GLFW.GLFW_KEY_KP_SUBTRACT;
        if ((mode == Mode.AXIS || mode == Mode.PLANE) && (digit >= 0 || minus)) {
            // a digit during a drag hands over to a typed transform on that axis; the mouse stops moving the object
            int mask = mode == Mode.PLANE ? TypedTransform.X | TypedTransform.Z : 1 << axis;
            typed = TypedTransform.move(mask);
            copyKind = 0;
            mode = Mode.TYPED;
            typedKey(key, mods);
        }
    }

    private static int digitOf(int key) {
        if (key >= GLFW.GLFW_KEY_0 && key <= GLFW.GLFW_KEY_9) return key - GLFW.GLFW_KEY_0;
        if (key >= GLFW.GLFW_KEY_KP_0 && key <= GLFW.GLFW_KEY_KP_9) return key - GLFW.GLFW_KEY_KP_0;
        return -1;
    }

    private void typedKey(int key, int mods) {
        boolean shift = (mods & GLFW.GLFW_MOD_SHIFT) != 0;
        int d = digitOf(key);
        if (d >= 0) {
            typed.digit(d);
            return;
        }
        switch (key) {
            case GLFW.GLFW_KEY_MINUS, GLFW.GLFW_KEY_KP_SUBTRACT -> typed.negate();
            case GLFW.GLFW_KEY_BACKSPACE -> typed.backspace();
            case GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER -> commit();
            case GLFW.GLFW_KEY_COMMA, GLFW.GLFW_KEY_TAB, GLFW.GLFW_KEY_SPACE -> typed.nextField();
            case GLFW.GLFW_KEY_X -> typed.setAxis(TypedTransform.X, shift);
            case GLFW.GLFW_KEY_Y -> typed.setAxis(TypedTransform.Y, shift);
            case GLFW.GLFW_KEY_Z -> typed.setAxis(TypedTransform.Z, shift);
            default -> { }
        }
    }

    // ------------------------------------------------------------------ box select

    private BoxSelect.Mode boxModeNow(Minecraft mc) {
        return ctrlHeld(mc) ? BoxSelect.Mode.SUBTRACT : shiftHeld(mc) ? BoxSelect.Mode.ADD : BoxSelect.Mode.REPLACE;
    }

    private List<BoxSelect.Item> boxItems(Minecraft mc) {
        View v = view(mc);
        List<BoxSelect.Item> items = new ArrayList<>();
        for (WorldBoxes.Owned o : s.boxes().owners().values()) {
            double[] c = dev.vitruvius.editor.gizmo.Pivots.centreOf(o.union());
            double[] p = ViewMath.project(v.basis(), v.cam(), v.fov(), v.w(), v.h(), c[0], c[1], c[2]);
            items.add(new BoxSelect.Item(o.owner().id(), p == null ? Double.NaN : p[0], p == null ? Double.NaN : p[1]));
        }
        return items;
    }

    private void applyBox(Minecraft mc) {
        List<UUID> hits = BoxSelect.hits(new BoxSelect.Rect(bx0, by0, bx1, by1), boxItems(mc));
        s.selection.setAll(BoxSelect.combine(boxModeNow(mc), s.selection.ids(), hits));
    }

    // ------------------------------------------------------------------ per frame

    private boolean stillValid() {
        if (s.selection.version() != gVersion) return false;
        for (UUID id : gIds) if (s.scene().byId(id) == null) return false;
        return true;
    }

    /** Advances the running gesture and draws. Call once per frame from the editor layer, with editor mode on. */
    public void frame(Minecraft mc) {
        if (!EditorMode.isActive()) {
            if (modal()) cancel();
            return;
        }
        if ((mode == Mode.AXIS || mode == Mode.PLANE || mode == Mode.RING || mode == Mode.TYPED) && !stillValid()) cancel();
        EditorCamera cam = EditorCamera.get();
        double[] c = cursor(mc);
        hover = Part.NONE;
        ringHover = false;
        preview = null;
        hud = null;
        UiHost.Cursor cursorWanted = null;

        // a drag that started on empty space turns into a box select after 5 px
        if (pending) {
            if (GLFW.glfwGetMouseButton(mc.getWindow().handle(), GLFW.GLFW_MOUSE_BUTTON_LEFT) != GLFW.GLFW_PRESS || cam.isNavigating()) {
                pending = false;
            } else if (Math.hypot(c[0] - bx0, c[1] - by0) >= CLICK_PX) {
                beginBox(new double[] {bx0, by0}, true);
            }
        }

        View v = view(mc);
        float sc = UiHost.scale();
        boolean over = UiHost.imguiWantsMouse();
        Tool tool = s.tool();
        boolean haveSel = !s.selection.isEmpty() && !cam.isNavigating();
        boolean locked = haveSel && s.selectionLocked();
        boolean snapOn = s.snap != ctrlHeld(mc);

        switch (mode) {
            case AXIS -> updateAxis(mc, c, snapOn);
            case PLANE -> updatePlane(mc, c, snapOn);
            case RING -> updateRing(mc, c);
            case TYPED -> updateTyped();
            case BOX -> {
                bx1 = c[0];
                by1 = c[1];
                boxMode = boxModeNow(mc);
                boxHits = BoxSelect.hits(new BoxSelect.Rect(bx0, by0, bx1, by1), boxItems(mc));
            }
            default -> { }
        }

        ImDrawList dl = ImGui.getBackgroundDrawList();
        boolean drawTool = haveSel && (mode == Mode.IDLE || mode == Mode.AXIS || mode == Mode.PLANE || mode == Mode.RING) && !armed;
        if (drawTool && (tool == Tool.MOVE || tool == Tool.ROTATE)) {
            Vec3i pv = mode == Mode.IDLE ? s.pivotOf(s.selection.ids()) : gPivot;      // null: nothing visible to move
            Vec3i origin = pv;
            if (tool == Tool.MOVE && pv != null) {
                origin = mode == Mode.IDLE ? moveAnchor(v, pv, s.boxes().unionOf(s.scene(), s.selection.ids())) : gAnchor;
            }
            GizmoLayout g = origin == null ? null : GizmoLayout.of(v.basis(), v.cam(), v.fov(), v.w(), v.h(), centre(origin), sc);
            if (g != null) {
                if (tool == Tool.MOVE && mode != Mode.RING) {
                    if (mode == Mode.IDLE && !over && !locked) hover = g.hit(c[0], c[1], true);
                    cursorWanted = drawMove(dl, g, locked);
                } else if (tool == Tool.ROTATE) {
                    if (mode == Mode.IDLE && !over && !locked) ringHover = ringDistance(v, g, centre(pv), c) <= GizmoLayout.RING_HIT * sc;
                    drawRing(dl, v, g, centre(pv), locked);
                    if (ringHover || mode == Mode.RING) cursorWanted = UiHost.Cursor.RESIZE_ALL;
                }
            }
        }
        if (mode == Mode.BOX) drawBoxRect(dl, sc);
        if ((armed || s.eyedropping()) && mode == Mode.IDLE) cursorWanted = UiHost.Cursor.CROSSHAIR;
        if (mode == Mode.BOX) cursorWanted = UiHost.Cursor.CROSSHAIR;
        if (cursorWanted != null && !over) UiHost.setCursor(cursorWanted);

        buildPreviewAndHud();
        if (preview != null && preview.face() != null) drawSnap(dl, v, sc);
    }

    // ------------------------------------------------------------------ drags

    private void updateAxis(Minecraft mc, double[] c, boolean snapOn) {
        double[] ray = Picking.rayAt(mc, c[0], c[1]);
        double t = AxisProjection.axisParam(new double[] {ray[0], ray[1], ray[2]}, new double[] {ray[3], ray[4], ray[5]}, p0, axis);
        int blocks = Double.isNaN(t) ? deltaOn(axis) : AxisProjection.blocks(t - t0);
        snapX = null;
        if (snapOn) {
            FaceSnap.Result r = FaceSnap.solve(gUnion, axis, blocks, gTargets);
            if (r != null) {
                blocks = r.delta();
                snapX = r;
            }
        }
        delta = new Vec3i(axis == 0 ? blocks : 0, axis == 1 ? blocks : 0, axis == 2 ? blocks : 0);
    }

    private int deltaOn(int a) {
        return delta.get(a);
    }

    private void updatePlane(Minecraft mc, double[] c, boolean snapOn) {
        double[] ray = Picking.rayAt(mc, c[0], c[1]);
        double[] h = AxisProjection.planeHit(new double[] {ray[0], ray[1], ray[2]}, new double[] {ray[3], ray[4], ray[5]}, p0, 1);
        int dx = h == null ? delta.x() : AxisProjection.blocks(h[0] - plane0[0]);
        int dz = h == null ? delta.z() : AxisProjection.blocks(h[1] - plane0[1]);
        snapX = snapZ = null;
        if (snapOn) {
            FaceSnap.PlaneResult r = FaceSnap.solvePlane(gUnion, dx, dz, gTargets);
            dx = r.dx();
            dz = r.dz();
            snapX = r.x();
            snapZ = r.z();
        }
        delta = new Vec3i(dx, 0, dz);
    }

    private void updateRing(Minecraft mc, double[] c) {
        double a = ringAngle(mc, c);
        if (!Double.isNaN(a)) {
            angAcc += TurnMath.wrapDelta(a, angLast);
            angLast = a;
        }
        quarter = TurnMath.quarterTurns(angAcc);
    }

    private void updateTyped() {
        if (typed.kind() == TypedTransform.Kind.ROTATE) quarter = typed.quarterTurns();
        else delta = typed.delta();
    }

    // ------------------------------------------------------------------ preview and HUD

    private void buildPreviewAndHud() {
        switch (mode) {
            case AXIS, PLANE -> {
                FaceSnap.Result r = snapX != null ? snapX : snapZ;
                preview = movePreview(r == null ? null : r.face());
                String amount = mode == Mode.PLANE ? signed(delta.x()) + ", " + signed(delta.z()) : signed(delta.get(axis));
                String verb = mode == Mode.PLANE ? "Move XZ" : "Move " + "XYZ".charAt(axis);
                String snapName = r == null ? null : r.target().name();
                hud = new Hud(verb, -1, false, amount, "blocks", snapName);
            }
            case RING -> {
                preview = rotatePreview(quarter);
                hud = new Hud("Rotate Y", -1, false, signedDeg(quarter), null, null);
            }
            case TYPED -> {
                if (typed.kind() == TypedTransform.Kind.ROTATE) {
                    preview = rotatePreview(quarter);
                    String t = typed.text(0);
                    hud = new Hud("Rotate Y", -1, true, (t.isEmpty() ? "0" : t) + "°", "snaps to 90°", null);
                } else {
                    preview = movePreview(null);
                    String amount = typed.isPlane() ? typed.shown(0) + ", " + typed.shown(1) : typed.shown(0);
                    String verb = copyKind == 0 ? "Move" : copyKind == 1 ? "Duplicate" : "Linked duplicate";
                    hud = new Hud(verb, typed.mask(), true, amount, "blocks", null);
                }
            }
            default -> { }
        }
    }

    private Preview movePreview(FaceSnap.Face face) {
        List<Box> after = new ArrayList<>();
        for (Box b : gBoxes) after.add(TurnMath.moved(b, delta));
        double[] from = centre(gPivot);
        double[] to = {from[0] + delta.x(), from[1] + delta.y(), from[2] + delta.z()};
        return new Preview(gBoxes, after, from, to, face, copyKind != 0);
    }

    private Preview rotatePreview(int q) {
        List<Box> after = new ArrayList<>();
        for (Box b : gBoxes) after.add(TurnMath.rotated(b, gPivot, q));
        return new Preview(gBoxes, after, null, null, null, false);
    }

    private static String signed(int v) {
        return v > 0 ? "+" + v : v < 0 ? "-" + (-v) : "0";
    }

    private static String signedDeg(int q) {
        int d = q * 90;
        return (d > 0 ? "+" : d < 0 ? "-" : "") + Math.abs(d) + "°";
    }

    // ------------------------------------------------------------------ drawing

    private static int mix(int rgb, float toWhite) {
        int r = (rgb >> 16) & 0xFF, g = (rgb >> 8) & 0xFF, b = rgb & 0xFF;
        r += (int) ((255 - r) * toWhite);
        g += (int) ((255 - g) * toWhite);
        b += (int) ((255 - b) * toWhite);
        return (r << 16) | (g << 8) | b;
    }

    private static int axisColor(int a) {
        return a == 0 ? Ui.AX_X : a == 1 ? Ui.AX_Y : Ui.AX_Z;
    }

    private UiHost.Cursor drawMove(ImDrawList dl, GizmoLayout g, boolean locked) {
        float sc = (float) g.sc;
        float ox = (float) g.ox, oy = (float) g.oy;
        boolean dragging = mode == Mode.AXIS || mode == Mode.PLANE;
        UiHost.Cursor cursor = null;
        int halo = Ui.c(0x000000, 0.45f);

        if (dragging) {
            // constraint line(s) across the whole screen
            int[] axes = mode == Mode.PLANE ? new int[] {0, 2} : new int[] {axis};
            for (int a : axes) {
                var ar = g.arrow(a);
                float len = 4000;
                dl.addLine((float) (ox - ar.dx() * len), (float) (oy - ar.dy() * len), (float) (ox + ar.dx() * len), (float) (oy + ar.dy() * len),
                        Ui.c(axisColor(a), 0.5f), 1f);
            }
            if (mode == Mode.AXIS) {
                var ar = g.arrow(axis);
                cursor = Math.abs(ar.dx()) >= Math.abs(ar.dy()) ? UiHost.Cursor.RESIZE_EW : UiHost.Cursor.RESIZE_NS;
            } else cursor = UiHost.Cursor.RESIZE_ALL;
        }

        // plane handle
        if (g.planeVisible() && (!dragging || mode == Mode.PLANE)) {
            double[][] q = g.planeCorners();
            boolean hot = hover == Part.PLANE || mode == Mode.PLANE;
            int rgb = locked ? Theme.GUTTER : Ui.AX_Y;
            dl.addQuad((float) q[0][0], (float) q[0][1], (float) q[1][0], (float) q[1][1], (float) q[2][0], (float) q[2][1], (float) q[3][0],
                    (float) q[3][1], halo, 3.5f * sc);
            dl.addQuadFilled((float) q[0][0], (float) q[0][1], (float) q[1][0], (float) q[1][1], (float) q[2][0], (float) q[2][1], (float) q[3][0],
                    (float) q[3][1], Ui.c(rgb, locked ? 0.2f : hot ? 0.55f : 0.30f));
            dl.addQuad((float) q[0][0], (float) q[0][1], (float) q[1][0], (float) q[1][1], (float) q[2][0], (float) q[2][1], (float) q[3][0],
                    (float) q[3][1], Ui.c(rgb), 1.5f * sc);
            if (hover == Part.PLANE) cursor = UiHost.Cursor.RESIZE_ALL;
        }

        for (int a = 0; a < 3; a++) {
            if (dragging && (mode == Mode.PLANE || a != axis)) continue;
            var ar = g.arrow(a);
            boolean usable = ar.usable();
            boolean hot = !locked && usable && (hover == (a == 0 ? Part.X : a == 1 ? Part.Y : Part.Z) || (mode == Mode.AXIS && a == axis));
            int rgb = locked ? Theme.GUTTER : hot ? mix(axisColor(a), 0.25f) : axisColor(a);
            float alpha = usable ? 1f : 0.25f;
            float width = (mode == Mode.AXIS ? 4f : hot ? 3.5f : locked ? 2f : 2.5f) * sc;
            drawArrow(dl, g, a, rgb, alpha, width, halo, sc);
            if (hot && mode == Mode.IDLE) {
                cursor = UiHost.Cursor.HAND;
            }
        }

        // centre dot
        dl.addCircleFilled(ox, oy, 5.5f * sc, Ui.c(0x000000, 0.5f));
        dl.addCircleFilled(ox, oy, 4.5f * sc, Ui.c(locked ? Theme.GUTTER : Theme.FG));
        if (locked) {
            Theme.push(UiHost.theme().small());
            String t = lockLabel();
            dl.addText(ox - ImGui.calcTextSizeX(t) * 0.5f, oy + 10 * sc, Ui.c(Theme.DIM), t);
            ImGui.popFont();
        }
        return cursor;
    }

    private void drawArrow(ImDrawList dl, GizmoLayout g, int a, int rgb, float alpha, float width, int halo, float sc) {
        var ar = g.arrow(a);
        float dx = (float) ar.dx(), dy = (float) ar.dy();
        double[] s0 = g.along(a, GizmoLayout.SHAFT_FROM), tip = g.along(a, GizmoLayout.SHAFT_TO), base = g.along(a, GizmoLayout.SHAFT_TO - GizmoLayout.HEAD_LEN);
        float nx = -dy, ny = dx;
        float hw = (float) (GizmoLayout.HEAD_WIDTH * 0.5 * sc);
        float bx = (float) base[0], by = (float) base[1];
        int col = Ui.c(rgb, alpha), hcol = Ui.c(0x000000, 0.45f * alpha);
        // halo first
        dl.addLine((float) s0[0], (float) s0[1], bx, by, hcol, width + 2 * sc);
        float e = 1.5f * sc;
        dl.addTriangleFilled((float) tip[0] + dx * e, (float) tip[1] + dy * e, bx + nx * (hw + e), by + ny * (hw + e), bx - nx * (hw + e), by - ny * (hw + e), hcol);
        // arrow
        dl.addLine((float) s0[0], (float) s0[1], bx, by, col, width);
        dl.addTriangleFilled((float) tip[0], (float) tip[1], bx + nx * hw, by + ny * hw, bx - nx * hw, by - ny * hw, col);
        // letter
        double[] lp = g.along(a, GizmoLayout.SHAFT_TO + 12);
        Theme.push(UiHost.theme().monoS());
        String letter = a == 0 ? "X" : a == 1 ? "Y" : "Z";
        float tx = (float) lp[0] - ImGui.calcTextSizeX(letter) * 0.5f, ty = (float) lp[1] - ImGui.getFontSize() * 0.5f;
        dl.addText(tx + 0.5f, ty, col, letter);
        dl.addText(tx, ty, col, letter);
        ImGui.popFont();
    }

    private void drawRing(ImDrawList dl, View v, GizmoLayout g, double[] pc, boolean locked) {
        float sc = (float) g.sc;
        double r = GizmoLayout.worldLen(GizmoLayout.RING_RADIUS * sc, g.dist, v.fov(), v.h());
        double[][] ring = GizmoLayout.ring(v.basis(), v.cam(), v.fov(), v.w(), v.h(), pc, r, 64);
        int rgb = locked ? Theme.GUTTER : ringHover || mode == Mode.RING ? mix(Ui.AX_Y, 0.25f) : Ui.AX_Y;
        float width = (ringHover || mode == Mode.RING ? 3.5f : 2.5f) * sc;
        int halo = Ui.c(0x000000, 0.45f);

        if (mode == Mode.RING && quarter != 0) {               // the arc from the start angle to the snapped one
            double end = ang0 + quarter * (Math.PI / 2);
            int steps = 24;
            double[] prev = null;
            for (int i = 0; i <= steps; i++) {
                double a = ang0 + (end - ang0) * i / steps;
                double[] p = ViewMath.project(v.basis(), v.cam(), v.fov(), v.w(), v.h(), pc[0] + Math.cos(a) * r, pc[1], pc[2] + Math.sin(a) * r);
                if (prev != null && p != null) {
                    dl.addTriangleFilled((float) g.ox, (float) g.oy, (float) prev[0], (float) prev[1], (float) p[0], (float) p[1], Ui.c(Ui.AX_Y, 0.15f));
                }
                prev = p;
            }
        }
        for (int pass = 0; pass < 2; pass++) {
            for (int i = 0; i + 1 < ring.length; i++) {
                if (ring[i] == null || ring[i + 1] == null) continue;
                dl.addLine((float) ring[i][0], (float) ring[i][1], (float) ring[i + 1][0], (float) ring[i + 1][1], pass == 0 ? halo : Ui.c(rgb),
                        pass == 0 ? width + 2 * sc : width);
            }
        }
        for (int k = 0; k < 4; k++) {                           // tick diamonds at 0, 90, 180, 270 degrees
            double[] p = ring[k * 16];
            if (p == null) continue;
            float x = (float) p[0], y = (float) p[1], d = 4.2f * sc;
            dl.addQuadFilled(x, y - d, x + d, y, x, y + d, x - d, y, Ui.c(rgb));
        }
        dl.addCircleFilled((float) g.ox, (float) g.oy, 4.5f * sc, Ui.c(locked ? Theme.GUTTER : Theme.FG));
        if (locked) {
            Theme.push(UiHost.theme().small());
            String t = lockLabel();
            dl.addText((float) g.ox - ImGui.calcTextSizeX(t) * 0.5f, (float) g.oy + 10 * sc, Ui.c(Theme.DIM), t);
            ImGui.popFont();
        }
    }

    /** "Locked" and the reason, shortened, so it is clear why the selection does not move. */
    private String lockLabel() {
        String why = s.selectionLockReason();
        if (why == null || why.isEmpty()) return "Locked";
        return "Locked: " + (why.length() > 60 ? why.substring(0, 57) + "..." : why);
    }

    private void drawBoxRect(ImDrawList dl, float sc) {
        boolean sub = boxMode == BoxSelect.Mode.SUBTRACT;
        int rgb = sub ? Theme.ERR : Theme.ACCENT;
        float x0 = (float) Math.min(bx0, bx1), y0 = (float) Math.min(by0, by1), x1 = (float) Math.max(bx0, bx1), y1 = (float) Math.max(by0, by1);
        dl.addRectFilled(x0, y0, x1, y1, Ui.c(rgb, sub ? 0.06f : 0.08f));
        dl.addRect(x0, y0, x1, y1, Ui.c(rgb), 0f, 0, 1f);
    }

    private void drawSnap(ImDrawList dl, View v, float sc) {
        FaceSnap.Face f = preview.face();
        double[] c = new double[3];
        int[] o = f.axis() == 0 ? new int[] {1, 2} : f.axis() == 1 ? new int[] {0, 2} : new int[] {0, 1};
        c[f.axis()] = f.coord();
        c[o[0]] = (f.lo1() + f.hi1()) * 0.5;
        c[o[1]] = (f.lo2() + f.hi2()) * 0.5;
        double[] p = ViewMath.project(v.basis(), v.cam(), v.fov(), v.w(), v.h(), c[0], c[1], c[2]);
        if (p == null) return;
        float x = (float) p[0], y = (float) p[1], d = 3.5f * sc;
        dl.addQuadFilled(x, y - d, x + d, y, x, y + d, x - d, y, Ui.c(Theme.ACCENT));
        FaceSnap.Result r = snapX != null ? snapX : snapZ;
        if (r == null) return;
        Theme.push(UiHost.theme().monoS());
        String text = "snapped to " + r.target().name();
        float w = ImGui.calcTextSizeX(text) + 12 * sc, h = 18 * sc;
        dl.addRectFilled(x + 10 * sc, y - h * 0.5f, x + 10 * sc + w, y + h * 0.5f, Ui.c(0x14161a, 0.82f), 4 * sc);
        dl.addText(x + 16 * sc, y - ImGui.getFontSize() * 0.5f, Ui.c(Theme.ACCENT), text);
        ImGui.popFont();
    }

}
