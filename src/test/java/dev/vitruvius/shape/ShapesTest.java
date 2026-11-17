package dev.vitruvius.shape;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ShapesTest {
    private static ShapeSpec spec(ShapeType type, int sx, int sy, int sz, Axis axis) {
        return new ShapeSpec(type, sx, sy, sz, axis, 1, 3, 1, false).clamped();
    }

    @Test
    void everyShapeAtSizeOneIsABlock() {
        for (ShapeType type : ShapeType.values()) {
            for (Axis axis : Axis.values()) {
                ShapeSpec s = new ShapeSpec(type, 1, 1, 1, axis, 1, 1, 1, false).clamped();
                assertTrue(Shapes.inside(s, 0, 0, 0), type + " " + axis);
                ShapeSpec sharp = new ShapeSpec(type, 1, 1, 1, axis, 1, 0, 1, false).clamped();
                assertTrue(Shapes.inside(sharp, 0, 0, 0), type + " " + axis + " param 0");
            }
        }
    }

    @Test
    void symmetricShapesMirrorForOddAndEvenSizes() {
        int[][] sizes = {{9, 7, 11}, {8, 6, 10}, {8, 9, 10}};
        for (ShapeType type : ShapeType.values()) {
            if (type == ShapeType.GRID) continue;
            for (Axis axis : Axis.values()) {
                for (int[] sz : sizes) {
                    ShapeSpec s = spec(type, sz[0], sz[1], sz[2], axis);
                    boolean mirrorX = true, mirrorY = true, mirrorZ = true;
                    if (type.usesAxis()) {
                        mirrorX = axis != Axis.X;
                        mirrorY = axis != Axis.Y;
                        mirrorZ = axis != Axis.Z;
                    }
                    if (type == ShapeType.ARCH) {
                        mirrorY = false;
                        mirrorX = axis != Axis.X;
                        mirrorZ = axis == Axis.X;
                    }
                    for (int y = 0; y < s.sizeY(); y++) {
                        for (int z = 0; z < s.sizeZ(); z++) {
                            for (int x = 0; x < s.sizeX(); x++) {
                                boolean v = Shapes.inside(s, x, y, z);
                                String msg = type + " " + axis + " " + s.sizeX() + "x" + s.sizeY() + "x" + s.sizeZ();
                                if (mirrorX) assertEquals(v, Shapes.inside(s, s.sizeX() - 1 - x, y, z), msg + " x");
                                if (mirrorY) assertEquals(v, Shapes.inside(s, x, s.sizeY() - 1 - y, z), msg + " y");
                                if (mirrorZ) assertEquals(v, Shapes.inside(s, x, y, s.sizeZ() - 1 - z), msg + " z");
                            }
                        }
                    }
                }
            }
        }
    }

    @Test
    void cylinderAlongXHasItsCircleInYz() {
        ShapeSpec s = spec(ShapeType.CYLINDER, 5, 9, 9, Axis.X);
        assertTrue(Shapes.inside(s, 0, 4, 4));
        assertTrue(Shapes.inside(s, 4, 4, 4));
        assertFalse(Shapes.inside(s, 2, 0, 0));
        assertTrue(Shapes.inside(s, 2, 0, 4));
    }

    @Test
    void domeBaseIsAtTheLowEndOfTheAxis() {
        ShapeSpec s = spec(ShapeType.DOME, 9, 5, 9, Axis.Y);
        assertTrue(Shapes.inside(s, 4, 0, 4));
        assertTrue(Shapes.inside(s, 1, 0, 4));
        assertFalse(Shapes.inside(s, 0, 4, 0));
        assertFalse(Shapes.inside(s, 1, 4, 4));
    }

    @Test
    void archStandsOnTheBottomWithAHole() {
        ShapeSpec s = new ShapeSpec(ShapeType.ARCH, 9, 5, 3, Axis.Z, 1, 1, 1, false).clamped();
        assertTrue(Shapes.inside(s, 0, 0, 1));
        assertTrue(Shapes.inside(s, 4, 4, 1));
        assertFalse(Shapes.inside(s, 4, 0, 1));
        assertFalse(Shapes.inside(s, 4, 1, 1));
    }

    @Test
    void torusAndTubeHaveHoles() {
        ShapeSpec torus = new ShapeSpec(ShapeType.TORUS, 11, 3, 11, Axis.Y, 1, 3, 1, false).clamped();
        assertFalse(Shapes.inside(torus, 5, 1, 5));
        assertTrue(Shapes.inside(torus, 1, 1, 5));
        ShapeSpec tube = new ShapeSpec(ShapeType.TUBE, 9, 4, 9, Axis.Y, 1, 2, 1, false).clamped();
        assertFalse(Shapes.inside(tube, 4, 2, 4));
        assertTrue(Shapes.inside(tube, 0, 2, 4));
        ShapeSpec thick = new ShapeSpec(ShapeType.TUBE, 5, 4, 5, Axis.Y, 1, 9, 1, false).clamped();
        assertTrue(Shapes.inside(thick, 2, 2, 2));
    }

    @Test
    void gridLinesStartAtZeroAndRepeat() {
        ShapeSpec s = new ShapeSpec(ShapeType.GRID, 9, 1, 9, Axis.Y, 1, 4, 1, false).clamped();
        assertTrue(Shapes.inside(s, 0, 0, 3));
        assertTrue(Shapes.inside(s, 3, 0, 4));
        assertTrue(Shapes.inside(s, 8, 0, 3));
        assertFalse(Shapes.inside(s, 1, 0, 1));
        ShapeSpec lattice = new ShapeSpec(ShapeType.GRID, 9, 9, 9, Axis.Y, 1, 4, 1, false).clamped();
        assertTrue(Shapes.inside(lattice, 0, 0, 0));
        assertTrue(Shapes.inside(lattice, 0, 1, 4));
        assertFalse(Shapes.inside(lattice, 1, 1, 0));
        assertFalse(Shapes.inside(lattice, 1, 1, 1));
    }

    @Test
    void frameKeepsOnlyTheEdges() {
        ShapeSpec s = spec(ShapeType.FRAME, 5, 5, 5, Axis.Y);
        assertTrue(Shapes.inside(s, 0, 0, 2));
        assertTrue(Shapes.inside(s, 4, 2, 4));
        assertFalse(Shapes.inside(s, 0, 2, 2));
        assertFalse(Shapes.inside(s, 2, 2, 2));
    }

    @Test
    void planeClampsToOneAlongItsAxis() {
        assertEquals(1, new ShapeSpec(ShapeType.PLANE, 5, 7, 5, Axis.Y, 1, 1, 1, false).clamped().sizeY());
        assertEquals(1, new ShapeSpec(ShapeType.PLANE, 5, 7, 5, Axis.X, 1, 1, 1, false).clamped().sizeX());
    }

    @Test
    void coneAndPyramidNarrowUpwards() {
        for (ShapeType type : new ShapeType[] {ShapeType.CONE, ShapeType.PYRAMID}) {
            ShapeSpec s = new ShapeSpec(type, 9, 9, 9, Axis.Y, 1, 0, 1, false).clamped();
            assertTrue(Shapes.inside(s, 1, 0, 4), type + " base");
            assertFalse(Shapes.inside(s, 1, 8, 4), type + " top");
            assertTrue(Shapes.inside(s, 4, 8, 4), type + " apex");
        }
    }
}
