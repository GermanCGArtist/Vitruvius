package dev.vitruvius.editor.gizmo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class AxisProjectionTest {
    @Test
    void axisParamFindsTheClosestPointOnTheAxis() {
        double t = AxisProjection.axisParam(new double[] {3, 5, 0}, new double[] {0, -1, 0}, new double[] {0, 0, 0}, 0);
        assertEquals(3.0, t, 1e-9);
        double len = Math.sqrt(16 + 100 + 100);   // a slanted ray from (0,10,-10) toward (4,0,0)
        t = AxisProjection.axisParam(new double[] {0, 10, -10}, new double[] {4 / len, -10 / len, 10 / len}, new double[] {1, 0, 0}, 0);
        assertTrue(Math.abs(t - 3) < 0.5, "near x = 4: " + t);
    }

    @Test
    void axisParamOfARayAlongTheAxisIsNaN() {
        assertTrue(Double.isNaN(AxisProjection.axisParam(new double[] {0, 0, -5}, new double[] {0, 0, 1}, new double[] {0, 0, 0}, 2)));
    }

    @Test
    void planeHitReturnsTheOtherTwoCoordinates() {
        double s = Math.sqrt(0.5);
        double[] h = AxisProjection.planeHit(new double[] {0, 10, 2}, new double[] {s, -s, 0}, new double[] {0, 0, 0}, 1);
        assertEquals(10.0, h[0], 1e-9);
        assertEquals(2.0, h[1], 1e-9);
    }

    @Test
    void planeHitMissesWhenParallelOrBehind() {
        assertNull(AxisProjection.planeHit(new double[] {0, 10, 0}, new double[] {1, 0, 0}, new double[] {0, 0, 0}, 1));
        assertNull(AxisProjection.planeHit(new double[] {0, 10, 0}, new double[] {0, 1, 0}, new double[] {0, 0, 0}, 1));
    }

    @Test
    void blocksRoundToWholeBlocks() {
        assertEquals(3, AxisProjection.blocks(3.4));
        assertEquals(4, AxisProjection.blocks(3.5));
        assertEquals(-3, AxisProjection.blocks(-3.4));
        assertEquals(0, AxisProjection.blocks(-0.4));
    }
}
