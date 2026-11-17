package dev.vitruvius.editor.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

class CycleCheckTest {
    private static Function<String, List<String>> graph(Map<String, List<String>> m) {
        return p -> m.getOrDefault(p, List.of());
    }

    @Test
    void linkingToItselfIsACycle() {
        assertTrue(CycleCheck.wouldCreateCycle("A", "A", graph(Map.of())));
    }

    @Test
    void directAndIndirectLoops() {
        var g = graph(Map.of("B", List.of("A")));
        assertTrue(CycleCheck.wouldCreateCycle("A", "B", g));   // A gets B, B already has A
        var g2 = graph(Map.of("B", List.of("C"), "C", List.of("D"), "D", List.of("A")));
        assertTrue(CycleCheck.wouldCreateCycle("A", "B", g2));
        assertEquals(Optional.of(List.of("A", "B", "C", "D", "A")), CycleCheck.findCycle("A", "B", g2));
    }

    @Test
    void noLoopWhenTargetNotReachable() {
        var g = graph(Map.of("B", List.of("C", "D"), "C", List.of("E"), "D", List.of("E")));   // a diamond
        assertFalse(CycleCheck.wouldCreateCycle("A", "B", g));
        assertTrue(CycleCheck.findCycle("A", "B", g).isEmpty());
    }

    @Test
    void existingLoopElsewhereDoesNotHang() {
        var g = graph(Map.of("B", List.of("C"), "C", List.of("B")));
        assertFalse(CycleCheck.wouldCreateCycle("A", "B", g));
        assertTrue(CycleCheck.wouldCreateCycle("C", "B", g));
    }

    @Test
    void unreadableFilesLinkNothing() {
        assertFalse(CycleCheck.wouldCreateCycle("A", "missing", p -> null));
    }
}
