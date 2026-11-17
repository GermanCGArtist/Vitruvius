package dev.schemarray.editor.core;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * The front order: a global integer per world, 1 = front. A node with 0 (not numbered yet) sorts behind every
 * numbered one; ties break by node id, so the order is always the same for the same numbers.
 *
 * <p>The operations take the whole list of nodes of one world (see {@code Scene.frontOwnersInWorld}) and the nodes
 * to move, work out the new order, and renumber everything 1..n. Several selected nodes move as a group and keep
 * their order relative to each other.
 */
public final class FrontOrder {
    private FrontOrder() {}

    /** Negative when a is in front of b. */
    public static int compare(int frontA, UUID idA, int frontB, UUID idB) {
        int c = Integer.compare(effective(frontA), effective(frontB));
        return c != 0 ? c : idA.compareTo(idB);
    }

    private static int effective(int front) {
        return front <= 0 ? Integer.MAX_VALUE : front;
    }

    public static final Comparator<HasFront> ORDER = (a, b) -> compare(a.front(), a.id(), b.front(), b.id());

    /** The nodes from front to back (a new list). */
    public static List<HasFront> sorted(Collection<? extends HasFront> nodes) {
        List<HasFront> out = new ArrayList<>(nodes);
        out.sort(ORDER);
        return out;
    }

    /** Numbers the nodes 1..n in their current order (fixes gaps, duplicates and unnumbered nodes). */
    public static void renumber(Collection<? extends HasFront> nodes) {
        apply(sorted(nodes));
    }

    /** Moves the selected nodes in front of everything else. */
    public static void front(Collection<? extends HasFront> all, Collection<? extends HasFront> selected) {
        List<HasFront> order = sorted(all);
        Set<HasFront> sel = identitySet(selected);
        List<HasFront> out = new ArrayList<>();
        for (HasFront n : order) if (sel.contains(n)) out.add(n);
        for (HasFront n : order) if (!sel.contains(n)) out.add(n);
        apply(out);
    }

    /** Moves the selected nodes behind everything else. */
    public static void back(Collection<? extends HasFront> all, Collection<? extends HasFront> selected) {
        List<HasFront> order = sorted(all);
        Set<HasFront> sel = identitySet(selected);
        List<HasFront> out = new ArrayList<>();
        for (HasFront n : order) if (!sel.contains(n)) out.add(n);
        for (HasFront n : order) if (sel.contains(n)) out.add(n);
        apply(out);
    }

    /** Moves each selected node one place toward the front (a selected node already at the front, or right behind another selected one that cannot move, stays). */
    public static void forward(Collection<? extends HasFront> all, Collection<? extends HasFront> selected) {
        List<HasFront> order = sorted(all);
        Set<HasFront> sel = identitySet(selected);
        for (int i = 1; i < order.size(); i++) {
            if (sel.contains(order.get(i)) && !sel.contains(order.get(i - 1))) {
                HasFront t = order.get(i);
                order.set(i, order.get(i - 1));
                order.set(i - 1, t);
            }
        }
        apply(order);
    }

    /** Moves each selected node one place toward the back. */
    public static void backward(Collection<? extends HasFront> all, Collection<? extends HasFront> selected) {
        List<HasFront> order = sorted(all);
        Set<HasFront> sel = identitySet(selected);
        for (int i = order.size() - 2; i >= 0; i--) {
            if (sel.contains(order.get(i)) && !sel.contains(order.get(i + 1))) {
                HasFront t = order.get(i);
                order.set(i, order.get(i + 1));
                order.set(i + 1, t);
            }
        }
        apply(order);
    }

    /** The number a new node should get so that it sits behind all numbered ones. */
    public static int nextBack(Collection<? extends HasFront> all) {
        int max = 0;
        for (HasFront n : all) max = Math.max(max, n.front());
        return max + 1;
    }

    private static void apply(List<HasFront> order) {
        for (int i = 0; i < order.size(); i++) order.get(i).setFront(i + 1);
    }

    private static Set<HasFront> identitySet(Collection<? extends HasFront> c) {
        Set<HasFront> s = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
        s.addAll(c);
        return s;
    }
}
