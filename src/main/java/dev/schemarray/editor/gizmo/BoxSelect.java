package dev.schemarray.editor.gizmo;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;

/** The hit test and the combination rule of box select (B, or a drag on empty space). */
public final class BoxSelect {
    private BoxSelect() {}

    public enum Mode { REPLACE, ADD, SUBTRACT }

    /** A screen rectangle between two corners, in any order. */
    public record Rect(double x0, double y0, double x1, double y1) {
        public double left() { return Math.min(x0, x1); }
        public double right() { return Math.max(x0, x1); }
        public double top() { return Math.min(y0, y1); }
        public double bottom() { return Math.max(y0, y1); }

        public boolean contains(double x, double y) {
            return x >= left() && x <= right() && y >= top() && y <= bottom();
        }
    }

    /** An owner with the screen position of its box centre (NaN when the centre is behind the camera). */
    public record Item(UUID id, double cx, double cy) {}

    /** The owners whose projected box centre is inside the rectangle. */
    public static List<UUID> hits(Rect r, List<Item> items) {
        List<UUID> out = new ArrayList<>();
        for (Item i : items) {
            if (Double.isNaN(i.cx()) || Double.isNaN(i.cy())) continue;
            if (r.contains(i.cx(), i.cy())) out.add(i.id());
        }
        return out;
    }

    /** The selection after a box: replace, add (Shift) or subtract (Ctrl). */
    public static List<UUID> combine(Mode mode, List<UUID> current, List<UUID> hits) {
        LinkedHashSet<UUID> s = new LinkedHashSet<>();
        switch (mode) {
            case REPLACE -> s.addAll(hits);
            case ADD -> {
                s.addAll(current);
                s.addAll(hits);
            }
            case SUBTRACT -> {
                s.addAll(current);
                s.removeAll(hits);
            }
        }
        return new ArrayList<>(s);
    }
}
