package dev.vitruvius.editor.sync.history;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Which placements on the server the history still needs, and what a cleanup would remove. A push used to remove the
 * placement of a cell it replaced; with history it only does so when no other commit lists it. Pure.
 */
public final class Retention {
    private Retention() {}

    /** Syncmatica ids that commits other than {@code except} use: their cell files and their manifest placements. */
    public static Set<String> referenced(CommitGraph g, String except) {
        Set<String> out = new HashSet<>();
        for (Commit c : g.all()) {
            if (c.id().equals(except)) continue;
            out.addAll(c.cells().values());
            if (c.placementId() != null) out.add(c.placementId());
        }
        return out;
    }

    /** What a cleanup removes: the commits (manifest placements) and the cell placements that no kept commit lists. */
    public record Prune(List<Commit> commits, List<String> placements) {
        public boolean isEmpty() {
            return commits.isEmpty();
        }
    }

    /** The cleanup that drops every commit {@code drop} accepts (heads of branches are never dropped). */
    public static Prune prune(CommitGraph g, Predicate<Commit> drop) {
        Set<String> heads = new HashSet<>();
        for (String b : g.branches()) for (Commit t : g.tips(b)) heads.add(t.id());
        return plan(g, c -> !heads.contains(c.id()) && drop.test(c));
    }

    /** Drops sealed commits older than {@code before} (milliseconds), except branch heads. */
    public static Prune olderThan(CommitGraph g, long before) {
        return prune(g, c -> !c.draft() && c.at() < before);
    }

    /** Deletes a branch: its commits that no other branch's history contains (merged work stays). Main cannot be deleted. */
    public static Prune deleteBranch(CommitGraph g, String branch) {
        if (CommitMeta.isMain(branch)) return new Prune(List.of(), List.of());
        Set<String> others = new HashSet<>();
        for (String b : g.branches()) {
            if (b.equals(branch)) continue;
            for (Commit t : g.tips(b)) others.addAll(g.ancestors(t.id()));
        }
        return plan(g, c -> c.branch().equals(branch) && !others.contains(c.id()));
    }

    private static Prune plan(CommitGraph g, Predicate<Commit> remove) {
        List<Commit> removed = new ArrayList<>();
        Set<String> kept = new HashSet<>();
        for (Commit c : g.all()) {
            if (remove.test(c)) {
                removed.add(c);
            } else {
                kept.addAll(c.cells().values());
                if (c.placementId() != null) kept.add(c.placementId());
            }
        }
        Set<String> placements = new LinkedHashSet<>();
        for (Commit c : removed) {
            if (c.placementId() != null && !kept.contains(c.placementId())) placements.add(c.placementId());
            for (String id : c.cells().values()) if (!kept.contains(id)) placements.add(id);
        }
        return new Prune(removed, new ArrayList<>(placements));
    }
}
