package dev.vitruvius.editor.ui;

import dev.vitruvius.editor.core.AssemblyNode;
import dev.vitruvius.editor.core.Box;
import dev.vitruvius.editor.core.ExternalNode;
import dev.vitruvius.editor.core.ObjectNode;
import dev.vitruvius.editor.core.Vec3i;
import dev.vitruvius.editor.pick.WorldBoxes;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * The maths of the Top view window (DESIGN 9), with no ImGui and no Minecraft: the map transform (north up, X to the
 * right, so world Z grows downwards on the screen), pan and zoom around the cursor, fitting, the grid step, hit tests
 * on the footprints, whole-block drag deltas, and the clipped hatch and dash segments the window draws.
 *
 * <p>Screen coordinates here are pixels relative to the top-left corner of the map canvas, {@code w} and {@code h} its size.
 */
public final class TopViewMath {
    private TopViewMath() {}

    /** Pixels per block limits. */
    public static final double MIN_SCALE = 0.125, MAX_SCALE = 32;

    /** The map: the world point (x, z) at the canvas centre and the zoom in pixels per block. */
    public record View(double cx, double cz, double scale) {}

    public enum Kind { OBJECT, CUTTER, AREA, ASSEMBLY }

    /** One footprint to draw and hit-test: the owner it belongs to, its box, whether it is an array copy, its kind. */
    public record Item(UUID id, Box box, boolean copy, Kind kind) {
        /** The top of the box: the map draws the lowest top first, so higher things cover lower ones. */
        public int top() {
            return box.maxExclusive().y();
        }
    }

    // ------------------------------------------------------------------ transform

    public static double screenX(View v, double w, double worldX) {
        return w / 2 + (worldX - v.cx()) * v.scale();
    }

    public static double screenY(View v, double h, double worldZ) {
        return h / 2 + (worldZ - v.cz()) * v.scale();
    }

    public static double worldX(View v, double w, double px) {
        return v.cx() + (px - w / 2) / v.scale();
    }

    public static double worldZ(View v, double h, double py) {
        return v.cz() + (py - h / 2) / v.scale();
    }

    /** Dragging the map by a pixel delta: the content follows the pointer. */
    public static View pan(View v, double dxPx, double dyPx) {
        return new View(v.cx() - dxPx / v.scale(), v.cz() - dyPx / v.scale(), v.scale());
    }

    /** Zooms by {@code factor}, keeping the world point under the pointer where it is. */
    public static View zoomAt(View v, double w, double h, double px, double py, double factor) {
        double scale = Math.max(MIN_SCALE, Math.min(MAX_SCALE, v.scale() * factor));
        double wx = worldX(v, w, px), wz = worldZ(v, h, py);
        return new View(wx - (px - w / 2) / scale, wz - (py - h / 2) / scale, scale);
    }

    /** A view that shows a whole box (its footprint) with a margin; a null box gives {@code fallback}. */
    public static View fit(Box box, double w, double h, double marginPx, View fallback) {
        if (box == null || w <= 2 * marginPx || h <= 2 * marginPx) return fallback;
        double bw = Math.max(1, box.size().x()), bd = Math.max(1, box.size().z());
        double scale = Math.min((w - 2 * marginPx) / bw, (h - 2 * marginPx) / bd);
        scale = Math.max(MIN_SCALE, Math.min(MAX_SCALE, scale));
        return new View(box.min().x() + box.size().x() / 2.0, box.min().z() + box.size().z() / 2.0, scale);
    }

    /** A view centred on a world point, the zoom kept. */
    public static View centered(View v, double x, double z) {
        return new View(x, z, v.scale());
    }

    /** The grid spacing in blocks: 8, 16, 32 ... the smallest that keeps lines at least {@code minPx} apart. */
    public static int gridStep(double scale, double minPx) {
        int step = 8;
        while (step * scale < minPx && step < (1 << 20)) step *= 2;
        return step;
    }

    /** "1 px = 0.5 blocks", "1 px = 4 blocks" for the header. */
    public static String scaleLabel(double scale) {
        double r = Math.round(100 / scale) / 100.0;
        String t = r == Math.rint(r) ? String.valueOf((long) r) : String.valueOf(r);
        return String.format(Locale.ROOT, "1 px = %s blocks", t);
    }

    // ------------------------------------------------------------------ items and hit tests

    /** The footprints of every pickable owner of the scene, lowest top first. */
    public static List<Item> items(dev.vitruvius.editor.core.Scene scene, WorldBoxes wb) {
        List<Item> out = new ArrayList<>();
        for (WorldBoxes.Owned o : wb.owners().values()) {
            Kind kind = o.owner() instanceof ObjectNode ob ? (ob.wire() ? Kind.CUTTER : Kind.OBJECT)
                    : o.owner() instanceof ExternalNode ? Kind.AREA
                    : o.owner() instanceof AssemblyNode ? Kind.ASSEMBLY : Kind.OBJECT;
            boolean copies = kind != Kind.ASSEMBLY;
            for (int i = 0; i < o.boxes().size(); i++) out.add(new Item(o.owner().id(), o.boxes().get(i), copies && i > 0, kind));
        }
        out.sort(java.util.Comparator.comparingInt(Item::top));
        return out;
    }

    /** Whether a screen point lies in the footprint of a box. */
    public static boolean inside(View v, double w, double h, Box b, double px, double py) {
        double x0 = screenX(v, w, b.min().x()), x1 = screenX(v, w, b.maxExclusive().x());
        double y0 = screenY(v, h, b.min().z()), y1 = screenY(v, h, b.maxExclusive().z());
        return px >= x0 && px <= x1 && py >= y0 && py <= y1;
    }

    /** The owner under a screen point: the one drawn on top (highest top, the later item on a tie), or null. */
    public static UUID hit(List<Item> items, View v, double w, double h, double px, double py) {
        UUID best = null;
        int bestTop = Integer.MIN_VALUE;
        for (Item it : items) {
            if (!inside(v, w, h, it.box(), px, py)) continue;
            if (it.top() >= bestTop) {
                bestTop = it.top();
                best = it.id();
            }
        }
        return best;
    }

    /** The drag of the pointer in pixels as a whole-block move on X and Z (Y stays). */
    public static Vec3i blockDelta(View v, double dxPx, double dyPx) {
        return new Vec3i((int) Math.round(dxPx / v.scale()), 0, (int) Math.round(dyPx / v.scale()));
    }

    // ------------------------------------------------------------------ drawing helpers

    /**
     * 45 degree hatch lines (bottom-left to top-right) every {@code step} px inside a rectangle, clipped to it. Each
     * entry is {x1, y1, x2, y2}.
     */
    public static List<double[]> hatch(double x0, double y0, double x1, double y1, double step) {
        List<double[]> out = new ArrayList<>();
        if (step <= 0 || x1 <= x0 || y1 <= y0) return out;
        for (double k = x0 + y0 + step; k < x1 + y1; k += step) {
            double xa = Math.max(x0, k - y1), xb = Math.min(x1, k - y0);
            if (xa < xb) out.add(new double[] {xa, k - xa, xb, k - xb});
        }
        return out;
    }

    /** The "on" pieces of a dashed line from (x1, y1) to (x2, y2): {x1, y1, x2, y2} each. */
    public static List<double[]> dashes(double x1, double y1, double x2, double y2, double on, double off) {
        List<double[]> out = new ArrayList<>();
        double len = Math.hypot(x2 - x1, y2 - y1);
        if (len < 1e-9 || on <= 0) return out;
        double ux = (x2 - x1) / len, uy = (y2 - y1) / len;
        for (double t = 0; t < len; t += on + off) {
            double e = Math.min(len, t + on);
            out.add(new double[] {x1 + ux * t, y1 + uy * t, x1 + ux * e, y1 + uy * e});
        }
        return out;
    }

    /**
     * The three corners of the camera's view wedge on the screen: the camera at (cx, cy), the view direction given
     * by the world heading (dirX, dirZ) (Z is down on the map), {@code half} the half angle in radians and {@code len}
     * the length in pixels. Returns {x1, y1, x2, y2} of the two far corners.
     */
    public static double[] wedge(double cx, double cy, double dirX, double dirZ, double half, double len) {
        double a = Math.atan2(dirZ, dirX);
        return new double[] {cx + Math.cos(a - half) * len, cy + Math.sin(a - half) * len, cx + Math.cos(a + half) * len, cy + Math.sin(a + half) * len};
    }
}
