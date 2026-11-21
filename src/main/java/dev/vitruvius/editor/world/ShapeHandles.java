package dev.vitruvius.editor.world;

import dev.cg.uihost.Theme;
import dev.cg.uihost.UiHost;
import dev.vitruvius.editor.EditorSession;
import dev.vitruvius.editor.EditorSession.Tool;
import dev.vitruvius.editor.cam.EditorCamera;
import dev.vitruvius.editor.core.Box;
import dev.vitruvius.editor.core.Mirror;
import dev.vitruvius.editor.core.Node;
import dev.vitruvius.editor.core.ObjectNode;
import dev.vitruvius.editor.core.Rot;
import dev.vitruvius.editor.core.Vec3i;
import dev.vitruvius.editor.gizmo.AxisProjection;
import dev.vitruvius.editor.mc.LitematicaAdapter;
import dev.vitruvius.editor.mc.Picking;
import dev.vitruvius.editor.ops.ResizeShape;
import dev.vitruvius.editor.pick.ViewMath;
import dev.vitruvius.editor.ui.ToastQueue;
import dev.vitruvius.editor.view.Ui;
import dev.vitruvius.shape.GeneratorSettings;
import dev.vitruvius.shape.ShapeGenerator;
import dev.vitruvius.shape.ShapeSpec;
import dev.vitruvius.shape.ShapeType;
import dev.vitruvius.shape.VoxelResult;
import dev.vitruvius.shape.mc.ShapePresets;
import dev.vitruvius.shape.mc.ShapeSchematics;
import imgui.ImDrawList;
import imgui.ImGui;
import net.minecraft.client.Minecraft;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Drag handles on the six faces of a generated shape (a schematic made by the Shape generator, which carries its
 * settings in the file's description). Dragging a face changes that side's size by whole blocks and regenerates the
 * shape with the same blocks and patterns; a dashed ghost shows the result while dragging and the new size is in the
 * HUD. The Properties window has size fields for the same thing ({@link #resize}). Only unturned, unmirrored objects
 * inside a project can be resized: the handles do not show for anything else.
 */
public final class ShapeHandles {
    private static final String PREFIX = "{";
    private static final int MAX = ShapeSpec.MAX_SIZE;

    private record Cached(long checkedMs, long mtime, GeneratorSettings g) {}

    private static final Map<Path, Cached> CACHE = new HashMap<>();

    private final EditorSession s;
    private UUID targetId;
    private GeneratorSettings g;
    private Box box;
    private int hover = -1, drag = -1;
    private double t0;
    private double[] p0;
    private int blocks;

    public ShapeHandles(EditorSession s) {
        this.s = s;
    }

    public boolean active() {
        return drag >= 0;
    }

    public boolean owning() {
        return drag >= 0 || hover >= 0;
    }

    public void cancel() {
        drag = -1;
        blocks = 0;
    }

    // ------------------------------------------------------------------ what can be resized

    /** The shape settings stored in the object's file, or null when it is not a generated shape or cannot be resized. */
    public static GeneratorSettings settingsOf(EditorSession s, ObjectNode o) {
        if (o.transform().rot() != Rot.NONE || o.transform().mirror() != Mirror.NONE) return null;
        Path f = s.fileOf(o);
        if (f == null) return null;
        long now = System.currentTimeMillis();
        Cached c = CACHE.get(f);
        if (c != null && now - c.checkedMs < 500) return c.g;
        long mtime;
        try {
            mtime = Files.getLastModifiedTime(f).toMillis();
        } catch (IOException e) {
            CACHE.remove(f);
            return null;
        }
        if (c != null && c.mtime == mtime) {
            CACHE.put(f, new Cached(now, mtime, c.g));
            return c.g;
        }
        String d = LitematicaAdapter.shared().description(f);
        GeneratorSettings g = d != null && d.startsWith(PREFIX) ? ShapePresets.fromJson(d) : null;
        if (CACHE.size() > 256) CACHE.clear();
        CACHE.put(f, new Cached(now, mtime, g));
        return g;
    }

    /** The size of the shape as the object has it now (a plane is one thick along its axis). */
    public static Vec3i sizeOf(GeneratorSettings g) {
        ShapeSpec sp = g.spec().clamped();
        return new Vec3i(sp.sizeX(), sp.sizeY(), sp.sizeZ());
    }

    /** Whether this side can change: a plane keeps its thickness. */
    private static boolean resizable(GeneratorSettings g, int axis) {
        return !(g.spec().type() == ShapeType.PLANE && g.spec().clamped().axis().ordinal() == axis);
    }

    /** Regenerates the shape at a new size and applies it as one undo step; {@code newPos} is the new low corner. */
    public static boolean resize(EditorSession s, ObjectNode o, GeneratorSettings g, Vec3i newSize, Vec3i newPos) {
        ShapeSpec sp = g.spec();
        ShapeSpec ns = new ShapeSpec(sp.type(), newSize.x(), newSize.y(), newSize.z(), sp.axis(), sp.thickness(),
                sp.param1(), sp.param2(), sp.anchorCenter()).clamped();
        GeneratorSettings ng = new GeneratorSettings(ns, g.outline(), g.fill(), g.name());
        VoxelResult r = ShapeGenerator.generate(ng, () -> false);
        return regenerate(s, o, ng, r, newPos);
    }

    /**
     * Writes the generated {@code r} for the settings {@code ng} (they are stored in the file) and makes the object use
     * it, as one undo step. Returns false (with a toast) when it cannot be done.
     */
    public static boolean regenerate(EditorSession s, ObjectNode o, GeneratorSettings ng, VoxelResult r, Vec3i newPos) {
        if (r == null || r.blockCount() == 0) {
            s.toast(ToastQueue.Level.WARN, "That makes an empty shape");
            return false;
        }
        String tmpRel = ResizeShape.CACHE_DIR;
        String baseDir = s.undoStack().context().baseDir(o);
        if (s.scene().projectOf(o) != null && !baseDir.isEmpty()) tmpRel = baseDir + "/" + ResizeShape.CACHE_DIR;
        String tmpName = Integer.toHexString(o.id().hashCode()) + "-" + Integer.toHexString(java.util.concurrent.ThreadLocalRandom.current().nextInt());
        Path dir = s.schematicsDir().resolve(tmpRel).normalize();
        try {
            Files.createDirectories(dir);
            if (!ShapeSchematics.build(r, o.name(), ShapePresets.toJson(ng)).writeToFile(dir, tmpName, true)) {
                s.toast(ToastQueue.Level.WARN, "Could not write the shape");
                return false;
            }
        } catch (IOException | RuntimeException e) {
            s.toast(ToastQueue.Level.WARN, "Could not write the shape: " + e.getMessage());
            return false;
        }
        ShapeSpec ns = ng.spec();
        String size = ns.sizeX() + "x" + ns.sizeY() + "x" + ns.sizeZ();
        Path before = s.fileOf(o);
        boolean ok = s.actions.execute(new ResizeShape(o.id(), tmpRel + "/" + tmpName + ".litematic", newPos, size, true));
        if (before != null) CACHE.remove(before);
        return ok;
    }

    // ------------------------------------------------------------------ per frame

    private void retarget() {
        targetId = null;
        g = null;
        box = null;
        List<UUID> ids = s.selection.ids();
        if (ids.size() != 1 || s.selectionLocked()) return;
        Node n = s.scene().byId(ids.get(0));
        if (!(n instanceof ObjectNode o) || n.hidden()) return;
        GeneratorSettings gs = settingsOf(s, o);
        if (gs == null) return;
        Vec3i size = sizeOf(gs), pos = o.transform().pos();
        targetId = o.id();
        g = gs;
        box = new Box(pos, pos.add(size));
    }

    private static double[] faceCentre(Box b, int face) {
        int axis = face / 2;
        double[] c = new double[3];
        for (int a = 0; a < 3; a++) c[a] = (b.min().get(a) + b.maxExclusive().get(a)) * 0.5;
        c[axis] = face % 2 == 0 ? b.min().get(axis) : b.maxExclusive().get(axis);
        return c;
    }

    /** The box while dragging: the dragged face moved by {@code blocks}. */
    private Box draggedBox() {
        int axis = drag / 2;
        int[] lo = {box.min().x(), box.min().y(), box.min().z()};
        int[] hi = {box.maxExclusive().x(), box.maxExclusive().y(), box.maxExclusive().z()};
        if (drag % 2 == 0) lo[axis] += blocks;
        else hi[axis] += blocks;
        return new Box(new Vec3i(lo[0], lo[1], lo[2]), new Vec3i(hi[0], hi[1], hi[2]));
    }

    /** Draws the handles, tracks hover and advances a drag. Called once per frame by the gizmo. */
    public void frame(Minecraft mc, boolean imguiOverMouse) {
        if (drag < 0) retarget();
        else if (s.selection.ids().size() != 1 || !s.selection.ids().get(0).equals(targetId) || s.scene().byId(targetId) == null) cancel();
        hover = -1;
        Tool tool = s.tool();
        EditorCamera cam = EditorCamera.get();
        if (targetId == null || box == null || tool == Tool.ROTATE || cam.isNavigating()) {
            if (drag >= 0) cancel();
            return;
        }
        var p = cam.position();
        ViewMath.Basis basis = ViewMath.Basis.of(cam.yaw(), cam.pitch());
        double[] cp = {p.x, p.y, p.z};
        double fov = mc.options.fov().get(), w = Math.max(1, mc.getWindow().getScreenWidth()), h = Math.max(1, mc.getWindow().getScreenHeight());
        double mx = UiHost.cursorX(), my = UiHost.cursorY();
        float sc = UiHost.scale();

        if (drag >= 0) {
            double[] ray = Picking.rayAt(mc, mx, my);
            double t = AxisProjection.axisParam(new double[] {ray[0], ray[1], ray[2]}, new double[] {ray[3], ray[4], ray[5]}, p0, drag / 2);
            if (!Double.isNaN(t)) {
                int b = AxisProjection.blocks(t - t0);
                int axis = drag / 2, sign = drag % 2 == 0 ? -1 : 1;
                int size = box.size().get(axis);
                int grow = Math.max(1 - size, Math.min(MAX - size, sign * b));      // new size = size + grow, within 1..MAX
                blocks = sign * grow;
            }
        }

        ImDrawList dl = ImGui.getBackgroundDrawList();
        Box shown = drag >= 0 ? draggedBox() : box;
        double bestD = 14 * sc;
        for (int f = 0; f < 6; f++) {
            if (!resizable(g, f / 2)) continue;
            double[] c = faceCentre(shown, f);
            double[] sp = ViewMath.project(basis, cp, fov, w, h, c[0], c[1], c[2]);
            if (sp == null) continue;
            double d = Math.hypot(sp[0] - mx, sp[1] - my);
            if (drag < 0 && !imguiOverMouse && d < bestD) {
                bestD = d;
                hover = f;
            }
        }
        for (int f = 0; f < 6; f++) {
            if (!resizable(g, f / 2)) continue;
            double[] c = faceCentre(shown, f);
            double[] sp = ViewMath.project(basis, cp, fov, w, h, c[0], c[1], c[2]);
            if (sp == null) continue;
            boolean on = f == hover || f == drag;
            float r = (on ? 8.5f : 6.5f) * sc;
            dl.addCircleFilled((float) sp[0], (float) sp[1], r + 1.5f * sc, Ui.c(0x101216, 0.85f), 20);
            dl.addCircleFilled((float) sp[0], (float) sp[1], r, Ui.c(on ? Theme.ACCENT : 0xdfe5ee, on ? 1f : 0.9f), 20);
        }
        if (hover >= 0 || drag >= 0) UiHost.setCursor(UiHost.Cursor.RESIZE_ALL);
    }

    // ------------------------------------------------------------------ mouse

    /** A left press: starts a drag when a handle is hovered. */
    public boolean press(Minecraft mc) {
        if (hover < 0 || box == null || targetId == null) return false;
        drag = hover;
        blocks = 0;
        p0 = faceCentre(box, drag);
        double[] ray = Picking.rayAt(mc, UiHost.cursorX(), UiHost.cursorY());
        t0 = AxisProjection.axisParam(new double[] {ray[0], ray[1], ray[2]}, new double[] {ray[3], ray[4], ray[5]}, p0, drag / 2);
        if (Double.isNaN(t0)) {
            drag = -1;
            return false;
        }
        return true;
    }

    /** The left button went up: applies the resize. Returns true when a drag ended. */
    public boolean release() {
        if (drag < 0) return false;
        int face = drag, b = blocks;
        UUID id = targetId;
        GeneratorSettings gs = g;
        Box start = box;
        drag = -1;
        blocks = 0;
        if (b == 0 || id == null || gs == null || start == null) return true;
        Node n = s.scene().byId(id);
        if (!(n instanceof ObjectNode o)) return true;
        int axis = face / 2;
        int[] sz = {start.size().x(), start.size().y(), start.size().z()};
        int[] pos = {start.min().x(), start.min().y(), start.min().z()};
        if (face % 2 == 0) {
            sz[axis] -= b;
            pos[axis] += b;
        } else {
            sz[axis] += b;
        }
        resize(s, o, gs, new Vec3i(sz[0], sz[1], sz[2]), new Vec3i(pos[0], pos[1], pos[2]));
        return true;
    }

    // ------------------------------------------------------------------ ghost and HUD

    /** The ghost for the world overlay while dragging, or null. */
    public Gizmo.Preview preview() {
        if (drag < 0 || box == null) return null;
        return new Gizmo.Preview(List.of(box), List.of(draggedBox()), null, null, null, false);
    }

    /** The HUD pill while dragging, or null. */
    public Gizmo.Hud hud() {
        if (drag < 0 || box == null) return null;
        Box d = draggedBox();
        Vec3i z = d.size();
        return new Gizmo.Hud("Resize " + "XYZ".charAt(drag / 2), -1, false, z.x() + " x " + z.y() + " x " + z.z(), "blocks", null);
    }
}
