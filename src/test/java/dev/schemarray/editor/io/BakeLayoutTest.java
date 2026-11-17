package dev.schemarray.editor.io;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.schemarray.editor.core.Box;
import dev.schemarray.editor.core.Vec3i;
import java.util.List;
import org.junit.jupiter.api.Test;

class BakeLayoutTest {
    private static Box box(int x, int y, int z, int sx, int sy, int sz) {
        return new Box(new Vec3i(x, y, z), new Vec3i(x + sx, y + sy, z + sz));
    }

    @Test
    void theOriginIsTheMinimumCornerOfEverythingAndRegionsAreRelativeToIt() {
        BakeLayout.Plan p = BakeLayout.plan(List.of(
                new BakeLayout.Source("a", box(100, 64, -20, 4, 3, 2)),
                new BakeLayout.Source("b", box(110, 60, -10, 5, 5, 5))));
        assertEquals(new Vec3i(100, 60, -20), p.origin());
        assertEquals(new Vec3i(15, 7, 15), p.enclosing());
        assertEquals(new Vec3i(0, 4, 0), p.regions().get(0).pos());
        assertEquals(new Vec3i(10, 0, 10), p.regions().get(1).pos());
        assertEquals(new Vec3i(5, 5, 5), p.regions().get(1).size());
        assertEquals(1, p.regions().get(1).source());
    }

    @Test
    void clashingNamesGetNumbers() {
        BakeLayout.Plan p = BakeLayout.plan(List.of(
                new BakeLayout.Source("tower", box(0, 0, 0, 1, 1, 1)),
                new BakeLayout.Source("tower", box(5, 0, 0, 1, 1, 1)),
                new BakeLayout.Source("tower", box(9, 0, 0, 1, 1, 1)),
                new BakeLayout.Source(" ", box(12, 0, 0, 1, 1, 1))));
        assertEquals(List.of("tower", "tower 2", "tower 3", "Part"), p.regions().stream().map(BakeLayout.Region::name).toList());
    }

    @Test
    void nothingToBakeGivesAnEmptyPlan() {
        assertTrue(BakeLayout.plan(List.of()).regions().isEmpty());
    }
}
