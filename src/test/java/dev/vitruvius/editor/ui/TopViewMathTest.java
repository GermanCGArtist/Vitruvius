package dev.vitruvius.editor.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vitruvius.editor.core.ArrayModifier;
import dev.vitruvius.editor.core.Box;
import dev.vitruvius.editor.core.ExternalNode;
import dev.vitruvius.editor.core.ObjectNode;
import dev.vitruvius.editor.core.ProjectNode;
import dev.vitruvius.editor.core.Scene;
import dev.vitruvius.editor.core.Transform;
import dev.vitruvius.editor.core.Vec3i;
import dev.vitruvius.editor.pick.WorldBoxes;
import dev.vitruvius.editor.ui.TopViewMath.Item;
import dev.vitruvius.editor.ui.TopViewMath.Kind;
import dev.vitruvius.editor.ui.TopViewMath.View;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TopViewMathTest {
    private static final double W = 300, H = 200;
    private final View v = new View(100, 50, 2);

    @Test
    void worldAndScreenAreInverseAndNorthIsUp() {
        double sx = TopViewMath.screenX(v, W, 110), sy = TopViewMath.screenY(v, H, 40);
        assertEquals(150 + 20, sx, 1e-9, "x grows to the right");
        assertEquals(100 - 20, sy, 1e-9, "a smaller Z (north) is higher on the screen");
        assertEquals(110, TopViewMath.worldX(v, W, sx), 1e-9);
        assertEquals(40, TopViewMath.worldZ(v, H, sy), 1e-9);
        assertEquals(150, TopViewMath.screenX(v, W, 100), 1e-9, "the centre is the canvas centre");
    }

    @Test
    void panMovesTheContentWithThePointer() {
        View p = TopViewMath.pan(v, 20, -10);
        // a world point that was at screen x moves by +20 px
        double before = TopViewMath.screenX(v, W, 120), after = TopViewMath.screenX(p, W, 120);
        assertEquals(20, after - before, 1e-9);
        assertEquals(-10, TopViewMath.screenY(p, H, 70) - TopViewMath.screenY(v, H, 70), 1e-9);
    }

    @Test
    void zoomKeepsThePointUnderTheCursor() {
        double px = 220, py = 60;
        double wx = TopViewMath.worldX(v, W, px), wz = TopViewMath.worldZ(v, H, py);
        View z = TopViewMath.zoomAt(v, W, H, px, py, 1.5);
        assertEquals(3, z.scale(), 1e-9);
        assertEquals(px, TopViewMath.screenX(z, W, wx), 1e-9);
        assertEquals(py, TopViewMath.screenY(z, H, wz), 1e-9);
    }

    @Test
    void zoomIsLimited() {
        View a = TopViewMath.zoomAt(v, W, H, 10, 10, 1e6);
        assertEquals(TopViewMath.MAX_SCALE, a.scale());
        View b = TopViewMath.zoomAt(v, W, H, 10, 10, 1e-6);
        assertEquals(TopViewMath.MIN_SCALE, b.scale());
    }

    @Test
    void fitShowsTheWholeFootprintWithAMargin() {
        Box box = new Box(new Vec3i(0, 0, 0), new Vec3i(100, 10, 40));
        View f = TopViewMath.fit(box, W, H, 10, v);
        assertEquals(50, f.cx(), 1e-9);
        assertEquals(20, f.cz(), 1e-9);
        assertEquals(2.8, f.scale(), 1e-9, "width limits: 280 px for 100 blocks");
        assertTrue(TopViewMath.screenX(f, W, 0) >= 10 - 1e-9 && TopViewMath.screenX(f, W, 100) <= W - 10 + 1e-9);
        assertEquals(v, TopViewMath.fit(null, W, H, 10, v), "nothing to fit keeps the view");
    }

    @Test
    void gridStepFollowsTheZoom() {
        assertEquals(8, TopViewMath.gridStep(4, 24));
        assertEquals(16, TopViewMath.gridStep(1.5, 24));
        assertEquals(32, TopViewMath.gridStep(1, 24));
        assertEquals(256, TopViewMath.gridStep(0.125, 24));
    }

    @Test
    void scaleLabelReadsLikeTheDesign() {
        assertEquals("1 px = 0.5 blocks", TopViewMath.scaleLabel(2));
        assertEquals("1 px = 4 blocks", TopViewMath.scaleLabel(0.25));
        assertEquals("1 px = 0.33 blocks", TopViewMath.scaleLabel(3));
    }

    private Item item(UUID id, int x, int z, int w, int d, int top, boolean copy, Kind k) {
        return new Item(id, new Box(new Vec3i(x, 0, z), new Vec3i(x + w, top, z + d)), copy, k);
    }

    @Test
    void theHigherFootprintWinsAndTheOutsideHitsNothing() {
        UUID low = UUID.randomUUID(), high = UUID.randomUUID();
        List<Item> items = List.of(item(low, 90, 40, 20, 20, 5, false, Kind.OBJECT), item(high, 100, 50, 20, 20, 30, false, Kind.OBJECT));
        double px = TopViewMath.screenX(v, W, 105), py = TopViewMath.screenY(v, H, 55);
        assertEquals(high, TopViewMath.hit(items, v, W, H, px, py), "both cover it: the higher one is on top");
        double lx = TopViewMath.screenX(v, W, 92), ly = TopViewMath.screenY(v, H, 42);
        assertEquals(low, TopViewMath.hit(items, v, W, H, lx, ly));
        assertNull(TopViewMath.hit(items, v, W, H, 5, 5));
    }

    @Test
    void aTieOnTheTopGoesToTheLaterItem() {
        UUID a = UUID.randomUUID(), b = UUID.randomUUID();
        List<Item> items = List.of(item(a, 100, 50, 10, 10, 8, false, Kind.OBJECT), item(b, 100, 50, 10, 10, 8, false, Kind.OBJECT));
        assertEquals(b, TopViewMath.hit(items, v, W, H, TopViewMath.screenX(v, W, 103), TopViewMath.screenY(v, H, 53)));
    }

    @Test
    void dragBecomesWholeBlocks() {
        assertEquals(new Vec3i(5, 0, -3), TopViewMath.blockDelta(v, 10, -6));
        assertEquals(new Vec3i(1, 0, 0), TopViewMath.blockDelta(v, 1.2, 0.4), "1.2 px of 2 px per block is 0.6 of a block: rounds to 1");
        assertEquals(new Vec3i(0, 0, 0), TopViewMath.blockDelta(v, 0.9, -0.9));
        assertEquals(new Vec3i(-2, 0, 0), TopViewMath.blockDelta(new View(0, 0, 8), -16, 0));
    }

    @Test
    void hatchLinesStayInsideTheRectangle() {
        List<double[]> hs = TopViewMath.hatch(10, 20, 50, 40, 4);
        assertFalse(hs.isEmpty());
        for (double[] s : hs) {
            for (int i = 0; i < 4; i += 2) {
                assertTrue(s[i] >= 10 - 1e-9 && s[i] <= 50 + 1e-9, "x in range");
                assertTrue(s[i + 1] >= 20 - 1e-9 && s[i + 1] <= 40 + 1e-9, "y in range");
            }
            assertEquals(s[2] - s[0], s[1] - s[3], 1e-9, "45 degrees, rising to the right");
        }
        assertTrue(TopViewMath.hatch(0, 0, 0, 10, 4).isEmpty());
    }

    @Test
    void dashesAlternateAndCoverTheLineUpToItsEnd() {
        List<double[]> d = TopViewMath.dashes(0, 0, 14, 0, 4, 3);
        assertEquals(2, d.size(), "0-4 and 7-11, then 14 is the end");
        assertEquals(7, d.get(1)[0], 1e-9);
        assertEquals(11, d.get(1)[2], 1e-9);
        assertEquals(3, TopViewMath.dashes(0, 0, 15, 0, 4, 3).size());
        assertEquals(15, TopViewMath.dashes(0, 0, 15, 0, 4, 3).get(2)[0] + 1, "the last dash starts at 14 and is clipped to 15");
    }

    @Test
    void theWedgePointsWhereTheCameraLooks() {
        double[] w = TopViewMath.wedge(100, 100, 0, 1, Math.toRadians(35), 50);   // looking toward +Z (down on the map)
        assertTrue(w[1] > 100 && w[3] > 100, "both far corners lie below the camera");
        assertEquals(100, (w[0] + w[2]) / 2, 1e-9, "centred on the view direction");
    }

    @Test
    void itemsComeFromTheScene() {
        Scene scene = new Scene();
        ProjectNode p = new ProjectNode("p");
        scene.openProject(p);
        ObjectNode a = new ObjectNode("a", "a");
        a.setTransform(Transform.IDENTITY.withPos(new Vec3i(0, 0, 0)));
        ArrayModifier am = new ArrayModifier();
        am.count = 3;
        a.mods().add(am);
        ObjectNode cut = new ObjectNode("c", "c");
        cut.setWire(true);
        cut.setTransform(Transform.IDENTITY.withPos(new Vec3i(40, 0, 0)));
        ExternalNode area = new ExternalNode("area", "none:missing");
        area.setTransform(Transform.IDENTITY.withPos(new Vec3i(80, 0, 0)));
        p.addChild(a);
        p.addChild(cut);
        p.addChild(area);
        WorldBoxes wb = new WorldBoxes(scene, o -> new Vec3i(4, 4, 4));
        List<Item> items = TopViewMath.items(scene, wb);
        assertEquals(3 + 1 + 1, items.size());
        assertEquals(2, items.stream().filter(Item::copy).count(), "two array copies");
        assertEquals(1, items.stream().filter(i -> i.kind() == Kind.CUTTER).count());
        assertEquals(1, items.stream().filter(i -> i.kind() == Kind.AREA).count());
        for (int i = 1; i < items.size(); i++) assertTrue(items.get(i - 1).top() <= items.get(i).top(), "lowest top first");
    }
}
