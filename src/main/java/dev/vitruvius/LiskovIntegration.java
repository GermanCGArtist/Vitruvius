package dev.vitruvius;

import dev.liskov.core.FileTypeRegistry;
import dev.liskov.core.SemanticColor;
import dev.liskov.ui.explorer.FileActionRegistry;
import dev.liskov.ui.theme.MubWidgets;
import dev.vitruvius.editor.EditorMode;
import dev.vitruvius.editor.core.ProjectNode;
import dev.vitruvius.editor.io.ProjectLayout;
import dev.vitruvius.editor.mc.Reconciler;
import net.minecraft.client.Minecraft;

import java.nio.file.Path;

/**
 * Vitruvius in Liskov's shared UI: an "Open in Vitruvius" action on project files in the Liskov Explorer (F8), and
 * Command Palette (Ctrl+P) entries. Needs Litematica (the editor does).
 */
public final class LiskovIntegration {
    private LiskovIntegration() {}

    public static void init() {
        dev.vitruvius.editor.ui.MenuModel.setSyncHook(dev.vitruvius.editor.sync.LiskovShare.get());
        FileActionRegistry.registerAction(new FileActionRegistry.Action(
                "vitruvius.open_project",
                "OPEN IN VITRUVIUS",
                SemanticColor.FOCUS,
                type -> type != null && FileTypeRegistry.VPROJ.id().equals(type.id()),
                path -> Minecraft.getInstance().execute(() -> openProject(path))));

        MubWidgets.CommandPalette.register(new MubWidgets.CommandPalette.Entry(
                "vitruvius.toggle_editor", "Vitruvius: Toggle Editor Mode (F6)", "Vp", SemanticColor.FOCUS,
                () -> Minecraft.getInstance().execute(() -> EditorMode.toggle(Minecraft.getInstance()))));
        MubWidgets.CommandPalette.register(new MubWidgets.CommandPalette.Entry(
                "vitruvius.projects_folder", "Vitruvius: Show Projects Folder", "Vp", SemanticColor.FOCUS,
                () -> VitruviusPaths.fs().openInExplorer(ProjectLayout.projectsRoot(VitruviusPaths.schematicsDir()))));
    }

    private static boolean reported;

    /** Logs once that Liskov's UI host came up with Vitruvius on it, and where the files are. Client tick. */
    public static void tick(Minecraft mc) {
        if (reported || !dev.liskov.ui.host.LiskovUiHost.isInitialized()) return;
        reported = true;
        System.out.println("[Vitruvius] running on Liskov's UI host; schematics in " + VitruviusPaths.schematicsDir()
                + ", settings in " + VitruviusPaths.configDir());
    }

    /** Opens a project file (or folder) from the explorer in the editor, and switches editor mode on. Client thread. */
    static void openProject(Path path) {
        Minecraft mc = Minecraft.getInstance();
        if (!Reconciler.active()) {
            MubWidgets.ToastHost.push("Vitruvius: join a world to open a project", SemanticColor.WARN);
            return;
        }
        try {
            ProjectNode p = Reconciler.projects().open(path);
            Reconciler.projects().setActive(p.id());
            if (!EditorMode.isActive()) EditorMode.enter(mc);
            MubWidgets.ToastHost.push("Vitruvius: opened \"" + p.name() + "\"", SemanticColor.FOCUS);
        } catch (Exception e) {
            MubWidgets.ToastHost.push("Vitruvius: cannot open " + path.getFileName() + ": " + e.getMessage(), SemanticColor.DANGER);
        }
    }
}
