package dev.vitruvius.editor.core;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Plans the masks of many placed copies at once (array cells, Loose objects, parts of linked assemblies): every copy
 * has its own {@link MaskPlan}, copies with the same {@link MaskPlan.Plan#hash} share one derived file, and one object
 * gets at most {@link #MAX_FILES} different files per pass; the copies beyond that keep the original file and are
 * counted in {@link Result#capped}. Pure.
 */
public final class CellMasks {
    private CellMasks() {}

    /** The most distinct derived files one object may have in one planning pass. */
    public static final int MAX_FILES = 256;

    /** One placed copy: {@code slot} is its stable identity (the key a reconciler asks the derived file for). */
    public record Cell(UUID slot, MaskPlan.Subject subject) {}

    /**
     * @param plans  slot to its non-empty plan; copies that need no mask (or were capped) are absent
     * @param capped object id to the number of copies left on the original file because of the cap
     * @param files  object id to the number of distinct files its copies need
     */
    public record Result(Map<UUID, MaskPlan.Plan> plans, Map<UUID, Integer> capped, Map<UUID, Integer> files) {
        public boolean anyCapped() {
            return !capped.isEmpty();
        }
    }

    public static Result plan(Scene scene, List<Cell> cells, Collection<? extends HasFront> owners, MaskPlan.Env env, MaskPlan.Filter f) {
        return plan(scene, cells, owners, env, f, MAX_FILES);
    }

    /** As above with another cap (for tests). */
    public static Result plan(Scene scene, List<Cell> cells, Collection<? extends HasFront> owners, MaskPlan.Env env, MaskPlan.Filter f, int cap) {
        MaskPlan.Cache cache = new MaskPlan.Cache();
        Map<UUID, MaskPlan.Plan> plans = new LinkedHashMap<>();
        Map<UUID, Set<String>> hashes = new HashMap<>();
        Map<UUID, Integer> capped = new LinkedHashMap<>();
        for (Cell c : cells) {
            MaskPlan.Plan p;
            try {
                p = MaskPlan.plan(scene, c.subject(), owners, env, f, cache);
            } catch (RuntimeException | LinkageError ex) {
                continue;
            }
            if (p.isEmpty()) continue;
            UUID obj = c.subject().object().id();
            Set<String> seen = hashes.computeIfAbsent(obj, k -> new HashSet<>());
            if (!seen.contains(p.hash()) && seen.size() >= cap) {
                capped.merge(obj, 1, Integer::sum);
                continue;
            }
            seen.add(p.hash());
            plans.put(c.slot(), p);
        }
        Map<UUID, Integer> files = new HashMap<>();
        hashes.forEach((k, v) -> files.put(k, v.size()));
        return new Result(plans, capped, files);
    }
}
