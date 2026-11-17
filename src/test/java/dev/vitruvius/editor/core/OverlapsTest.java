package dev.vitruvius.editor.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class OverlapsTest {
    private static Box box(int x0, int y0, int z0, int x1, int y1, int z1) {
        return new Box(new Vec3i(x0, y0, z0), new Vec3i(x1, y1, z1));
    }

    private static UUID id(int n) {
        return new UUID(0, n);
    }

    @Test
    void theOneBehindLoses() {
        var a = new Overlaps.Item(id(1), 2, true, List.of(box(0, 0, 0, 10, 10, 10)));
        var b = new Overlaps.Item(id(2), 1, true, List.of(box(5, 0, 5, 15, 10, 15)));
        var r = Overlaps.find(List.of(a, b));
        assertEquals(1, r.size());
        assertEquals(box(5, 0, 5, 10, 10, 10), r.get(0).box());
        assertEquals(id(1), r.get(0).loser());
        assertEquals(id(2), r.get(0).winner());
    }

    @Test
    void tiesBreakByIdSoTheResultIsStable() {
        var a = new Overlaps.Item(id(1), 3, true, List.of(box(0, 0, 0, 4, 4, 4)));
        var b = new Overlaps.Item(id(2), 3, true, List.of(box(2, 2, 2, 6, 6, 6)));
        assertEquals(id(2), Overlaps.find(List.of(a, b)).get(0).loser());
        assertEquals(id(2), Overlaps.find(List.of(b, a)).get(0).loser());
    }

    @Test
    void touchingFacesAndSameOwnerDoNotOverlap() {
        var a = new Overlaps.Item(id(1), 1, true, List.of(box(0, 0, 0, 4, 4, 4), box(2, 2, 2, 8, 8, 8)));   // own cells overlap: fine
        var b = new Overlaps.Item(id(2), 2, true, List.of(box(8, 0, 0, 12, 4, 4)));   // touches a's second box at x = 8
        assertTrue(Overlaps.find(List.of(a, b)).isEmpty());
    }

    @Test
    void everyBoxPairIsReported() {
        var a = new Overlaps.Item(id(1), 1, true, List.of(box(0, 0, 0, 4, 1, 1), box(10, 0, 0, 14, 1, 1)));
        var b = new Overlaps.Item(id(2), 2, true, List.of(box(2, 0, 0, 12, 1, 1)));
        var r = Overlaps.find(List.of(a, b));
        assertEquals(2, r.size());
        assertEquals(box(2, 0, 0, 4, 1, 1), r.get(0).box());
        assertEquals(box(10, 0, 0, 12, 1, 1), r.get(1).box());
    }

    @Test
    void itemsThatDoNotCountAreIgnored() {
        var a = new Overlaps.Item(id(1), 1, true, List.of(box(0, 0, 0, 4, 4, 4)));
        var b = new Overlaps.Item(id(2), 2, false, List.of(box(0, 0, 0, 4, 4, 4)));
        assertTrue(Overlaps.find(List.of(a, b)).isEmpty());
    }

    @Test
    void ofNodeSaysWhoCounts() {
        ObjectNode plain = new ObjectNode("p", "p.litematic");
        ObjectNode wire = new ObjectNode("w", "w.litematic");
        wire.setWire(true);
        ExternalNode ext = new ExternalNode("x", "heavymatica:area");
        AssemblyNode asm = new AssemblyNode("a");
        plain.setFront(4);
        List<Box> bx = List.of(box(0, 0, 0, 1, 1, 1));
        assertTrue(Overlaps.Item.of(plain, bx).counts());
        assertEquals(4, Overlaps.Item.of(plain, bx).front());
        assertTrue(!Overlaps.Item.of(wire, bx).counts());
        assertTrue(!Overlaps.Item.of(ext, bx).counts());
        assertTrue(Overlaps.Item.of(asm, bx).counts());
    }

    @Test
    void worksWithBoxTransformed() {
        Box local = Box.ofSize(new Vec3i(4, 4, 4));
        Box w1 = local.transformed(new Transform(new Vec3i(0, 0, 0), Rot.NONE, Mirror.NONE));
        Box w2 = local.transformed(new Transform(new Vec3i(2, 0, 0), Rot.CW_90, Mirror.NONE));
        var r = Overlaps.find(List.of(new Overlaps.Item(id(1), 1, true, List.of(w1)), new Overlaps.Item(id(2), 2, true, List.of(w2))));
        assertEquals(1, r.size());
    }
}
