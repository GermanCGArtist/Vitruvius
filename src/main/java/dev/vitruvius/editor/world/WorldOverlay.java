package dev.vitruvius.editor.world;

import dev.liskov.ui.theme.MubTheme;
import dev.liskov.ui.host.LiskovUiHost;
import dev.vitruvius.editor.EditorMode;
import dev.vitruvius.editor.EditorSession;
import dev.vitruvius.editor.cam.EditorCamera;
import dev.vitruvius.editor.core.AssemblyNode;
import dev.vitruvius.editor.core.Box;
import dev.vitruvius.editor.core.Node;
import dev.vitruvius.editor.core.ObjectNode;
import dev.vitruvius.editor.core.Overlaps;
import dev.vitruvius.editor.mc.Picking;
import dev.vitruvius.editor.pick.ViewMath;
import dev.vitruvius.editor.pick.WorldBoxes;
import dev.vitruvius.editor.ui.TreeModel;
import imgui.ImDrawList;
import imgui.ImGui;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * What the editor draws in the world (WP 1.5, selection only): depth-tested box outlines for the selection, fainter
 * ones for array copies and the hover, drawn with Minecraft's gizmos like Heavymatica's wireframes; and name tags
 * for the selection, drawn with ImGui's background draw list from projected points (DESIGN 11.1, 11.7).
 * Missing nodes have no box and so draw nothing.
 */
public final class WorldOverlay {
    private WorldOverlay() {}

    private static final double INFLATE = 0.02;

    /** Registers the render hook. Call once from the client entrypoint. */
    public static void init() {
        LevelRenderEvents.BEFORE_GIZMOS.register(ctx -> renderOutlines());
    }

    private static int argb(int rgb, float alpha) {
        return (Math.round(alpha * 255f) << 24) | (rgb & 0xFFFFFF);
    }

    private static AABB aabb(Box b) {
        return new AABB(b.min().x(), b.min().y(), b.min().z(), b.maxExclusive().x(), b.maxExclusive().y(), b.maxExclusive().z()).inflate(INFLATE);
    }

    // ------------------------------------------------------------------ outlines (Minecraft gizmos)

    private record Line(AABB box, int rgb, float alpha, float width, boolean xray) {}

    private static void renderOutlines() {
        if (!EditorMode.isActive()) return;
        Minecraft mc = Minecraft.getInstance();
        EditorSession s = EditorSession.get();
        if (s == null || mc.levelRenderer == null || mc.player == null) return;
        List<Line> lines = new ArrayList<>();
        collect(s, lines);
        Gizmo.Preview preview = s.gizmo.preview();
        List<Box> overlaps = new ArrayList<>();
        for (Overlaps.Overlap ov : s.allOverlaps()) if (s.lostTo(ov.loser(), ov.winner()) != 0) overlaps.add(ov.box());
        List<Object[]> cutters = cutters(s);
        boolean externals = hasExternals(s);
        if (lines.isEmpty() && preview == null && overlaps.isEmpty() && cutters.isEmpty() && !externals && !s.boxEdit.active()) return;
        try (var ignored = mc.levelRenderer.collectPerFrameRenderThreadGizmos()) {
            for (Line l : lines) {
                var p = Gizmos.cuboid(l.box(), GizmoStyle.stroke(argb(l.rgb(), l.alpha()), l.width()));
                if (l.xray()) p.setAlwaysOnTop();
            }
            for (Box b : overlaps) overlapVolume(b);
            for (Object[] c : cutters) ghostBox((Box) c[0], (Boolean) c[1] ? BOOL : MubTheme.GUTTER, (Boolean) c[1] ? 0.9f : 0.6f, 1.5f, 0f);
            if (externals) drawExternals(s);
            if (preview != null) renderPreview(preview);
            if (s.boxEdit.active()) renderBoxEdit(s.boxEdit);
        }
    }

    /** Edit mode: the side under the pointer, and the box a running drag would make. */
    private static void renderBoxEdit(BoxEdit e) {
        Box ghost = e.ghostWorld();
        if (ghost != null) ghostBox(ghost, MubTheme.ACCENT, 1f, 2f, 0.08f);
        double[][] c = e.hoverRect();
        if (c != null) {
            Gizmos.rect(new Vec3(c[0][0], c[0][1], c[0][2]), new Vec3(c[1][0], c[1][1], c[1][2]), new Vec3(c[2][0], c[2][1], c[2][2]),
                    new Vec3(c[3][0], c[3][1], c[3][2]), GizmoStyle.strokeAndFill(argb(MubTheme.ACCENT, 1f), 2f, argb(MubTheme.ACCENT, 0.25f))).setAlwaysOnTop();
        }
    }

    // ------------------------------------------------------------------ objects of other mods (API 2)

    private static boolean hasExternals(EditorSession s) {
        for (var p : dev.vitruvius.api.ObjectTypes.placedIn(s.scene(), null, s.boundsEnv())) {
            if (!p.hidden()) return true;
        }
        return false;
    }

    private static final java.util.Set<String> FAILED_TYPES = new java.util.HashSet<>();

    /** What an object type draws with: dashed or solid lines and faces through Minecraft gizmos. */
    private static final class Drawer implements dev.vitruvius.api.OverlayDrawer {
        @Override
        public void box(Box box, int rgb, float alpha, boolean dashed) {
            if (dashed) ghostBox(box, rgb, alpha, 1.5f, 0f);
            else Gizmos.cuboid(aabb(box), GizmoStyle.stroke(argb(rgb, alpha), 1.5f)).setAlwaysOnTop();
        }

        @Override
        public void line(double x1, double y1, double z1, double x2, double y2, double z2, int rgb, float alpha, boolean dashed) {
            if (dashed) dashed(new Vec3(x1, y1, z1), new Vec3(x2, y2, z2), argb(rgb, alpha), 1.5f);
            else Gizmos.line(new Vec3(x1, y1, z1), new Vec3(x2, y2, z2), argb(rgb, alpha), 1.5f).setAlwaysOnTop();
        }

        @Override
        public void fill(Box box, int rgb, float alpha) {
            fillFaces(aabb(box), argb(rgb, alpha));
        }
    }

    private static void drawExternals(EditorSession s) {
        Drawer d = new Drawer();
        for (var p : dev.vitruvius.api.ObjectTypes.placedIn(s.scene(), null, s.boundsEnv())) {
            if (p.hidden()) continue;
            var type = dev.vitruvius.api.ObjectTypes.get(p.typeId());
            if (type == null) {                                     // the mod is not installed: a grey dashed box, the data stays
                Box b = Box.ofSize(new dev.vitruvius.editor.core.Vec3i(1, 1, 1)).transformed(p.world());
                ghostBox(b, MubTheme.GUTTER, 0.8f, 1.5f, 0.04f);
                continue;
            }
            boolean sel = s.selection.isSelected(p.id()) || s.selection.isSelected(p.ownerId());
            try {
                type.renderOutline(p.data(), p.world(), d, sel);
            } catch (RuntimeException | LinkageError e) {
                if (FAILED_TYPES.add(p.typeId())) System.out.println("[Vitruvius] object type " + p.typeId() + " failed to draw: " + e);
            }
        }
    }

    private static final int BOOL = 0xb48cf0;
    private static final int OVERLAP = 0xf07470;

    /** The boxes of wire cutter objects, each with whether an enabled Boolean uses it. */
    private static List<Object[]> cutters(EditorSession s) {
        List<Object[]> out = new ArrayList<>();
        WorldBoxes wb = s.boxes();
        var scene = s.scene();
        for (Node n : scene.allNodes()) {
            if (!(n instanceof ObjectNode o) || !o.wire()) continue;
            boolean used = false;
            for (Node x : scene.allNodes()) {
                List<dev.vitruvius.editor.core.Modifier> mods = x instanceof ObjectNode ox ? ox.mods() : x instanceof AssemblyNode ax ? ax.mods() : List.of();
                for (var m : mods) if (m instanceof dev.vitruvius.editor.core.BooleanModifier bm && bm.enabled && o.id().equals(bm.targetId)) used = true;
            }
            for (Box b : wb.objectBoxes(o.id())) out.add(new Object[] {b, used});
        }
        return out;
    }

    /** DESIGN 11.4: translucent faces and edges with depth off, plus a 45 degree hatch on the top face. */
    private static void overlapVolume(Box b) {
        AABB a = aabb(b);
        var edges = Gizmos.cuboid(a, GizmoStyle.stroke(argb(OVERLAP, 0.85f), 1.5f));
        edges.setAlwaysOnTop();
        int fill = argb(OVERLAP, 0.18f);
        double[] lo = {a.minX, a.minY, a.minZ}, hi = {a.maxX, a.maxY, a.maxZ};
        for (int axis = 0; axis < 3; axis++) {
            int u = (axis + 1) % 3, v = (axis + 2) % 3;
            for (int side = 0; side < 2; side++) {
                double[][] c = new double[4][3];
                for (int k = 0; k < 4; k++) {
                    c[k][axis] = side == 0 ? lo[axis] : hi[axis];
                    c[k][u] = (k == 0 || k == 3) ? lo[u] : hi[u];
                    c[k][v] = (k < 2) ? lo[v] : hi[v];
                }
                Gizmos.rect(new Vec3(c[0][0], c[0][1], c[0][2]), new Vec3(c[1][0], c[1][1], c[1][2]), new Vec3(c[2][0], c[2][1], c[2][2]),
                        new Vec3(c[3][0], c[3][1], c[3][2]), GizmoStyle.fill(fill)).setAlwaysOnTop();
            }
        }
        double w = hi[0] - lo[0], d = hi[2] - lo[2];
        double step = Math.max(1.0, Math.ceil((w + d) / 60.0));
        for (double t = lo[0] + lo[2] + step; t < hi[0] + hi[2]; t += step) {
            double xa = Math.max(lo[0], t - hi[2]), xb = Math.min(hi[0], t - lo[2]);
            if (xa < xb) {
                Gizmos.line(new Vec3(xa, hi[1], t - xa), new Vec3(xb, hi[1], t - xb), argb(OVERLAP, 0.5f), 1f).setAlwaysOnTop();
            }
        }
    }

    /** Labels "−N blocks from loser" at the top-face centre of overlaps whose loser or winner is hovered or selected. */
    public static void drawOverlapLabels() {
        EditorSession s = EditorSession.get();
        EditorCamera cam = EditorCamera.get();
        if (s == null || cam.isNavigating()) return;
        List<Overlaps.Overlap> all = s.allOverlaps();
        if (all.isEmpty()) return;
        UUID hover = Picking.hover();
        var scene = s.scene();
        Minecraft mc = Minecraft.getInstance();
        double w = Math.max(1, mc.getWindow().getScreenWidth()), h = Math.max(1, mc.getWindow().getScreenHeight());
        ViewMath.Basis basis = ViewMath.Basis.of(cam.yaw(), cam.pitch());
        Vec3 cp = cam.position();
        double[] c = {cp.x, cp.y, cp.z};
        double fov = mc.options.fov().get();
        float sc = LiskovUiHost.scale();
        ImDrawList dl = ImGui.getBackgroundDrawList();
        MubTheme.push(MubTheme.monoS());
        float fh = ImGui.getFontSize(), tagH = 18 * sc;
        java.util.Set<String> done = new java.util.HashSet<>();
        List<float[]> placed = new ArrayList<>();
        for (Overlaps.Overlap ov : all) {
            boolean hot = ov.loser().equals(hover) || ov.winner().equals(hover) || s.selection.isSelected(ov.loser()) || s.selection.isSelected(ov.winner());
            if (!hot || !done.add(ov.loser() + ">" + ov.winner())) continue;
            long n = s.lostTo(ov.loser(), ov.winner());
            if (n == 0) continue;
            Node loser = scene.byId(ov.loser());
            if (loser == null) continue;
            String text = n >= 0 ? String.format(java.util.Locale.ROOT, "−%,d blocks from %s", n, loser.name()) : "overlap with front object, counting…";
            Box b = ov.box();
            double[] p = ViewMath.project(basis, c, fov, w, h, (b.min().x() + b.maxExclusive().x()) * 0.5, b.maxExclusive().y(),
                    (b.min().z() + b.maxExclusive().z()) * 0.5);
            if (p == null) continue;
            float tw = ImGui.calcTextSizeX(text) + 12 * sc;
            float x = (float) p[0] - tw * 0.5f, y = (float) p[1] - tagH * 0.5f;
            boolean hit = true;
            for (int tries = 0; tries <= 3 && hit; tries++) {
                hit = false;
                for (float[] r : placed) if (x < r[2] && x + tw > r[0] && y < r[3] && y + tagH > r[1]) hit = true;
                if (hit) y -= tagH + 4 * sc;
            }
            if (hit) continue;
            placed.add(new float[] {x, y, x + tw, y + tagH});
            dl.addRectFilled(x, y, x + tw, y + tagH, MubTheme.u32(0x14161a, 0.82f), 4 * sc);
            dl.addText(x + 6 * sc, y + (tagH - fh) * 0.5f, MubTheme.u32(OVERLAP), text);
        }
        ImGui.popFont();
    }

    // ------------------------------------------------------------------ the drag ghost (DESIGN 11.3) and the snap face (11.10)

    private static final double DASH_ON = 0.5, DASH_OFF = 0.35;
    private static final int MAX_DASHES_PER_EDGE = 48;

    private static Vec3 lerp(Vec3 a, Vec3 b, double t) {
        return new Vec3(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t, a.z + (b.z - a.z) * t);
    }

    private static void dashed(Vec3 a, Vec3 b, int color, float width) {
        double len = Math.sqrt((b.x - a.x) * (b.x - a.x) + (b.y - a.y) * (b.y - a.y) + (b.z - a.z) * (b.z - a.z));
        if (len < 1e-6) return;
        double on = DASH_ON, off = DASH_OFF, period = on + off;
        if (len / period > MAX_DASHES_PER_EDGE) {                       // long edges: longer dashes, not thousands of lines
            double k = len / period / MAX_DASHES_PER_EDGE;
            on *= k;
            period *= k;
        }
        for (double t = 0; t < len; t += period) {
            Gizmos.line(lerp(a, b, t / len), lerp(a, b, Math.min(len, t + on) / len), color, width).setAlwaysOnTop();
        }
    }

    /** A dashed, depth-off box with faint filled faces. */
    private static void ghostBox(Box b, int rgb, float strokeAlpha, float width, float fillAlpha) {
        AABB a = aabb(b);
        double[] lo = {a.minX, a.minY, a.minZ}, hi = {a.maxX, a.maxY, a.maxZ};
        int stroke = argb(rgb, strokeAlpha);
        for (int axis = 0; axis < 3; axis++) {
            int u = (axis + 1) % 3, v = (axis + 2) % 3;
            for (int i = 0; i < 2; i++) {
                for (int j = 0; j < 2; j++) {
                    double[] p = new double[3], q = new double[3];
                    p[u] = q[u] = i == 0 ? lo[u] : hi[u];
                    p[v] = q[v] = j == 0 ? lo[v] : hi[v];
                    p[axis] = lo[axis];
                    q[axis] = hi[axis];
                    dashed(new Vec3(p[0], p[1], p[2]), new Vec3(q[0], q[1], q[2]), stroke, width);
                }
            }
        }
        if (fillAlpha > 0) fillFaces(a, argb(rgb, fillAlpha));
    }

    private static void fillFaces(AABB a, int fill) {
        double[] lo = {a.minX, a.minY, a.minZ}, hi = {a.maxX, a.maxY, a.maxZ};
        {
            for (int axis = 0; axis < 3; axis++) {
                int u = (axis + 1) % 3, v = (axis + 2) % 3;
                for (int side = 0; side < 2; side++) {
                    double[][] c = new double[4][3];
                    for (int k = 0; k < 4; k++) {
                        c[k][axis] = side == 0 ? lo[axis] : hi[axis];
                        c[k][u] = (k == 0 || k == 3) ? lo[u] : hi[u];
                        c[k][v] = (k < 2) ? lo[v] : hi[v];
                    }
                    Gizmos.rect(new Vec3(c[0][0], c[0][1], c[0][2]), new Vec3(c[1][0], c[1][1], c[1][2]), new Vec3(c[2][0], c[2][1], c[2][2]),
                            new Vec3(c[3][0], c[3][1], c[3][2]), GizmoStyle.fill(fill)).setAlwaysOnTop();
                }
            }
        }
    }

    private static void renderPreview(Gizmo.Preview p) {
        for (Box b : p.after()) ghostBox(b, MubTheme.ACCENT, 1f, 2f, 0.06f);
        if (p.from() != null && p.to() != null) {
            Gizmos.line(new Vec3(p.from()[0], p.from()[1], p.from()[2]), new Vec3(p.to()[0], p.to()[1], p.to()[2]), argb(MubTheme.ACCENT, 0.5f), 1f)
                    .setAlwaysOnTop();
        }
        var f = p.face();
        if (f != null) {
            double[][] c = new double[4][3];
            int[] o = f.axis() == 0 ? new int[] {1, 2} : f.axis() == 1 ? new int[] {0, 2} : new int[] {0, 1};
            double[] s1 = {f.lo1(), f.hi1(), f.hi1(), f.lo1()}, s2 = {f.lo2(), f.lo2(), f.hi2(), f.hi2()};
            for (int k = 0; k < 4; k++) {
                c[k][f.axis()] = f.coord();
                c[k][o[0]] = s1[k];
                c[k][o[1]] = s2[k];
            }
            Gizmos.rect(new Vec3(c[0][0], c[0][1], c[0][2]), new Vec3(c[1][0], c[1][1], c[1][2]), new Vec3(c[2][0], c[2][1], c[2][2]),
                    new Vec3(c[3][0], c[3][1], c[3][2]), GizmoStyle.strokeAndFill(argb(MubTheme.ACCENT, 1f), 2f, argb(MubTheme.ACCENT, 0.15f))).setAlwaysOnTop();
        }
    }

    private static void collect(EditorSession s, List<Line> out) {
        WorldBoxes wb = s.boxes();
        var scene = s.scene();
        UUID active = s.selection.active();
        UUID hover = Picking.hover();
        if (hover != null && !s.selection.isSelected(hover)) {
            WorldBoxes.Owned o = wb.ownerBoxes(hover);
            if (o != null) {
                boolean linked = o.owner() instanceof AssemblyNode a && a.isLinked();
                out.add(new Line(aabb(o.union()), linked ? Theme_LINK : MubTheme.FG, linked ? 0.6f : 0.45f, 1.5f, false));
            }
        }
        for (UUID id : s.gizmo.boxHits()) {                  // box select: what the rectangle will select
            WorldBoxes.Owned o = wb.ownerBoxes(id);
            if (o != null && !s.selection.isSelected(id)) out.add(new Line(aabb(o.union()), MubTheme.FG, 0.45f, 1.5f, false));
        }
        boolean ghost = s.gizmo.preview() != null;           // a drag runs: the original stays as a faint solid outline
        for (UUID id : s.selection.ids()) {
            Node n = scene.byId(id);
            if (n == null) continue;
            boolean isActive = id.equals(active);
            boolean locked = TreeModel.lockedPart(n);
            int rgb = locked ? MubTheme.GUTTER : MubTheme.ACCENT;
            float alpha = ghost ? 0.35f : isActive || locked ? 1f : 0.75f;
            float width = ghost ? 1.5f : isActive ? 2f : 1.5f;
            if (n instanceof ObjectNode o) {
                List<Box> boxes = wb.objectBoxes(o.id());
                if (boxes.isEmpty()) continue;                                    // missing or hidden: nothing to draw
                out.add(new Line(aabb(boxes.get(0)), rgb, alpha, width, false));
                if (!locked && !ghost) out.add(new Line(aabb(boxes.get(0)), rgb, isActive ? 0.30f : 0.20f, 1f, true));
                for (int i = 1; i < boxes.size(); i++) out.add(new Line(aabb(boxes.get(i)), rgb, 0.5f, 1f, false));
                Node owner = scene.ownerOf(o);
                if (owner != o && wb.ownerBoxes(owner.id()) != null) out.add(new Line(aabb(wb.ownerBoxes(owner.id()).union()), rgb, 0.35f, 1f, false));
            } else if (n instanceof AssemblyNode) {
                WorldBoxes.Owned ow = wb.ownerBoxes(id);
                if (ow == null) continue;
                out.add(new Line(aabb(ow.union()), rgb, alpha, width, false));
                if (!locked && !ghost) out.add(new Line(aabb(ow.union()), rgb, isActive ? 0.30f : 0.20f, 1f, true));
                for (Box cell : wb.cellBoxes(id)) out.add(new Line(aabb(cell), rgb, 0.5f, 1f, false));     // the copies of an assembly array
            } else if (n instanceof dev.vitruvius.editor.core.ExternalNode) {
                List<Box> boxes = wb.objectBoxes(id);
                if (boxes.isEmpty()) continue;
                out.add(new Line(aabb(boxes.get(0)), rgb, alpha, width, false));
                if (!locked && !ghost) out.add(new Line(aabb(boxes.get(0)), rgb, isActive ? 0.30f : 0.20f, 1f, true));
                for (int i = 1; i < boxes.size(); i++) out.add(new Line(aabb(boxes.get(i)), rgb, 0.5f, 1f, false));
            } else {
                Box u = wb.unionOf(scene, List.of(id));                              // folder, project, Loose: what they hold
                if (u != null) out.add(new Line(aabb(u), rgb, 0.35f, 1f, false));
            }
        }
    }

    private static final int Theme_LINK = 0x7fc8f0;

    // ------------------------------------------------------------------ name tags (ImGui)

    /** Draws the tags of the selection with the background draw list. Call from the layer's draw. */
    public static void drawTags() {
        EditorSession s = EditorSession.get();
        EditorCamera cam = EditorCamera.get();
        if (s == null || s.selection.isEmpty() || cam.isNavigating()) return;       // they flicker while flying and orbiting
        Minecraft mc = Minecraft.getInstance();
        WorldBoxes wb = s.boxes();
        var scene = s.scene();
        List<UUID> ids = s.selection.ids();
        double w = Math.max(1, mc.getWindow().getScreenWidth()), h = Math.max(1, mc.getWindow().getScreenHeight());
        ViewMath.Basis basis = ViewMath.Basis.of(cam.yaw(), cam.pitch());
        Vec3 cp = cam.position();
        double[] c = {cp.x, cp.y, cp.z};
        double fov = mc.options.fov().get();
        float sc = LiskovUiHost.scale();

        // up to 8 tags; with more, only the active one, with "+n"
        List<UUID> tagged = new ArrayList<>(ids);
        UUID active = s.selection.active();
        String extra = null;
        if (tagged.size() > 8 && active != null) {
            extra = "+" + (tagged.size() - 1);
            tagged = List.of(active);
        }
        ImDrawList dl = ImGui.getBackgroundDrawList();
        MubTheme.push(MubTheme.monoS());
        float fh = ImGui.getFontSize(), tagH = 18 * sc;
        List<float[]> placed = new ArrayList<>();
        for (UUID id : tagged) {
            Node n = scene.byId(id);
            if (n == null) continue;
            Box box = n instanceof ObjectNode ? firstBox(wb, id) : wb.unionOf(scene, List.of(id));
            if (box == null) continue;
            double[] p = ViewMath.project(basis, c, fov, w, h, (box.min().x() + box.maxExclusive().x()) * 0.5, box.maxExclusive().y(),
                    (box.min().z() + box.maxExclusive().z()) * 0.5);
            if (p == null) continue;
            String text = n.name() + (extra != null && id.equals(active) ? "  " + extra : "");
            float tw = ImGui.calcTextSizeX(text) + 12 * sc;
            float x = (float) p[0] - tw * 0.5f, y = (float) p[1] - 14 * sc - tagH;
            boolean hidden = false;
            for (int tries = 0; tries <= 3; tries++) {
                boolean hit = false;
                for (float[] r : placed) if (x < r[2] && x + tw > r[0] && y < r[3] && y + tagH > r[1]) hit = true;
                if (!hit) break;
                if (tries == 3) hidden = true;
                y -= tagH + 4 * sc;
            }
            if (hidden) continue;
            placed.add(new float[] {x, y, x + tw, y + tagH});
            dl.addRectFilled(x, y, x + tw, y + tagH, MubTheme.u32(0x14161a, 0.82f), 4 * sc);
            dl.addText(x + 6 * sc, y + (tagH - fh) * 0.5f, MubTheme.u32(MubTheme.ACCENT), text);
        }
        ImGui.popFont();
    }

    private static Box firstBox(WorldBoxes wb, UUID objectId) {
        List<Box> b = wb.objectBoxes(objectId);
        return b.isEmpty() ? null : b.get(0);
    }
}
