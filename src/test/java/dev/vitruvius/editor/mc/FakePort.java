package dev.vitruvius.editor.mc;

import dev.vitruvius.editor.core.Mirror;
import dev.vitruvius.editor.core.Rot;
import dev.vitruvius.editor.core.Vec3i;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** A placement list in memory, standing in for Litematica. */
final class FakePort implements PlacementPort {
    final Map<String, PlacementState> placements = new LinkedHashMap<>();
    final List<String> rebuilt = new ArrayList<>();
    final List<String> subRegionCopies = new ArrayList<>();
    int created, updated, removed;

    @Override
    public String create(Path file, Vec3i origin, Rot rot, Mirror mirror, String name, boolean enabled, boolean render, String preferredId) {
        String id = null;
        if (preferredId != null && !placements.containsKey(preferredId)) {
            try {
                id = UUID.fromString(preferredId).toString();
            } catch (IllegalArgumentException e) {
                // fresh id below
            }
        }
        if (id == null) id = UUID.randomUUID().toString();
        placements.put(id, new PlacementState(file, origin, rot, mirror, enabled, render, name));
        created++;
        return id;
    }

    @Override
    public void update(String hashId, Vec3i origin, Rot rot, Mirror mirror, boolean enabled, boolean render) {
        PlacementState s = placements.get(hashId);
        placements.put(hashId, new PlacementState(s.file(), origin, rot, mirror, enabled, render, s.name()));
        updated++;
    }

    @Override
    public void remove(String hashId) {
        if (placements.remove(hashId) != null) removed++;
    }

    @Override
    public PlacementState read(String hashId) {
        return placements.get(hashId);
    }

    @Override
    public Set<String> allHashIds() {
        return new HashSet<>(placements.keySet());
    }

    @Override
    public void markRebuild(String hashId) {
        rebuilt.add(hashId);
    }

    @Override
    public void copySubRegions(String fromId, String toId) {
        subRegionCopies.add(fromId + ">" + toId);
    }

    /** Somebody else (Litematica's own tools) moves a placement. */
    void moveExternally(String id, Vec3i origin, Rot rot, Mirror mirror) {
        PlacementState s = placements.get(id);
        placements.put(id, new PlacementState(s.file(), origin, rot, mirror, s.enabled(), s.render(), s.name()));
    }

    /** A placement the editor does not manage. */
    String addForeign(Path file, Vec3i origin) {
        String id = UUID.randomUUID().toString();
        placements.put(id, new PlacementState(file, origin, Rot.NONE, Mirror.NONE, true, true, "foreign"));
        return id;
    }
}
