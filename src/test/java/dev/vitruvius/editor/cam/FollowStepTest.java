package dev.vitruvius.editor.cam;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class FollowStepTest {
    private static double len(double[] a, double x, double y, double z) {
        return Math.sqrt((a[0] - x) * (a[0] - x) + (a[1] - y) * (a[1] - y) + (a[2] - z) * (a[2] - z));
    }

    @Test
    void aShortMoveGoesAllTheWay() {
        assertArrayEquals(new double[] {3, 64, -2}, FollowStep.next(0, 64, 0, 3, 64, -2, FollowStep.MAX_STEP), 1e-9);
    }

    @Test
    void aLongMoveIsCappedAlongTheLine() {
        // the move from the server log: about 19.6 blocks at once
        double[] p = FollowStep.next(0, 0, 0, -3.59, -19.08, 2.73, FollowStep.MAX_STEP);
        assertEquals(FollowStep.MAX_STEP, len(p, 0, 0, 0), 1e-9);
        double total = Math.sqrt(3.59 * 3.59 + 19.08 * 19.08 + 2.73 * 2.73);
        assertEquals(total - FollowStep.MAX_STEP, len(p, -3.59, -19.08, 2.73), 1e-9);
    }

    @Test
    void everyStepStaysBelowTheServerLimit() {
        double x = 0, y = 70, z = 0;
        int ticks = 0;
        while (x != 1000 || y != 20 || z != -500) {
            double[] p = FollowStep.next(x, y, z, 1000, 20, -500, FollowStep.MAX_STEP);
            assertTrue(len(p, x, y, z) * len(p, x, y, z) < 100, "a vanilla server refuses 10 blocks or more per tick");
            x = p[0]; y = p[1]; z = p[2];
            assertTrue(++ticks < 200, "it gets there");
        }
    }

    @Test
    void standingStillStaysPut() {
        assertArrayEquals(new double[] {5, 6, 7}, FollowStep.next(5, 6, 7, 5, 6, 7, FollowStep.MAX_STEP), 0);
    }
}
