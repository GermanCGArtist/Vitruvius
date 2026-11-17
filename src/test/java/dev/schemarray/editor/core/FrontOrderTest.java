package dev.schemarray.editor.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class FrontOrderTest {
    private static ObjectNode obj(String name, int front) {
        ObjectNode o = new ObjectNode(new UUID(0, name.charAt(0)), name);
        o.setFront(front);
        return o;
    }

    private static String names(List<? extends HasFront> l) {
        StringBuilder sb = new StringBuilder();
        for (HasFront h : FrontOrder.sorted(l)) sb.append(((Node) h).name());
        return sb.toString();
    }

    private static List<ObjectNode> abcde() {
        return new ArrayList<>(List.of(obj("a", 1), obj("b", 2), obj("c", 3), obj("d", 4), obj("e", 5)));
    }

    @Test
    void sortsByNumberThenId() {
        List<ObjectNode> l = new ArrayList<>(List.of(obj("c", 2), obj("b", 2), obj("a", 3), obj("d", 1)));
        assertEquals("dbca", names(l));   // the tie between b and c breaks by id
    }

    @Test
    void unnumberedIsBehindEverything() {
        List<ObjectNode> l = List.of(obj("a", 0), obj("b", 9), obj("c", 0));
        assertEquals("bac", names(l));
    }

    @Test
    void renumberFixesGapsAndDuplicates() {
        List<ObjectNode> l = List.of(obj("a", 7), obj("b", 7), obj("c", 0), obj("d", 2));
        FrontOrder.renumber(l);
        assertEquals("dabc", names(l));
        assertEquals(List.of(2, 3, 4, 1), l.stream().map(ObjectNode::front).toList());
    }

    @Test
    void frontAndBack() {
        List<ObjectNode> l = abcde();
        FrontOrder.front(l, List.of(l.get(3)));
        assertEquals("dabce", names(l));
        FrontOrder.back(l, List.of(l.get(0)));   // a
        assertEquals("dbcea", names(l));
        assertEquals(1, l.get(3).front());
        assertEquals(5, l.get(0).front());
    }

    @Test
    void forwardAndBackwardStepOnePlace() {
        List<ObjectNode> l = abcde();
        FrontOrder.forward(l, List.of(l.get(2)));
        assertEquals("acbde", names(l));
        FrontOrder.backward(l, List.of(l.get(2)));
        assertEquals("abcde", names(l));
        FrontOrder.forward(l, List.of(l.get(0)));   // already in front: no change
        assertEquals("abcde", names(l));
        FrontOrder.backward(l, List.of(l.get(4)));   // already at the back
        assertEquals("abcde", names(l));
    }

    @Test
    void groupMovesKeepRelativeOrder() {
        List<ObjectNode> l = abcde();
        FrontOrder.forward(l, List.of(l.get(1), l.get(2)));   // b, c
        assertEquals("bcade", names(l));
        FrontOrder.front(l, List.of(l.get(4), l.get(3)));   // e, d given in a different order
        assertEquals("debca", names(l));
        FrontOrder.backward(l, List.of(l.get(3), l.get(4)));   // d, e
        assertEquals("bdeca", names(l));
    }

    @Test
    void backwardGroupBlockedByTheEnd() {
        List<ObjectNode> l = abcde();
        FrontOrder.backward(l, List.of(l.get(3), l.get(4)));   // d,e: e is last, so d is stuck behind it
        assertEquals("abcde", names(l));
        FrontOrder.backward(l, List.of(l.get(2), l.get(3)));   // c,d: d swaps with e, c follows
        assertEquals("abecd", names(l));
    }

    @Test
    void resultIsAlwaysConsecutiveFromOne() {
        List<ObjectNode> l = List.of(obj("a", 40), obj("b", 0), obj("c", 5));
        FrontOrder.front(l, List.of(l.get(1)));
        List<Integer> nums = new ArrayList<>(l.stream().map(ObjectNode::front).toList());
        nums.sort(null);
        assertEquals(List.of(1, 2, 3), nums);
        assertEquals(1, l.get(1).front());
        assertEquals(4, FrontOrder.nextBack(l));
    }

    @Test
    void sameAnswerForTheSameNumbers() {
        List<ObjectNode> l1 = List.of(obj("a", 1), obj("b", 1), obj("c", 1));
        List<ObjectNode> l2 = List.of(obj("c", 1), obj("a", 1), obj("b", 1));
        assertEquals(names(l1), names(l2));
    }
}
