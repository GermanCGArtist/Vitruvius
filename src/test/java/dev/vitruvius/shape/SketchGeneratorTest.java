package dev.vitruvius.shape;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SketchGeneratorTest {
    private static final BlockSlot STONE = BlockSlot.single("minecraft:stone");
    private static final BlockSlot DIRT = BlockSlot.single("minecraft:dirt");

    /** A filled cols x rows rectangle of shape pixels whose top-left is at (-2, 5): the canvas origin does not matter. */
    private static Sketch rect(int cols, int rows, int depth) {
        Map<Long, String> m = new HashMap<>();
        for (int r = 0; r < rows; r++) for (int c = 0; c < cols; c++) m.put(Sketch.key(-2 + c, 5 + r), "");
        return Sketch.fromMap(m, depth);
    }

    private static GeneratorSettings settings(Sketch sk, Axis axis, int thickness, BlockSlot fill) {
        ShapeSpec spec = new ShapeSpec(ShapeType.CUSTOM, 1, 1, 1, axis, thickness, 3, 1, false);
        return new GeneratorSettings(spec, STONE, fill, "t", sk).withSketchSize();
    }

    @Test
    void sizeFollowsTheAxis() {
        Sketch sk = rect(5, 3, 4);
        assertEquals(5, settings(sk, Axis.Z, 1, DIRT).spec().sizeX());
        GeneratorSettings y = settings(sk, Axis.Y, 1, DIRT);
        assertEquals(5, y.spec().sizeX());
        assertEquals(4, y.spec().sizeY());
        assertEquals(3, y.spec().sizeZ());
        GeneratorSettings x = settings(sk, Axis.X, 1, DIRT);
        assertEquals(4, x.spec().sizeX());
        assertEquals(3, x.spec().sizeY());
        assertEquals(5, x.spec().sizeZ());
    }

    @Test
    void edgeAndCapsAreOutlineAndTheInsideIsFill() {
        VoxelResult r = ShapeGenerator.generate(settings(rect(5, 3, 4), Axis.Z, 1, DIRT), () -> false);
        assertEquals(60, r.blockCount());
        long dirt = 0;
        for (short c : r.cells()) if (c != 0 && r.palette().get(c - 1).equals("minecraft:dirt")) dirt++;
        assertEquals(3 * 2, dirt);            // 3 interior pixels x the 2 layers between the caps
        assertEquals(r.palette().indexOf("minecraft:stone") + 1, r.get(2, 1, 0));    // a cap is outline
    }

    @Test
    void hollowShapeKeepsEdgeAndCapsButNoInside() {
        VoxelResult r = ShapeGenerator.generate(settings(rect(5, 3, 4), Axis.Z, 1, BlockSlot.air()), () -> false);
        assertEquals(60 - 6, r.blockCount());
        assertEquals(0, r.get(2, 1, 1));
    }

    @Test
    void rowsGrowDownwardSoYIsFlipped() {
        Map<Long, String> m = new HashMap<>();
        m.put(Sketch.key(0, 0), "minecraft:gold_block");     // top-left on screen
        m.put(Sketch.key(0, 1), "");
        VoxelResult r = ShapeGenerator.generate(settings(Sketch.fromMap(m, 1), Axis.Z, 1, DIRT), () -> false);
        assertEquals(1, r.sizeX());
        assertEquals(2, r.sizeY());
        int gold = r.palette().indexOf("minecraft:gold_block") + 1;
        assertTrue(gold > 0);
        assertEquals(gold, r.get(0, 1, 0));                 // the top pixel is the highest block
        assertEquals(r.palette().indexOf("minecraft:stone") + 1, r.get(0, 0, 0));
    }

    @Test
    void pinnedBlocksOverridePatternsInTheWholeColumn() {
        Map<Long, String> m = new HashMap<>();
        for (int c = 0; c < 3; c++) m.put(Sketch.key(c, 0), c == 1 ? "minecraft:glass" : "");
        VoxelResult r = ShapeGenerator.generate(settings(Sketch.fromMap(m, 3), Axis.Y, 1, DIRT), () -> false);
        int glass = r.palette().indexOf("minecraft:glass") + 1;
        for (int y = 0; y < 3; y++) assertEquals(glass, r.get(1, y, 0));
    }

    @Test
    void emptyDrawingMakesNothing() {
        VoxelResult r = ShapeGenerator.generate(settings(Sketch.EMPTY, Axis.Z, 1, DIRT), () -> false);
        assertEquals(0, r.blockCount());
    }

    @Test
    void mapRoundTrip() {
        Sketch sk = rect(4, 4, 2);
        assertEquals(sk.toMap(), Sketch.fromMap(sk.toMap(), 2).toMap());
        assertEquals(16, sk.pixelCount());
    }
}
