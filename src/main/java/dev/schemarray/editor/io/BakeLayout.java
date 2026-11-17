package dev.schemarray.editor.io;

import dev.schemarray.editor.core.Box;
import dev.schemarray.editor.core.Vec3i;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Where the sub-regions of a baked {@code .litematic} go (no Minecraft types). Every source is a box in the world
 * (one sub-region of one part, after its world transform). The bake origin is the minimum corner of all of them, so
 * the result's coordinates start at 0,0,0; each sub-region sits at its box's minimum minus that origin. Names are
 * made unique (a clash gets " 2", " 3", ...), keeping the order.
 */
public final class BakeLayout {
    private BakeLayout() {}

    public record Source(String name, Box worldBox) {}

    /** {@code source} is the index in the input list. */
    public record Region(String name, Vec3i pos, Vec3i size, int source) {}

    public record Plan(Vec3i origin, Vec3i enclosing, List<Region> regions) {}

    /** The layout for these sources; empty regions when there are none. */
    public static Plan plan(List<Source> sources) {
        if (sources.isEmpty()) return new Plan(Vec3i.ZERO, Vec3i.ZERO, List.of());
        Box all = null;
        for (Source s : sources) all = all == null ? s.worldBox() : all.union(s.worldBox());
        Vec3i origin = all.min();
        List<Region> out = new ArrayList<>();
        Set<String> used = new HashSet<>();
        for (int i = 0; i < sources.size(); i++) {
            Source s = sources.get(i);
            String base = s.name().isBlank() ? "Part" : s.name();
            String name = base;
            for (int n = 2; !used.add(name); n++) name = base + " " + n;
            out.add(new Region(name, s.worldBox().min().sub(origin), s.worldBox().size(), i));
        }
        return new Plan(origin, all.size(), out);
    }
}
