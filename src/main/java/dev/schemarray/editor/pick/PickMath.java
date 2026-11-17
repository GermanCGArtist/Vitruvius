package dev.schemarray.editor.pick;

import dev.schemarray.editor.core.Box;
import dev.schemarray.editor.core.FrontOrder;
import dev.schemarray.editor.core.Vec3i;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Picking rules (EDITOR-2.0 section 6) without Minecraft: which owner a click means. A hit block of the schematic
 * world maps to the owners whose boxes contain it, the one in front wins; without a block hit the nearest box along
 * the ray wins. Alt cycles through every owner along the ray.
 */
public final class PickMath {
    private PickMath() {}

    /** Something that can be picked: an owner (object or top-level assembly) with its front number and world boxes. */
    public record Candidate(UUID id, int front, List<Box> boxes) {
        public Candidate {
            boxes = List.copyOf(boxes);
        }
    }

    /** The distance along the ray at which it enters {@code b}, or NaN when it misses (0 when it starts inside). */
    public static double rayBox(double[] o, double[] d, Box b, double maxDist) {
        double tmin = 0, tmax = maxDist;
        double[] lo = {b.min().x(), b.min().y(), b.min().z()};
        double[] hi = {b.maxExclusive().x(), b.maxExclusive().y(), b.maxExclusive().z()};
        for (int a = 0; a < 3; a++) {
            if (Math.abs(d[a]) < 1e-12) {
                if (o[a] < lo[a] || o[a] > hi[a]) return Double.NaN;
            } else {
                double t1 = (lo[a] - o[a]) / d[a], t2 = (hi[a] - o[a]) / d[a];
                if (t1 > t2) {
                    double t = t1;
                    t1 = t2;
                    t2 = t;
                }
                tmin = Math.max(tmin, t1);
                tmax = Math.min(tmax, t2);
                if (tmin > tmax) return Double.NaN;
            }
        }
        return tmin;
    }

    private record Hit(Candidate c, double t) {}

    private static double nearest(double[] o, double[] d, Candidate c, double maxDist) {
        double best = Double.NaN;
        for (Box b : c.boxes()) {
            double t = rayBox(o, d, b, maxDist);
            if (!Double.isNaN(t) && (Double.isNaN(best) || t < best)) best = t;
        }
        return best;
    }

    /**
     * The owner a click means, or null. {@code hitBlock} is the block the ray hit in the schematic world (null when
     * it hit nothing or there is no schematic world).
     */
    public static UUID choose(List<Candidate> all, Vec3i hitBlock, double[] origin, double[] dir, double maxDist) {
        if (hitBlock != null) {
            Candidate best = null;
            for (Candidate c : all) {
                boolean in = false;
                for (Box b : c.boxes()) if (b.contains(hitBlock)) in = true;
                if (in && (best == null || FrontOrder.compare(c.front(), c.id(), best.front(), best.id()) < 0)) best = c;
            }
            if (best != null) return best.id();
        }
        List<UUID> along = along(all, origin, dir, maxDist);
        return along.isEmpty() ? null : along.get(0);
    }

    /** Every owner whose box the ray crosses, nearest entry first (ties: front order). */
    public static List<UUID> along(List<Candidate> all, double[] origin, double[] dir, double maxDist) {
        List<Hit> hits = new ArrayList<>();
        for (Candidate c : all) {
            double t = nearest(origin, dir, c, maxDist);
            if (!Double.isNaN(t)) hits.add(new Hit(c, t));
        }
        hits.sort(Comparator.<Hit>comparingDouble(Hit::t)
                .thenComparing((a, b) -> FrontOrder.compare(a.c().front(), a.c().id(), b.c().front(), b.c().id())));
        List<UUID> out = new ArrayList<>();
        for (Hit h : hits) out.add(h.c().id());
        return out;
    }

    /** The cycle order for Alt: the primary pick first, then the rest along the ray. */
    public static List<UUID> cycleOrder(UUID primary, List<UUID> along) {
        List<UUID> out = new ArrayList<>();
        if (primary != null) out.add(primary);
        for (UUID id : along) if (!id.equals(primary)) out.add(id);
        return out;
    }

    /** The next owner after {@code current} in {@code order} (wraps); the first when current is not in it. */
    public static UUID cycle(List<UUID> order, UUID current) {
        if (order.isEmpty()) return null;
        int i = current == null ? -1 : order.indexOf(current);
        return order.get((i + 1) % order.size());
    }
}
