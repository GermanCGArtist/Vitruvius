package dev.vitruvius.editor.ops;

import dev.vitruvius.editor.core.Ids;
import dev.vitruvius.editor.core.ProjectNode;
import dev.vitruvius.editor.core.Vec3i;
import dev.vitruvius.editor.io.ProjectIo;
import dev.vitruvius.editor.io.ProjectLayout;

import java.util.UUID;

/**
 * Creates a project: {@code Projects/<name>/project.json} is written (a free folder name is chosen) and the project
 * is opened and made active. Undo closes it and moves the {@code project.json} to {@code .trash}.
 */
public final class NewProject extends BaseCommand {
    private final String name;
    private final String world;
    private final String dim;
    private final Vec3i origin;
    private final UUID id = Ids.newId();
    private String path;

    public NewProject(String name, String world, String dim, Vec3i origin) {
        super("New project");
        this.name = name == null ? "" : name.strip();
        this.world = world;
        this.dim = dim;
        this.origin = origin == null ? Vec3i.ZERO : origin;
    }

    public UUID projectId() {
        return id;
    }

    @Override
    protected Refusal refusal(EditContext c) {
        return name.isEmpty() ? new Refusal("A project needs a name") : null;
    }

    /** The folder of the new project; once chosen it stays the same for redo, as long as it is still free. */
    static String freeProjectPath(EditContext ctx, String name, String chosen) {
        if (chosen != null && !ctx.fs().exists(FileSystemPort.join(chosen, ProjectLayout.PROJECT_FILE))) return chosen;
        String folder = ProjectLayout.uniqueName(name, n -> ctx.fs().exists(FileSystemPort.join(ProjectLayout.PROJECTS_DIR, n)));
        return FileSystemPort.join(ProjectLayout.PROJECTS_DIR, folder);
    }

    @Override
    protected void doApply() {
        rememberScene();
        path = freeProjectPath(ctx, name, path);
        ProjectNode p = new ProjectNode(id, name);
        p.setPath(path);
        p.setWorld(world);
        if (dim != null) p.setDim(dim);
        p.setOrigin(origin);
        ctx.scene().openProject(p);
        ctx.scene().setActiveProject(p);
        journal().create(FileSystemPort.join(path, ProjectLayout.PROJECT_FILE), ProjectIo.toJson(p));
        setLabel("New project " + name);
        touch(p);
    }
}
