package dev.vitruvius.editor.sync.history;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * The commits of one project as a graph: branches, heads, log, merge base. Pure.
 *
 * <p>A branch's head is its newest commit that no other commit of the same branch lists as a parent. Two heads on one branch
 * ({@link #diverged}) happen when two players push to it at once; the newer one is shown and the window offers a merge.
 * Parents that are not in the list (pruned history) end the walk there: the commit is a root.
 */
public final class CommitGraph {
    private final Map<String, Commit> byId = new LinkedHashMap<>();

    /** Builds the graph. Two copies of one commit (a draft and its sealed version during a push) keep the sealed one, else the newer. */
    public CommitGraph(List<Commit> commits) {
        for (Commit c : commits) {
            Commit old = byId.get(c.id());
            if (old == null || better(c, old)) byId.put(c.id(), c);
        }
    }

    private static boolean better(Commit a, Commit b) {
        if (a.draft() != b.draft()) return !a.draft();
        return a.at() > b.at();
    }

    public List<Commit> all() {
        return List.copyOf(byId.values());
    }

    public Commit get(String id) {
        return byId.get(id);
    }

    public boolean isEmpty() {
        return byId.isEmpty();
    }

    /** The branch names, main first, the others by name. */
    public List<String> branches() {
        TreeSet<String> names = new TreeSet<>();
        for (Commit c : byId.values()) if (!c.main()) names.add(c.branch());
        List<String> out = new ArrayList<>();
        if (byId.values().stream().anyMatch(Commit::main)) out.add(CommitMeta.MAIN);
        out.addAll(names);
        return out;
    }

    /** The heads of a branch, newest first (more than one means the branch has diverged). */
    public List<Commit> tips(String branch) {
        String b = CommitMeta.isMain(branch) ? CommitMeta.MAIN : branch;
        Set<String> parentsOnBranch = new HashSet<>();
        for (Commit c : byId.values()) if (c.branch().equals(b)) parentsOnBranch.addAll(c.parents());
        List<Commit> out = new ArrayList<>();
        for (Commit c : byId.values()) if (c.branch().equals(b) && !parentsOnBranch.contains(c.id())) out.add(c);
        out.sort(Comparator.comparingLong(Commit::at).reversed().thenComparing(Commit::id));
        return out;
    }

    public Optional<Commit> head(String branch) {
        List<Commit> t = tips(branch);
        return t.isEmpty() ? Optional.empty() : Optional.of(t.get(0));
    }

    public boolean diverged(String branch) {
        return tips(branch).size() > 1;
    }

    /** The newest commit of the branch that is not a draft: what a new draft has as parent. Null when the branch has none. */
    public Commit sealedHead(String branch) {
        Commit h = head(branch).orElse(null);
        while (h != null && h.draft()) h = h.parents().isEmpty() ? null : byId.get(h.parents().get(0));
        return h;
    }

    /** Ids of a commit and everything it descends from (parents that are gone are skipped). */
    public Set<String> ancestors(String id) {
        Set<String> seen = new LinkedHashSet<>();
        ArrayDeque<String> todo = new ArrayDeque<>();
        todo.add(id);
        while (!todo.isEmpty()) {
            String x = todo.poll();
            Commit c = byId.get(x);
            if (c == null || !seen.add(x)) continue;
            todo.addAll(c.parents());
        }
        return seen;
    }

    public boolean isAncestor(String ancestor, String of) {
        return ancestors(of).contains(ancestor);
    }

    /** The newest commit both descend from, or null (unrelated histories). */
    public Commit mergeBase(String a, String b) {
        Set<String> common = new HashSet<>(ancestors(a));
        common.retainAll(ancestors(b));
        Set<String> dominated = new HashSet<>();
        for (String x : common) {
            Set<String> up = ancestors(x);
            up.remove(x);
            dominated.addAll(up);
        }
        Commit best = null;
        for (String x : common) {
            if (dominated.contains(x)) continue;
            Commit c = byId.get(x);
            if (best == null || c.at() > best.at() || c.at() == best.at() && c.id().compareTo(best.id()) > 0) best = c;
        }
        return best;
    }

    /** The commit and its ancestors, newest first: what the history list of a branch shows. */
    public List<Commit> log(String headId) {
        List<Commit> out = new ArrayList<>();
        for (String id : ancestors(headId)) out.add(byId.get(id));
        out.sort(Comparator.comparingLong(Commit::at).reversed().thenComparing(Commit::id));
        return out;
    }

    /** Whether the branch has work the other does not: its newest sealed commit is not in the other head's history. */
    public boolean ahead(String branch, String of) {
        Commit h = sealedHead(branch), o = head(of).orElse(null);
        if (h == null) return false;
        return o == null || !isAncestor(h.id(), o.id());
    }
}
