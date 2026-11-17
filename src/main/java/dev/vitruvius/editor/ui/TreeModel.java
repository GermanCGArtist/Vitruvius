package dev.vitruvius.editor.ui;

import dev.vitruvius.editor.core.AssemblyNode;
import dev.vitruvius.editor.core.ExternalNode;
import dev.vitruvius.editor.core.FolderNode;
import dev.vitruvius.editor.core.HasFront;
import dev.vitruvius.editor.core.LooseNode;
import dev.vitruvius.editor.core.Node;
import dev.vitruvius.editor.core.ObjectNode;
import dev.vitruvius.editor.core.ProjectNode;
import dev.vitruvius.editor.core.Scene;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * The flat list of rows the Scene window draws (DESIGN 7.1 to 7.3): projects first, Loose last, then folders,
 * objects, assemblies and externals, with the expansion state, the filter chip and the search text applied. Pure.
 */
public final class TreeModel {
    public enum Chip { ALL, SCHEMATICS, ASSEMBLIES, AREAS, ISSUES }

    /** What a row is, for its icon and colour. */
    public enum Kind { PROJECT, LOOSE, FOLDER, OBJECT, CUTTER, ASSEMBLY, LINKED_ASSEMBLY, AREA }

    /**
     * One row. {@code locked}: inside a linked assembly. {@code hidden}: hidden itself or through a parent.
     * {@code ancestorOnly}: shown only to reach a match. {@code matchStart}/{@code matchLen}: the search hit in
     * the name (-1 when none). {@code projectName}: set in the flat Issues view.
     */
    public record Row(Node node, int depth, Kind kind, boolean container, boolean hasChildren, boolean open,
                      boolean locked, boolean hidden, boolean ancestorOnly, int matchStart, int matchLen,
                      String projectName, boolean missing) {
        public UUID id() {
            return node.id();
        }
    }

    private final Set<UUID> open = new HashSet<>();
    private final Set<UUID> closed = new HashSet<>();
    private Chip chip = Chip.ALL;
    private String search = "";

    public Chip chip() {
        return chip;
    }

    public void setChip(Chip c) {
        chip = c;
    }

    public String search() {
        return search;
    }

    public void setSearch(String s) {
        search = s == null ? "" : s;
    }

    public boolean isFiltering() {
        return chip != Chip.ALL || !search.isBlank();
    }

    public boolean isOpen(Node n) {
        if (open.contains(n.id())) return true;
        if (closed.contains(n.id())) return false;
        if (n instanceof ProjectNode || n instanceof LooseNode) return true;
        if (n instanceof FolderNode f) return f.open();
        return false;
    }

    public void setOpen(Node n, boolean value) {
        if (value) {
            open.add(n.id());
            closed.remove(n.id());
        } else {
            closed.add(n.id());
            open.remove(n.id());
        }
    }

    /** Opens every ancestor of a node so its row is visible (selecting in the viewport). */
    public void reveal(Node n) {
        for (Node p = n.parent(); p != null; p = p.parent()) {
            if (!isOpen(p)) setOpen(p, true);
        }
    }

    public static Kind kindOf(Node n) {
        if (n instanceof ProjectNode) return Kind.PROJECT;
        if (n instanceof LooseNode) return Kind.LOOSE;
        if (n instanceof FolderNode) return Kind.FOLDER;
        if (n instanceof ObjectNode o) return o.wire() ? Kind.CUTTER : Kind.OBJECT;
        if (n instanceof AssemblyNode a) return a.isLinked() ? Kind.LINKED_ASSEMBLY : Kind.ASSEMBLY;
        return Kind.AREA;
    }

    /** True when the node or an ancestor above it is hidden. */
    public static boolean effectivelyHidden(Node n) {
        for (Node x = n; x != null; x = x.parent()) if (x.hidden()) return true;
        return false;
    }

    /** True for a node inside a linked assembly (the assembly itself is not locked). */
    public static boolean lockedPart(Node n) {
        for (Node x = n.parent(); x != null; x = x.parent()) if (x instanceof AssemblyNode a && a.isLinked()) return true;
        return false;
    }

    /**
     * The rows to draw.
     *
     * @param issues ids of objects involved in an overlap or with a failure (the Issues chip lists exactly these)
     */
    public List<Row> rows(Scene scene, Set<UUID> issues) {
        List<Row> out = new ArrayList<>();
        if (chip == Chip.ISSUES) {
            List<Node> flat = new ArrayList<>();
            for (Node n : scene.allNodes()) if (issues.contains(n.id()) && matchIndex(n) >= 0) flat.add(n);
            flat.sort(Comparator.comparingInt((Node n) -> n instanceof HasFront f && f.front() != 0 ? f.front() : Integer.MAX_VALUE)
                    .thenComparing(Node::id));
            for (Node n : flat) {
                ProjectNode p = scene.projectOf(n);
                out.add(row(n, 0, false, matchIndex(n), p == null ? "Loose" : p.name()));
            }
            return out;
        }
        for (Node root : scene.roots()) addRows(root, 0, out);
        return out;
    }

    private boolean addRows(Node n, int depth, List<Row> out) {
        if (!isFiltering()) {
            out.add(row(n, depth, false, -1, null));
            if (isOpen(n)) for (Node c : n.children()) addRows(c, depth + 1, out);
            return true;
        }
        // filtering: a node shows when it matches or has a matching descendant; containers expand to the matches
        boolean self = matchesChip(n) && matchIndex(n) >= 0 && !isStructural(n);
        int mark = out.size();
        out.add(null);
        boolean any = false;
        for (Node c : n.children()) any |= addRows(c, depth + 1, out);
        if (self || any) {
            out.set(mark, row(n, depth, !self, self ? matchIndex(n) : -1, null));
            return true;
        }
        out.remove(mark);
        return false;
    }

    private static boolean isStructural(Node n) {
        return n instanceof ProjectNode || n instanceof FolderNode || n instanceof LooseNode;
    }

    private Row row(Node n, int depth, boolean ancestorOnly, int matchStart, String projectName) {
        boolean has = !n.children().isEmpty();
        boolean isOpen = isFiltering() ? has : has && isOpen(n);
        boolean missing = n instanceof ObjectNode o && o.missing()
                || n instanceof ExternalNode x && dev.vitruvius.api.ObjectTypes.get(x.typeId()) == null;   // the mod is not installed
        boolean searching = !search.isBlank() && matchStart >= 0;
        return new Row(n, depth, kindOf(n), n.isContainer(), has, isOpen, lockedPart(n), effectivelyHidden(n), ancestorOnly,
                searching ? matchStart : -1, searching ? search.strip().length() : -1, projectName, missing);
    }

    private boolean matchesChip(Node n) {
        return switch (chip) {
            case ALL, ISSUES -> true;
            case SCHEMATICS -> n instanceof ObjectNode;
            case ASSEMBLIES -> n instanceof AssemblyNode;
            case AREAS -> n instanceof ExternalNode;
        };
    }

    /** Index of the search text in the node's name, 0 for no search, -1 for no match. */
    private int matchIndex(Node n) {
        String s = search.strip();
        if (s.isEmpty()) return 0;
        return n.name().toLowerCase(Locale.ROOT).indexOf(s.toLowerCase(Locale.ROOT));
    }

    /** The ids of the rows in order (for range selection). */
    public static List<UUID> order(List<Row> rows) {
        List<UUID> ids = new ArrayList<>();
        for (Row r : rows) ids.add(r.id());
        return ids;
    }
}
