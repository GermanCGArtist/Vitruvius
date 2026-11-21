package dev.vitruvius.editor;

import dev.vitruvius.Compat;
import dev.vitruvius.editor.core.AssemblyBounds;
import dev.vitruvius.editor.core.AssemblyNode;
import dev.vitruvius.editor.core.BakeParts;
import dev.vitruvius.editor.core.FolderNode;
import dev.vitruvius.editor.core.Node;
import dev.vitruvius.editor.core.ObjectNode;
import dev.vitruvius.editor.core.ProjectNode;
import dev.vitruvius.editor.core.Transform;
import dev.vitruvius.editor.io.ProjectLayout;
import dev.vitruvius.editor.io.ZipExport;
import dev.vitruvius.editor.mc.Bake;
import dev.vitruvius.editor.mc.LinkPaths;
import dev.vitruvius.editor.ui.ToastQueue;
import net.minecraft.client.Minecraft;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * The exports of the editor (DESIGN: Export menus). Everything is written to {@code schematics/exports/} under a name
 * that is not taken yet, and a toast says where. Planning reads the model on the render thread; the file work runs
 * on a daemon worker so a big bake or zip does not stall the game, and its toast is posted back to the client thread.
 */
public final class EditorExports {
    private final EditorSession s;

    EditorExports(EditorSession s) {
        this.s = s;
    }

    public Path dir() {
        return s.schematicsDir().resolve(ZipExport.EXPORTS_DIR);
    }

    private String shown(Path file) {
        try {
            return s.schematicsDir().relativize(file.toAbsolutePath().normalize()).toString().replace('\\', '/');
        } catch (IllegalArgumentException e) {
            return file.toString();
        }
    }

    // ------------------------------------------------------------------------------------------ zips

    /** The linked file of an assembly (seen from {@code base}; null for one in the model) for the planner. */
    private ZipExport.LinkedFile linkedFile(AssemblyNode a, Path base) {
        AssemblyBounds.Linked l = s.linkedParts(a, base);
        Path target = s.linkTarget(a, base);
        if (l == null || target == null) return null;
        String name = target.getFileName().toString();
        if (name.equals(ProjectLayout.PROJECT_FILE) && target.getParent() != null) name = target.getParent().getFileName().toString();
        else if (name.toLowerCase().endsWith(ProjectLayout.ASSEMBLY_EXT)) name = name.substring(0, name.length() - ProjectLayout.ASSEMBLY_EXT.length());
        return new ZipExport.LinkedFile(LinkPaths.identity(target), name, l.parts(), l.base());
    }

    private ZipExport.Resolver resolver() {
        return new ZipExport.Resolver() {
            @Override
            public Path file(ObjectNode o, Path base) {
                return s.fileOf(o, base);
            }

            @Override
            public ZipExport.LinkedFile link(AssemblyNode a, Path base) {
                return linkedFile(a, base);
            }
        };
    }

    /** Assembly (.zip): the {@code .lasm} of its parts plus every file they use, nested {@code .lasm} files included. */
    public void exportAssembly(AssemblyNode a) {
        List<Node> parts;
        Path base = null;
        if (a.isLinked()) {
            AssemblyBounds.Linked l = s.linkedParts(a, null);
            if (l == null) {
                s.toast(ToastQueue.Level.WARN, "The linked file of \"" + a.name() + "\" cannot be read");
                return;
            }
            parts = l.parts();
            base = l.base();
        } else {
            parts = new ArrayList<>(a.children());
        }
        zip(a.name(), ZipExport.planNodes(a.name(), parts, base, resolver()));
    }

    /** Folder (.zip): a {@code .lasm} of what the folder holds plus its files. */
    public void exportFolder(FolderNode f) {
        zip(f.name(), ZipExport.planNodes(f.name(), new ArrayList<>(f.children()), null, resolver()));
    }

    /** Selection (.zip): a {@code .lasm} of the selected nodes plus their files. */
    public void exportSelection(List<Node> nodes) {
        List<Node> top = topMost(nodes);
        if (top.isEmpty()) {
            s.toast(ToastQueue.Level.INFO, "Nothing selected to export");
            return;
        }
        zip(top.size() == 1 ? top.get(0).name() : "Selection", ZipExport.planNodes("Selection", top, null, resolver()));
    }

    /** Project (.zip): the project folder without {@code .cache} and {@code .trash}. Saves the project first. */
    public void exportProject(ProjectNode p) {
        s.projects().save(p.id());
        try {
            zip(p.name(), ZipExport.planProject(s.projectDir(p)));
        } catch (IOException e) {
            s.toast(ToastQueue.Level.ERROR, "Cannot read the project folder: " + e.getMessage());
        }
    }

    private void zip(String name, ZipExport.Plan plan) {
        Path out = dir().resolve(ZipExport.uniqueName(dir(), name, ".zip"));
        List<String> warnings = plan.warnings();
        async(() -> {
            ZipExport.write(plan, out);
            return "Exported to " + shown(out) + (warnings.isEmpty() ? "" : " (" + warnings.size() + " warning" + (warnings.size() == 1 ? "" : "s") + ": " + warnings.get(0) + ")");
        }, warnings.isEmpty() ? ToastQueue.Level.INFO : ToastQueue.Level.WARN);
    }

    // ------------------------------------------------------------------------------------------ litematic

    /**
     * Export as .litematic for one object: its file, copied; for an object with trims or Booleans the derived file
     * (cuts and front-order trims applied).
     */
    public void exportLitematic(ObjectNode o) {
        Path src = s.fileOf(o);
        if (src == null || !Files.isRegularFile(src)) {
            s.toast(ToastQueue.Level.WARN, "The file of \"" + o.name() + "\" is missing");
            return;
        }
        String base = o.name();
        String file = src.getFileName().toString();
        if (!base.isBlank() && !file.toLowerCase().startsWith(base.toLowerCase())) file = base + ProjectLayout.SCHEMATIC_EXT;
        Path out = dir().resolve(ZipExport.uniqueName(dir(), stripExt(file), ProjectLayout.SCHEMATIC_EXT));
        // a trimmed or cut object exports its derived variant (what Litematica shows), built first when needed
        var plan = s.planOf(o, dev.vitruvius.editor.core.MaskPlan.Filter.ALL);
        var proj = s.scene().projectOf(o);
        if (!plan.isEmpty() && proj != null) {
            s.toast(ToastQueue.Level.INFO, "Building the trimmed file for \"" + o.name() + "\"…");
            dev.vitruvius.editor.mc.DerivedFiles.shared().build(plan, src, s.projectDir(proj), b -> {
                if (!b.ok() || b.file() == null) {
                    s.toast(ToastQueue.Level.ERROR, "Export failed: " + (b.error() == null ? "no file" : b.error()));
                    return;
                }
                async(() -> {
                    Files.createDirectories(out.getParent());
                    Files.copy(b.file(), out);
                    return "Exported to " + shown(out) + " (with trims and Booleans applied)";
                }, ToastQueue.Level.INFO);
            });
            return;
        }
        async(() -> {
            Files.createDirectories(out.getParent());
            Files.copy(src, out);
            return "Exported to " + shown(out);
        }, ToastQueue.Level.INFO);
    }

    /** Bake to one .litematic: every placed copy of the nodes (array copies, linked parts) merged, one sub-region per part. */
    public void bake(List<Node> nodes, String name) {
        List<Node> top = topMost(nodes);
        AssemblyBounds.Env env = s.boundsEnv();
        List<BakeParts.Part> parts = new ArrayList<>();
        for (Node n : top) {
            Transform parent = n.parent() == null ? Transform.IDENTITY : s.scene().worldTransform(n.parent());
            parts.addAll(BakeParts.collect(List.of(n), parent, null, env));
        }
        if (parts.isEmpty()) {
            s.toast(ToastQueue.Level.INFO, "Nothing to bake");
            return;
        }
        String file = ZipExport.uniqueName(dir(), name == null || name.isBlank() ? "Bake" : name, ProjectLayout.SCHEMATIC_EXT);
        String stem = stripExt(file);
        Path out = dir();
        // parts whose base cell has a ready derived file bake that one (trims and Booleans applied); read here on the client thread
        java.util.Map<java.util.UUID, Path> derivedOf = new java.util.HashMap<>();
        for (BakeParts.Part p : parts) {
            if (p.base() != null || !p.cell().isEmpty()) continue;
            Path d = dev.vitruvius.editor.mc.DerivedFiles.shared().readyFile(p.object().id());
            if (d != null) derivedOf.put(p.object().id(), d);
        }
        async(() -> {
            Files.createDirectories(out);
            Bake.Result r = Bake.bake(parts, p -> {
                Path d = p.base() == null && p.cell().isEmpty() ? derivedOf.get(p.object().id()) : null;
                return d != null ? d : s.fileOf(p.object(), p.base());
            }, out, stem, Compat.playerName());
            if (!r.ok()) throw new IOException(r.error());
            return "Baked " + r.regions() + " sub-regions, " + r.blocks() + " blocks to " + shown(r.file())
                    + (r.skippedParts() > 0 ? " (" + r.skippedParts() + " parts skipped: file missing)" : "");
        }, ToastQueue.Level.INFO);
    }

    /** Bake a whole project. */
    public void bakeProject(ProjectNode p) {
        bake(new ArrayList<>(p.children()), p.name());
    }

    // ------------------------------------------------------------------------------------------ helpers

    private static String stripExt(String f) {
        int i = f.lastIndexOf('.');
        return i > 0 ? f.substring(0, i) : f;
    }

    private static List<Node> topMost(List<Node> nodes) {
        List<Node> out = new ArrayList<>();
        for (Node n : nodes) {
            if (!(n instanceof ObjectNode || n instanceof AssemblyNode || n instanceof FolderNode)) continue;
            boolean covered = false;
            for (Node x = n.parent(); x != null; x = x.parent()) {
                if ((x instanceof ObjectNode || x instanceof AssemblyNode || x instanceof FolderNode) && nodes.contains(x)) {
                    covered = true;
                    break;
                }
            }
            if (!covered) out.add(n);
        }
        return out;
    }

    private void async(Callable<String> job, ToastQueue.Level okLevel) {
        Thread t = new Thread(() -> {
            String msg;
            ToastQueue.Level level = okLevel;
            try {
                msg = job.call();
            } catch (Exception | LinkageError e) {
                msg = "Export failed: " + e.getMessage();
                level = ToastQueue.Level.ERROR;
            }
            String text = msg;
            ToastQueue.Level lv = level;
            Minecraft.getInstance().execute(() -> s.toast(lv, text));
        }, "vitruvius-export");
        t.setDaemon(true);
        t.start();
    }
}
