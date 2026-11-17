package dev.schemarray.editor.cam;

import dev.cg.uihost.UiHost;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.glfw.GLFW;

/**
 * The editor camera: one pose (orbit target, distance, yaw, pitch) that serves both modes.
 * <ul>
 *   <li>Orbit: middle mouse turns yaw/pitch around the target, Shift + middle mouse pans the target, the wheel
 *       dollies along the cursor's ray, so the point under the cursor stays under it.</li>
 *   <li>Fly: holding the right mouse button turns the view around the camera and WASD / Q E move the target (and so
 *       the camera, which sits {@code distance} behind it). The wheel changes the speed.</li>
 * </ul>
 * Input arrives as events from the layer (button, move, scroll); {@link #update} runs once per render frame (from the
 * camera mixin), applies it to the <em>goal</em> pose and eases the <em>shown</em> pose towards it with an
 * exponential filter on real frame time, so motion is smooth at any frame rate and independent of the tick rate.
 * Yaw/pitch follow Minecraft: yaw 0 looks south (+Z), pitch positive looks down.
 * <p>
 * Everything here runs on the render thread (GLFW events are polled there too), so there is no locking.
 */
public final class EditorCamera {
    private static final EditorCamera INSTANCE = new EditorCamera();

    public static EditorCamera get() { return INSTANCE; }

    /** What the mouse is doing right now. */
    public enum Gesture { NONE, ORBIT, PAN, FLY_PENDING, FLY }

    // right click vs fly
    public static final double CLICK_MAX_MOVE_PX = 4.0;
    public static final long CLICK_MAX_NANOS = 250_000_000L;

    // fly
    public static final float SPEED_MIN = 0.2f, SPEED_MAX = 8f;
    private static final float BASE_FLY_SPEED = 12f;        // blocks per second at 1x
    private static final float SPRINT = 3f;                 // Shift

    private static final float DEFAULT_DISTANCE = 12f;
    private static final float MIN_DISTANCE = 0.25f, MAX_DISTANCE = 4096f;
    private static final float ZOOM_PER_NOTCH = 1.12f;      // 12 % per notch
    private static final float SPEED_PER_NOTCH = 1.2f;

    // easing rates (1/s): how fast the shown pose catches up with the goal
    private static final float EASE_MOVE = 24f, EASE_TURN = 40f;

    // goal pose
    private double tx, ty, tz;
    private float dist = DEFAULT_DISTANCE, yaw, pitch;
    // shown pose
    private double sx, sy, sz;
    private float sDist = DEFAULT_DISTANCE, sYaw, sPitch;

    private float speed = 1f;
    private long lastNanos;

    // mouse state
    private Gesture gesture = Gesture.NONE;
    private double lastMx, lastMy;
    private boolean baselineValid;
    private double accDx, accDy;
    private long rmbNanos;
    private double rmbX, rmbY;
    private boolean rightClick;
    private double rightClickX, rightClickY;

    private EditorCamera() {}

    // ------------------------------------------------------------------ pose

    /** Starts at an eye position and rotation: the view is identical, the orbit target sits in front of it. */
    public void reset(Vec3 eye, float yawDeg, float pitchDeg) {
        yaw = yawDeg;
        pitch = clampPitch(pitchDeg);
        dist = DEFAULT_DISTANCE;
        Vec3 f = forward(yaw, pitch);
        tx = eye.x + f.x * dist;
        ty = eye.y + f.y * dist;
        tz = eye.z + f.z * dist;
        sx = tx; sy = ty; sz = tz;
        sDist = dist; sYaw = yaw; sPitch = pitch;
        gesture = Gesture.NONE;
        baselineValid = false;
        accDx = accDy = 0;
        rightClick = false;
        lastNanos = 0;
    }

    /** The position the camera is shown at this frame. */
    public Vec3 position() { return posOf(sx, sy, sz, sYaw, sPitch, sDist); }

    public float yaw() { return sYaw; }
    public float pitch() { return sPitch; }
    public Vec3 target() { return new Vec3(sx, sy, sz); }

    /** Moves the orbit target (the Top view click); the shown pose eases to it. */
    public void setTarget(double x, double y, double z) {
        tx = x;
        ty = y;
        tz = z;
    }

    public float distance() { return sDist; }
    /** Fly speed multiplier, {@link #SPEED_MIN} to {@link #SPEED_MAX}. */
    public float speed() { return speed; }
    public Gesture gesture() { return gesture; }
    public boolean isFlying() { return gesture == Gesture.FLY; }
    public boolean isNavigating() { return gesture != Gesture.NONE; }

    /** True once after a right press that was released fast and without moving (the context menu trigger). */
    public boolean consumeRightClick() {
        boolean r = rightClick;
        rightClick = false;
        return r;
    }

    /** Window position (screen coordinates) of the last right click. */
    public double rightClickX() { return rightClickX; }
    public double rightClickY() { return rightClickY; }

    private static Vec3 posOf(double tx, double ty, double tz, float yaw, float pitch, float dist) {
        Vec3 f = forward(yaw, pitch);
        return new Vec3(tx - f.x * dist, ty - f.y * dist, tz - f.z * dist);
    }

    public static Vec3 forward(float yawDeg, float pitchDeg) {
        double y = Math.toRadians(yawDeg), p = Math.toRadians(pitchDeg);
        double cp = Math.cos(p);
        return new Vec3(-Math.sin(y) * cp, -Math.sin(p), Math.cos(y) * cp);
    }

    /** Screen-right direction (horizontal). Facing south (yaw 0) it points west (-X). */
    public static Vec3 right(float yawDeg) {
        double y = Math.toRadians(yawDeg);
        return new Vec3(-Math.cos(y), 0, -Math.sin(y));
    }

    private static Vec3 up(float yawDeg, float pitchDeg) {
        return right(yawDeg).cross(forward(yawDeg, pitchDeg));
    }

    private static float clampPitch(float p) { return Math.max(-89.9f, Math.min(89.9f, p)); }

    // ------------------------------------------------------------------ framing

    /** Frames a world box: keeps the viewing direction, centres the box and backs off until it fits. */
    public void frame(AABB box) {
        if (box == null) return;
        Minecraft mc = Minecraft.getInstance();
        Vec3 c = box.getCenter();
        double r = 0.5 * Math.sqrt(box.getXsize() * box.getXsize() + box.getYsize() * box.getYsize() + box.getZsize() * box.getZsize());
        double halfV = Math.toRadians(fovDegrees(mc) * 0.5);
        double halfH = Math.atan(Math.tan(halfV) * aspect(mc));
        double half = Math.max(0.2, Math.min(halfV, halfH));
        tx = c.x; ty = c.y; tz = c.z;
        dist = (float) Math.max(MIN_DISTANCE, Math.min(MAX_DISTANCE, Math.max(3.0, r) / Math.sin(half) * 1.05));
    }

    // ------------------------------------------------------------------ events (from EditorInputLayer)

    /** @param overUi true when the press landed on an ImGui window (those clicks are not ours) */
    public void onMouseButton(Minecraft mc, int button, int action, boolean overUi) {
        long win = mc.getWindow().handle();
        if (button == GLFW.GLFW_MOUSE_BUTTON_MIDDLE) {
            if (action == GLFW.GLFW_PRESS) {
                if (overUi || gesture != Gesture.NONE) return;
                gesture = Gesture.ORBIT;
                beginCapture();
            } else if (action == GLFW.GLFW_RELEASE && (gesture == Gesture.ORBIT || gesture == Gesture.PAN)) {
                endGesture();
            }
        } else if (button == GLFW.GLFW_MOUSE_BUTTON_RIGHT) {
            if (action == GLFW.GLFW_PRESS) {
                if (overUi || gesture != Gesture.NONE) return;
                gesture = Gesture.FLY_PENDING;
                rmbNanos = System.nanoTime();
                double[] p = cursor(win);
                rmbX = p[0];
                rmbY = p[1];
            } else if (action == GLFW.GLFW_RELEASE) {
                if (gesture == Gesture.FLY_PENDING) {
                    // not flying: a click if it was quick and the mouse stayed put
                    double[] p = cursor(win);
                    boolean still = Math.hypot(p[0] - rmbX, p[1] - rmbY) < CLICK_MAX_MOVE_PX;
                    if (still && System.nanoTime() - rmbNanos <= CLICK_MAX_NANOS) {
                        rightClick = true;
                        rightClickX = rmbX;
                        rightClickY = rmbY;
                    }
                    gesture = Gesture.NONE;
                } else if (gesture == Gesture.FLY) {
                    endGesture();
                }
            }
        }
    }

    public void onMouseMove(Minecraft mc, double x, double y) {
        if (gesture == Gesture.FLY_PENDING) {
            if (Math.hypot(x - rmbX, y - rmbY) >= CLICK_MAX_MOVE_PX) startFly();
        } else if (gesture != Gesture.NONE && UiHost.isCursorCaptured()) {
            if (baselineValid) {
                accDx += x - lastMx;
                accDy += y - lastMy;
            }
            baselineValid = true;
        }
        lastMx = x;
        lastMy = y;
    }

    public void onScroll(Minecraft mc, double dy) {
        if (dy == 0) return;
        if (gesture == Gesture.FLY) {
            speed = (float) Math.max(SPEED_MIN, Math.min(SPEED_MAX, speed * Math.pow(SPEED_PER_NOTCH, dy)));
            return;
        }
        if (gesture != Gesture.NONE) return;
        zoom(mc, dy);
    }

    /** Aborts any gesture and gives the cursor back (leaving the editor, a screen opening). */
    public void cancelGesture() {
        if (gesture != Gesture.NONE) endGesture();
    }

    private void beginCapture() {
        UiHost.captureCursor(true);
        baselineValid = false;
        accDx = accDy = 0;
    }

    private void endGesture() {
        UiHost.captureCursor(false);
        gesture = Gesture.NONE;
        baselineValid = false;
        accDx = accDy = 0;
    }

    private void startFly() {
        gesture = Gesture.FLY;
        beginCapture();
    }

    /** Dolly along the cursor's ray: the point under the cursor stays under it. */
    private void zoom(Minecraft mc, double notches) {
        double f = Math.pow(ZOOM_PER_NOTCH, -notches);                    // < 1 zooms in
        float newDist = (float) Math.max(MIN_DISTANCE, Math.min(MAX_DISTANCE, dist * f));
        f = newDist / dist;
        if (f == 1.0) return;
        Vec3 pos = posOf(tx, ty, tz, yaw, pitch, dist);
        Vec3 fwd = forward(yaw, pitch), rgt = right(yaw), upv = up(yaw, pitch);
        // the cursor in [-1, 1] screen space (x right, y up)
        double[] c = cursor(mc.getWindow().handle());
        double w = Math.max(1, mc.getWindow().getScreenWidth()), h = Math.max(1, mc.getWindow().getScreenHeight());
        double nx = Math.max(-1, Math.min(1, 2 * c[0] / w - 1)), ny = Math.max(-1, Math.min(1, 1 - 2 * c[1] / h));
        double th = Math.tan(Math.toRadians(fovDegrees(mc) * 0.5));
        // the world point under the cursor, at the depth of the target
        Vec3 p = pos.add(fwd.scale(dist)).add(rgt.scale(nx * th * (w / h) * dist)).add(upv.scale(ny * th * dist));
        // scale the camera about that point: it stays under the cursor
        Vec3 np = pos.add(p.subtract(pos).scale(1 - f));
        dist = newDist;
        Vec3 nt = np.add(fwd.scale(dist));
        tx = nt.x; ty = nt.y; tz = nt.z;
    }

    // ------------------------------------------------------------------ per frame

    /** Applies the input of this frame and eases the shown pose towards the goal. Call once per render frame. */
    public void update(Minecraft mc) {
        long now = System.nanoTime();
        float dt = lastNanos == 0 ? 0f : (float) ((now - lastNanos) / 1.0e9);
        lastNanos = now;
        dt = Math.max(0f, Math.min(0.1f, dt));
        long win = mc.getWindow().handle();

        if (gesture == Gesture.FLY_PENDING) {
            if (now - rmbNanos > CLICK_MAX_NANOS || flyKeyDown(win)) startFly();
        }

        float sens = lookSensitivity(mc);
        double dx = accDx, dy = accDy;
        accDx = accDy = 0;
        switch (gesture) {
            case ORBIT, PAN -> {
                boolean shift = key(win, GLFW.GLFW_KEY_LEFT_SHIFT) || key(win, GLFW.GLFW_KEY_RIGHT_SHIFT);
                gesture = shift ? Gesture.PAN : Gesture.ORBIT;
                if (shift) {
                    pan(mc, dx, dy);
                } else {
                    yaw += (float) (dx * sens * 1.5);
                    pitch = clampPitch(pitch + (float) (dy * sens * 1.5));
                }
            }
            case FLY -> {
                yaw += (float) (dx * sens);
                pitch = clampPitch(pitch + (float) (dy * sens));
                fly(win, dt);
            }
            default -> { }
        }

        // ease the shown pose towards the goal (yaw is never wrapped, so it never spins the long way round)
        float km = 1f - (float) Math.exp(-EASE_MOVE * dt), kt = 1f - (float) Math.exp(-EASE_TURN * dt);
        if (dt == 0f) km = kt = 1f;
        sx += (tx - sx) * km;
        sy += (ty - sy) * km;
        sz += (tz - sz) * km;
        sYaw += (yaw - sYaw) * kt;
        sPitch += (pitch - sPitch) * kt;
        sDist = (float) Math.exp(Math.log(sDist) + (Math.log(dist) - Math.log(sDist)) * km);
    }

    /** The scene follows the cursor: dragging right moves the camera left, dragging down moves it up. */
    private void pan(Minecraft mc, double dx, double dy) {
        double perPx = 2.0 * Math.tan(Math.toRadians(fovDegrees(mc) * 0.5)) * dist / Math.max(1, mc.getWindow().getScreenHeight());
        Vec3 rgt = right(yaw), upv = up(yaw, pitch);
        tx += (-rgt.x * dx + upv.x * dy) * perPx;
        ty += (-rgt.y * dx + upv.y * dy) * perPx;
        tz += (-rgt.z * dx + upv.z * dy) * perPx;
    }

    private void fly(long win, float dt) {
        float f = (key(win, GLFW.GLFW_KEY_W) ? 1 : 0) - (key(win, GLFW.GLFW_KEY_S) ? 1 : 0);
        float r = (key(win, GLFW.GLFW_KEY_D) ? 1 : 0) - (key(win, GLFW.GLFW_KEY_A) ? 1 : 0);
        float u = (key(win, GLFW.GLFW_KEY_E) ? 1 : 0) - (key(win, GLFW.GLFW_KEY_Q) ? 1 : 0);
        if (f == 0 && r == 0 && u == 0) return;
        boolean fast = key(win, GLFW.GLFW_KEY_LEFT_SHIFT) || key(win, GLFW.GLFW_KEY_RIGHT_SHIFT);
        double step = BASE_FLY_SPEED * speed * (fast ? SPRINT : 1f) * dt;
        Vec3 fwd = forward(yaw, pitch), rgt = right(yaw);
        tx += (fwd.x * f + rgt.x * r) * step;
        ty += (fwd.y * f + u) * step;
        tz += (fwd.z * f + rgt.z * r) * step;
    }

    private static boolean flyKeyDown(long win) {
        return key(win, GLFW.GLFW_KEY_W) || key(win, GLFW.GLFW_KEY_A) || key(win, GLFW.GLFW_KEY_S)
                || key(win, GLFW.GLFW_KEY_D) || key(win, GLFW.GLFW_KEY_Q) || key(win, GLFW.GLFW_KEY_E);
    }

    private static boolean key(long win, int key) {
        return GLFW.glfwGetKey(win, key) == GLFW.GLFW_PRESS;
    }

    private static double[] cursor(long win) {
        double[] x = new double[1], y = new double[1];
        GLFW.glfwGetCursorPos(win, x, y);
        return new double[]{x[0], y[0]};
    }

    /** Degrees per pixel, vanilla's formula so the camera feels like the player's mouse look. */
    private static float lookSensitivity(Minecraft mc) {
        double s = mc.options.sensitivity().get() * 0.6 + 0.2;
        return (float) (s * s * s * 8.0 * 0.15);
    }

    private static double fovDegrees(Minecraft mc) {
        return mc.options.fov().get();
    }

    private static double aspect(Minecraft mc) {
        return (double) Math.max(1, mc.getWindow().getScreenWidth()) / Math.max(1, mc.getWindow().getScreenHeight());
    }
}
