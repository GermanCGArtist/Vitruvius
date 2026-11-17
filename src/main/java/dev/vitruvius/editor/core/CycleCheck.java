package dev.vitruvius.editor.core;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * Whether linking a project or {@code .lasm} (by path) into a target would create a cycle. The caller gives paths in
 * one normalised form (the same file always the same string) and a resolver from a path to the paths it links
 * (empty for a file that links nothing or cannot be read).
 */
public final class CycleCheck {
    private CycleCheck() {}

    /** Whether linking {@code linked} into {@code target} would loop: they are the same, or {@code linked} reaches {@code target}. */
    public static boolean wouldCreateCycle(String target, String linked, Function<String, ? extends Collection<String>> links) {
        return findCycle(target, linked, links).isPresent();
    }

    /**
     * The loop as a path of files, starting and ending at {@code target}: [target, linked, ..., target].
     * Empty when there is none. Existing loops that do not pass through the target are skipped over.
     */
    public static Optional<List<String>> findCycle(String target, String linked, Function<String, ? extends Collection<String>> links) {
        if (target.equals(linked)) return Optional.of(List.of(target, target));
        Set<String> seen = new HashSet<>();
        ArrayDeque<List<String>> work = new ArrayDeque<>();
        seen.add(linked);
        work.add(List.of(linked));
        while (!work.isEmpty()) {
            List<String> path = work.poll();
            Collection<String> next = links.apply(path.get(path.size() - 1));
            if (next == null) next = Collections.emptyList();
            for (String n : next) {
                if (n.equals(target)) {
                    List<String> out = new ArrayList<>();
                    out.add(target);
                    out.addAll(path);
                    out.add(target);
                    return Optional.of(out);
                }
                if (seen.add(n)) {
                    List<String> p = new ArrayList<>(path);
                    p.add(n);
                    work.add(p);
                }
            }
        }
        return Optional.empty();
    }
}
