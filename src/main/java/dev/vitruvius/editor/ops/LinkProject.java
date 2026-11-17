package dev.vitruvius.editor.ops;

import dev.vitruvius.editor.core.ProjectNode;
import dev.vitruvius.editor.core.Vec3i;

import java.util.UUID;

/**
 * Links another project into a project as an assembly ("Link into active project as assembly"). The link path is
 * what the caller wants stored for the other project; its identity for the cycle check is its folder
 * ({@link ProjectNode#path()}). Refused when that would loop, e.g. a project linked into itself.
 */
public final class LinkProject extends LinkAssembly {
    public LinkProject(UUID parentId, int index, ProjectNode linked, String linkPath, Vec3i pos) {
        super(parentId, index, linked.name(), linkPath, linked.path(), pos, "Link project");
    }
}
