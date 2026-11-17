package dev.vitruvius.editor.mc;

import dev.vitruvius.editor.EditorSession;
import dev.vitruvius.editor.cam.EditorCamera;
import dev.vitruvius.editor.core.Node;
import dev.vitruvius.editor.core.Vec3i;
import dev.vitruvius.editor.pick.PickMath;
import dev.vitruvius.editor.pick.ViewMath;
import dev.vitruvius.editor.pick.WorldBoxes;
import fi.dy.masa.litematica.world.SchematicWorldHandler;
import fi.dy.masa.litematica.world.WorldSchematic;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import org.lwjgl.glfw.GLFW;

import java.util.List;
import java.util.UUID;

/**
 * Picking in the world (EDITOR-2.0 section 6): turns a click on the viewport into the owner it means.
 * <ol>
 *   <li>A ray under the cursor is cast against Litematica's schematic world; the hit block maps to the owners whose
 *       boxes contain it, the one in front (front order) wins.</li>
 *   <li>Without a block hit the nearest owner box along the ray wins.</li>
 *   <li>Alt cycles through every owner along the ray.</li>
 * </ol>
 * Left click selects, Shift toggles, Alt cycles; a double click frames. Clicks over an ImGui window never get here
 * (the input layer checks that). The maths is in {@link PickMath} and {@link ViewMath}.
 */
public final class Picking {
    private static final double MAX_DIST = 512.0;
    private static final double CLICK_MAX_MOVE_PX = 5.0;
    private static final long DOUBLE_CLICK_NANOS = 350_000_000L;

    private static boolean leftDown;
    private static double pressX, pressY;
    private static long lastClickNanos;
    private static UUID lastClickOwner;
    private static UUID hover;

    private Picking() {}

    /** What is under the cursor right now (for the hover outline), or null. */
    public static UUID hover() {
        return hover;
    }

    public static void clearHover() {
        hover = null;
    }

    // ------------------------------------------------------------------ ray

    /** The world ray under a window position (screen coordinates): {origin xyz, direction xyz}. */
    public static double[] rayAt(Minecraft mc, double sx, double sy) {
        EditorCamera cam = EditorCamera.get();
        Vec3 p = cam.position();
        ViewMath.Basis b = ViewMath.Basis.of(cam.yaw(), cam.pitch());
        double w = Math.max(1, mc.getWindow().getScreenWidth()), h = Math.max(1, mc.getWindow().getScreenHeight());
        double[] d = ViewMath.ray(b, mc.options.fov().get(), w, h, sx, sy);
        return new double[] {p.x, p.y, p.z, d[0], d[1], d[2]};
    }

    /** The first block of Litematica's schematic world along a ray, or null. */
    static Vec3i raycastSchematic(double[] ray) {
        WorldSchematic world = SchematicWorldHandler.getSchematicWorld();
        if (world == null) return null;
        Vec3 from = new Vec3(ray[0], ray[1], ray[2]);
        Vec3 to = from.add(ray[3] * MAX_DIST, ray[4] * MAX_DIST, ray[5] * MAX_DIST);
        try {
            BlockHitResult hit = world.clip(new ClipContext(from, to, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, CollisionContext.empty()));
            if (hit == null || hit.getType() != HitResult.Type.BLOCK) return null;
            return new Vec3i(hit.getBlockPos().getX(), hit.getBlockPos().getY(), hit.getBlockPos().getZ());
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** The owner under a window position, plus the cycle order for Alt. {@code null} when nothing is there. */
    public record Result(UUID owner, List<UUID> cycle) {}

    public static Result pickAt(EditorSession s, Minecraft mc, double sx, double sy) {
        WorldBoxes wb = s.boxes();
        List<PickMath.Candidate> all = wb.candidates();
        if (all.isEmpty()) return new Result(null, List.of());
        double[] ray = rayAt(mc, sx, sy);
        double[] o = {ray[0], ray[1], ray[2]}, d = {ray[3], ray[4], ray[5]};
        UUID primary = PickMath.choose(all, raycastSchematic(ray), o, d, MAX_DIST);
        if (primary == null) return new Result(null, List.of());
        return new Result(primary, PickMath.cycleOrder(primary, PickMath.along(all, o, d, MAX_DIST)));
    }

    // ------------------------------------------------------------------ events (from EditorInputLayer)

    private static double[] cursor(Minecraft mc) {
        double[] x = new double[1], y = new double[1];
        GLFW.glfwGetCursorPos(mc.getWindow().handle(), x, y);
        return new double[] {x[0], y[0]};
    }

    /** A left press on the viewport (not on an ImGui window). */
    public static void onLeftPress(Minecraft mc) {
        double[] c = cursor(mc);
        pressX = c[0];
        pressY = c[1];
        leftDown = true;
    }

    /** Forgets a press that another tool took over (a box select that grew out of it). */
    public static void cancelPress() {
        leftDown = false;
    }

    /** The left button went up: a click (little movement) selects. {@code mods}: GLFW modifier bits. */
    public static void onLeftRelease(Minecraft mc, int mods) {
        if (!leftDown) return;
        leftDown = false;
        double[] c = cursor(mc);
        if (Math.hypot(c[0] - pressX, c[1] - pressY) >= CLICK_MAX_MOVE_PX) return;     // a drag: box select comes later
        EditorSession s = EditorSession.get();
        if (s == null) return;
        boolean shift = (mods & GLFW.GLFW_MOD_SHIFT) != 0, alt = (mods & GLFW.GLFW_MOD_ALT) != 0;
        Result r = pickAt(s, mc, c[0], c[1]);
        if (r.owner() == null) {
            if (!shift) s.selection.clear();
            return;
        }
        UUID target = r.owner();
        if (alt) {
            Node active = s.selection.active() == null ? null : s.scene().byId(s.selection.active());
            UUID current = active == null ? null : s.scene().ownerOf(active).id();
            target = PickMath.cycle(r.cycle(), current);
        }
        long now = System.nanoTime();
        boolean doubleClick = target.equals(lastClickOwner) && now - lastClickNanos < DOUBLE_CLICK_NANOS && !shift && !alt;
        lastClickOwner = target;
        lastClickNanos = now;
        if (shift) s.selection.toggle(target);
        else s.selection.set(target);
        Node n = s.scene().byId(target);
        if (n != null && s.selection.isSelected(target)) {
            s.tree.reveal(n);
            s.scrollToRow = target;
        }
        if (doubleClick) s.actions.run("frame");
    }

    /** A right click (from {@link EditorCamera#consumeRightClick()}): selects what is under it. Returns true on an object. */
    public static boolean onRightClick(Minecraft mc, double sx, double sy) {
        EditorSession s = EditorSession.get();
        if (s == null) return false;
        Result r = pickAt(s, mc, sx, sy);
        if (r.owner() == null) return false;
        if (!s.selection.isSelected(r.owner())) {
            s.selection.set(r.owner());
            Node n = s.scene().byId(r.owner());
            if (n != null) s.tree.reveal(n);
            s.scrollToRow = r.owner();
        }
        return true;
    }

    /** Updates the hover outline from the cursor (call every few frames while no gesture runs). */
    public static void updateHover(Minecraft mc, boolean overUi) {
        EditorSession s = EditorSession.get();
        EditorCamera cam = EditorCamera.get();
        if (s == null || overUi || cam.isNavigating() || leftDown) {
            hover = null;
            return;
        }
        double[] c = cursor(mc);
        hover = pickAt(s, mc, c[0], c[1]).owner();
    }
}
