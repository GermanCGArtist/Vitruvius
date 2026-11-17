package dev.vitruvius.editor.gizmo;

import dev.vitruvius.editor.core.Box;
import dev.vitruvius.editor.core.Vec3i;

import java.util.List;

/**
 * Pivot modes of the toolbar (DESIGN 5 and 11.8). The pivot is always a block: the block that holds the centre, with
 * an even size choosing the lower of the two middle blocks (so an 8-wide box at x 0 pivots on block 3, as the
 * Properties rotation buttons always did).
 */
public final class Pivots {
    private Pivots() {}

    public enum Mode {
        MEDIAN("Median"), ACTIVE("Active object"), BOUNDS("Bounding box");

        public final String label;

        Mode(String label) {
            this.label = label;
        }
    }

    /** The block holding a centre coordinate (a centre on a block edge goes to the lower block). */
    public static int blockOf(double centre) {
        return (int) Math.floor(centre - 0.5);
    }

    public static double[] centreOf(Box b) {
        return new double[] {(b.min().x() + b.maxExclusive().x()) * 0.5, (b.min().y() + b.maxExclusive().y()) * 0.5,
                (b.min().z() + b.maxExclusive().z()) * 0.5};
    }

    private static Vec3i block(double[] c) {
        return new Vec3i(blockOf(c[0]), blockOf(c[1]), blockOf(c[2]));
    }

    /**
     * @param boxes  one box per selected item (what each owns)
     * @param active the active item's box, or null
     */
    public static Vec3i compute(Mode mode, List<Box> boxes, Box active) {
        if (boxes.isEmpty()) return active == null ? Vec3i.ZERO : block(centreOf(active));
        if (mode == Mode.ACTIVE && active != null) return block(centreOf(active));
        if (mode == Mode.BOUNDS) {
            Box u = boxes.get(0);
            for (Box b : boxes) u = u.union(b);
            return block(centreOf(u));
        }
        double[] sum = new double[3];
        for (Box b : boxes) {
            double[] c = centreOf(b);
            for (int i = 0; i < 3; i++) sum[i] += c[i];
        }
        for (int i = 0; i < 3; i++) sum[i] /= boxes.size();
        return block(sum);
    }
}
