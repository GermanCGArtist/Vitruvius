package dev.schemarray.editor.core;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Overlaps between owners (an object or a top-level assembly). Each owner brings its world boxes (one per array
 * cell, or per part). Where boxes of two different owners share blocks, the one behind in the front order loses
 * the shared part. ExternalNodes and wire objects never take part ({@code counts} is false for them).
 */
public final class Overlaps {
    private Overlaps() {}

    /** One owner: id, front number, whether it takes part, and its world boxes. */
    public record Item(UUID id, int front, boolean counts, List<Box> boxes) {
        public Item {
            boxes = List.copyOf(boxes);
        }

        /** An item for a node: it counts when it is a schematic object that is not wire, or an assembly. */
        public static Item of(Node owner, List<Box> boxes) {
            int front = owner instanceof HasFront f ? f.front() : 0;
            boolean counts = (owner instanceof ObjectNode o && !o.wire()) || owner instanceof AssemblyNode;
            return new Item(owner.id(), front, counts, boxes);
        }
    }

    /** The shared {@code box}; {@code loser} is trimmed there, {@code winner} keeps it. */
    public record Overlap(Box box, UUID loser, UUID winner) {}

    /** All overlaps, in the order of the items (pairs a then b), and within a pair in the order of the boxes. */
    public static List<Overlap> find(List<Item> items) {
        List<Item> in = new ArrayList<>();
        for (Item it : items) if (it.counts()) in.add(it);
        List<Overlap> out = new ArrayList<>();
        for (int i = 0; i < in.size(); i++) {
            for (int j = i + 1; j < in.size(); j++) {
                Item a = in.get(i), b = in.get(j);
                if (a.id().equals(b.id())) continue;
                boolean aWins = FrontOrder.compare(a.front(), a.id(), b.front(), b.id()) < 0;
                Item win = aWins ? a : b, lose = aWins ? b : a;
                for (Box ba : a.boxes()) {
                    for (Box bb : b.boxes()) {
                        ba.intersect(bb).ifPresent(x -> out.add(new Overlap(x, lose.id(), win.id())));
                    }
                }
            }
        }
        return out;
    }
}
