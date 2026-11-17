package dev.schemarray.shape;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ShapeGeneratorTest {
    private static final BlockSlot STONE = BlockSlot.single("minecraft:stone");
    private static final BlockSlot DIRT = BlockSlot.single("minecraft:dirt");

    private static ShapeSpec box(int size, int thickness) {
        return new ShapeSpec(ShapeType.BOX, size, size, size, Axis.Y, thickness, 3, 1, false);
    }

    @Test
    void hollowBoxIsExactlyTheSurface() {
        VoxelResult r = ShapeGenerator.generate(box(9, 1), STONE, BlockSlot.air(), () -> false);
        assertEquals(9 * 9 * 9 - 7 * 7 * 7, r.blockCount());
        assertEquals(0, r.get(4, 4, 4));
        assertEquals(1, r.get(0, 4, 4));
        assertEquals(List.of("minecraft:stone"), r.palette());
        VoxelResult thick = ShapeGenerator.generate(box(9, 2), STONE, BlockSlot.air(), () -> false);
        assertEquals(9 * 9 * 9 - 5 * 5 * 5, thick.blockCount());
    }

    @Test
    void thicknessAtLeastSizeMakesEverythingOutline() {
        VoxelResult r = ShapeGenerator.generate(box(5, 3), STONE, DIRT, () -> false);
        assertEquals(125, r.blockCount());
        assertEquals(List.of("minecraft:stone"), r.palette());
        VoxelResult two = ShapeGenerator.generate(box(5, 2), STONE, DIRT, () -> false);
        assertEquals(List.of("minecraft:stone", "minecraft:dirt"), two.palette());
        assertEquals(2, two.get(2, 2, 2));
        assertEquals(1, two.get(1, 2, 2));
    }

    @Test
    void planeClampsToOneLayer() {
        ShapeSpec s = new ShapeSpec(ShapeType.PLANE, 5, 5, 5, Axis.Y, 1, 1, 1, false);
        VoxelResult r = ShapeGenerator.generate(s, STONE, DIRT, () -> false);
        assertEquals(1, r.sizeY());
        assertEquals(25, r.blockCount());
    }

    @Test
    void frameIsAllOutline() {
        ShapeSpec s = new ShapeSpec(ShapeType.FRAME, 5, 5, 5, Axis.Y, 1, 1, 1, false);
        VoxelResult r = ShapeGenerator.generate(s, STONE, DIRT, () -> false);
        assertEquals(12 * 3 + 8, r.blockCount());
        assertEquals(List.of("minecraft:stone"), r.palette());
    }

    @Test
    void sphereShellIsHollow() {
        ShapeSpec s = new ShapeSpec(ShapeType.ELLIPSOID, 15, 15, 15, Axis.Y, 1, 1, 1, false);
        VoxelResult solid = ShapeGenerator.generate(s, STONE, STONE, () -> false);
        VoxelResult shell = ShapeGenerator.generate(s, STONE, BlockSlot.air(), () -> false);
        assertTrue(shell.blockCount() < solid.blockCount());
        assertEquals(0, shell.get(7, 7, 7));
        assertEquals(1, solid.get(7, 7, 7));
    }

    @Test
    void cancellationReturnsNull() {
        assertNull(ShapeGenerator.generate(box(20, 1), STONE, DIRT, () -> true));
        AtomicInteger calls = new AtomicInteger();
        assertNull(ShapeGenerator.generate(box(20, 1), STONE, DIRT, () -> calls.incrementAndGet() > 30));
        assertNotNull(ShapeGenerator.generate(box(20, 1), STONE, DIRT, () -> false));
    }

    @Test
    void paletteFollowsFirstUse() {
        BlockSlot checker = new BlockSlot(PatternMode.CHECKER, List.of(new BlockEntry("b", 1), new BlockEntry("a", 1)),
                1, 1, 1, Axis.Y, 1, 0, false);
        VoxelResult r = ShapeGenerator.generate(box(3, 1), checker, BlockSlot.air(), () -> false);
        assertEquals(List.of("b", "a"), r.palette());
    }

    @Test
    void largestBoxGeneratesQuickly() {
        ShapeGenerator.generate(box(64, 1), STONE, BlockSlot.air(), () -> false);
        long start = System.nanoTime();
        VoxelResult r = ShapeGenerator.generate(box(256, 1), STONE, BlockSlot.air(), () -> false);
        long ms = (System.nanoTime() - start) / 1_000_000;
        System.out.println("256^3 box generated in " + ms + " ms");
        assertEquals(256 * 256 * 256 - 254 * 254 * 254, r.blockCount());
        assertTrue(ms < 5000, "took " + ms + " ms");
    }
}
