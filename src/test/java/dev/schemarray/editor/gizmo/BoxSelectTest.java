package dev.schemarray.editor.gizmo;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.schemarray.editor.gizmo.BoxSelect.Item;
import dev.schemarray.editor.gizmo.BoxSelect.Mode;
import dev.schemarray.editor.gizmo.BoxSelect.Rect;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

class BoxSelectTest {
    private final UUID a = UUID.randomUUID(), b = UUID.randomUUID(), c = UUID.randomUUID(), d = UUID.randomUUID();
    private final List<Item> items = List.of(new Item(a, 10, 10), new Item(b, 50, 50), new Item(c, 100, 100), new Item(d, Double.NaN, 0));

    @Test
    void hitsTheCentresInsideTheRectangleInAnyDragDirection() {
        assertEquals(List.of(a, b), BoxSelect.hits(new Rect(0, 0, 60, 60), items));
        assertEquals(List.of(a, b), BoxSelect.hits(new Rect(60, 60, 0, 0), items));
        assertEquals(List.of(), BoxSelect.hits(new Rect(200, 200, 300, 300), items));
    }

    @Test
    void theEdgeCountsAndBehindTheCameraNever() {
        assertEquals(List.of(b), BoxSelect.hits(new Rect(50, 50, 60, 60), items));
        assertEquals(List.of(), BoxSelect.hits(new Rect(-10, -10, 1, 1), items));
    }

    @Test
    void combineReplacesAddsAndSubtracts() {
        assertEquals(List.of(b), BoxSelect.combine(Mode.REPLACE, List.of(a), List.of(b)));
        assertEquals(List.of(a, b), BoxSelect.combine(Mode.ADD, List.of(a), List.of(b, a)));
        assertEquals(List.of(a), BoxSelect.combine(Mode.SUBTRACT, List.of(a, b), List.of(b)));
    }
}
