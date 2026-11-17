package dev.vitruvius.editor.ui;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * The selected node ids plus the active one (the one Properties shows: the last clicked). Pure; click semantics of
 * the tree and the viewport: plain click replaces, Shift toggles, Ctrl selects a range from the active row.
 */
public final class SelectionModel {
    private final LinkedHashSet<UUID> ids = new LinkedHashSet<>();
    private UUID active;
    private int version;

    /** Changes with every modification, so windows can cache what they derive from the selection. */
    public int version() {
        return version;
    }

    public UUID active() {
        return active;
    }

    public boolean isSelected(UUID id) {
        return ids.contains(id);
    }

    public boolean isEmpty() {
        return ids.isEmpty();
    }

    public int size() {
        return ids.size();
    }

    /** The selected ids in selection order. */
    public List<UUID> ids() {
        return new ArrayList<>(ids);
    }

    public void clear() {
        if (ids.isEmpty() && active == null) return;
        ids.clear();
        active = null;
        version++;
    }

    /** Replaces the selection with one id (null clears). */
    public void set(UUID id) {
        if (id == null) {
            clear();
            return;
        }
        ids.clear();
        ids.add(id);
        active = id;
        version++;
    }

    /** Replaces the selection; the last id becomes active. */
    public void setAll(Collection<UUID> all) {
        ids.clear();
        active = null;
        for (UUID id : all) {
            ids.add(id);
            active = id;
        }
        version++;
    }

    /** Shift click: adds the id, or removes it when it is selected (the active one moves to the last selected). */
    public void toggle(UUID id) {
        if (ids.remove(id)) {
            if (id.equals(active)) active = ids.isEmpty() ? null : last();
        } else {
            ids.add(id);
            active = id;
        }
        version++;
    }

    /** Adds the id and makes it active. */
    public void add(UUID id) {
        ids.remove(id);
        ids.add(id);
        active = id;
        version++;
    }

    /** Makes an already selected id the active one (click in the "selected objects" list). */
    public void makeActive(UUID id) {
        if (ids.contains(id) && !id.equals(active)) {
            active = id;
            version++;
        }
    }

    /**
     * Ctrl click: selects every row between the active one and {@code id} in {@code order} (the visible rows top to
     * bottom), keeping what was selected. Without an active row it is a plain click. The clicked row becomes active.
     */
    public void range(List<UUID> order, UUID id) {
        int to = order.indexOf(id);
        int from = active == null ? -1 : order.indexOf(active);
        if (to < 0 || from < 0) {
            set(id);
            return;
        }
        int lo = Math.min(from, to), hi = Math.max(from, to);
        for (int i = lo; i <= hi; i++) ids.add(order.get(i));
        ids.remove(id);
        ids.add(id);
        active = id;
        version++;
    }

    /** Drops ids that no longer satisfy {@code exists} (deleted nodes). */
    public void prune(Predicate<UUID> exists) {
        boolean changed = ids.removeIf(i -> !exists.test(i));
        if (active != null && !ids.contains(active)) {
            active = ids.isEmpty() ? null : last();
            changed = true;
        }
        if (changed) version++;
    }

    private UUID last() {
        UUID l = null;
        for (UUID i : ids) l = i;
        return l;
    }

    /** A copy of the id set (for drag payloads). */
    public Set<UUID> copy() {
        return new LinkedHashSet<>(ids);
    }
}
