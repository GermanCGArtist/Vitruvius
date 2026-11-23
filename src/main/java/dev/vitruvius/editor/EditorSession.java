package dev.vitruvius.editor;

import dev.vitruvius.editor.cam.EditorCamera;
import dev.vitruvius.editor.core.AssemblyBounds;
import dev.vitruvius.editor.core.AssemblyNode;
import dev.vitruvius.editor.core.Box;
import dev.vitruvius.editor.core.Node;
import dev.vitruvius.editor.core.ObjectNode;
import dev.vitruvius.editor.core.Overlaps;
import dev.vitruvius.editor.core.ProjectNode;
import dev.vitruvius.editor.core.Scene;
import dev.vitruvius.editor.core.Vec3i;
import dev.vitruvius.editor.gizmo.Pivots;
import dev.vitruvius.editor.io.ProjectLayout;
import dev.vitruvius.editor.io.ZipExport;
import dev.vitruvius.editor.mc.DerivedFiles;
import dev.vitruvius.editor.mc.ExternalChange;
import dev.vitruvius.editor.mc.LasmCache;
import dev.vitruvius.editor.mc.LinkPaths;
import dev.vitruvius.editor.ops.Move;
import dev.vitruvius.editor.world.Gizmo;
import dev.vitruvius.editor.mc.ProjectManager;
import dev.vitruvius.editor.mc.Reconciler;
import dev.vitruvius.editor.mc.SchematicInfo;
import dev.vitruvius.editor.ops.ChangeSink;
import dev.vitruvius.editor.ops.EditContext;
import dev.vitruvius.editor.ops.ExternalChangeCommand;
import dev.vitruvius.editor.ops.NioFileSystemPort;
import dev.vitruvius.editor.ops.UndoStack;
import dev.vitruvius.editor.pick.WorldBoxes;
import dev.vitruvius.editor.ui.EditorActions;
import dev.vitruvius.editor.ui.SelectionModel;
import dev.vitruvius.editor.ui.ToastQueue;
import dev.vitruvius.editor.ui.TreeModel;
import fi.dy.masa.litematica.data.DataManager;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.AABB;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;

/**
 * The glue of the editor UI (WP 1.4/1.5): one object per game that owns the undo stack, the edit context, the
 * selection, the active tool and the toasts, and connects them to the reconciler and the project manager.
 *
 * <ul>
 *   <li>The {@link EditContext} is rooted at Litematica's schematics folder; its change sink only flags that the
 *       projects need saving (the reconciler already looks at the model every tick).</li>
 *   <li>Drift the reconciler finds (a placement moved outside the editor) is recorded on the undo stack as an
 *       {@link ExternalChangeCommand}.</li>
 *   <li>When the world changes the project manager builds a new {@link Scene}; the session then starts a fresh
 *       context, undo stack and selection.</li>
 * </ul>
 * Render/client thread only. Needs Litematica; use {@link #get()}, which is null without it.
 */
public final class EditorSession {
    private static final Logger LOG = LoggerFactory.getLogger("vitruvius-editor");

    public enum Tool { SELECT, MOVE, ROTATE }

    private static EditorSession instance;
    private static boolean failed;

    /** The session, or null when Litematica is missing or it failed to start. */
    public static EditorSession get() {
        if (instance == null && !failed) {
            if (!FabricLoader.getInstance().isModLoaded("litematica")) {
                failed = true;
                return null;
            }
            try {
                instance = new EditorSession();
            } catch (Throwable t) {
                failed = true;
                LOG.warn("[vitruvius] editor session failed to start", t);
            }
        }
        return instance;
    }

    /** Called every client tick (after the reconciler): keeps the session in step with the world and saves. */
    public static void tick(Minecraft mc) {
        EditorSession s = get();
        if (s != null) s.tickInternal(mc);
    }

    // ------------------------------------------------------------------------------------------ state

    private final ProjectManager pm = Reconciler.projects();
    private Scene scene;
    private EditContext ctx;
    private UndoStack undo;
    private NioFileSystemPort port;
    private Path schematicsDir;

    public final SelectionModel selection = new SelectionModel();
    public final TreeModel tree = new TreeModel();
    public final ToastQueue toasts = new ToastQueue();
    public final EditorActions actions;
    private Tool tool = Tool.SELECT;

    public boolean windowsHidden;
    public boolean showScene = true, showProperties = true, showTopView = true, showShape, showChat, showHistory;
    /** Snapping toolbar toggle (Ctrl inverts it while dragging). Saved in the UI config. */
    public boolean snap;
    /** Local view is on (see {@link #toggleLocalView()}); runtime only. */
    public boolean localView;
    /** Pivot mode of the toolbar dropdown. Saved in the UI config. */
    public Pivots.Mode pivotMode = Pivots.Mode.MEDIAN;
    /** The move gizmo, ring, typed transforms and box select. */
    public final Gizmo gizmo = new Gizmo(this);
    /** Edit mode for the boxes of objects of other mods (E). */
    public final dev.vitruvius.editor.world.BoxEdit boxEdit = new dev.vitruvius.editor.world.BoxEdit(this);
    private Set<UUID> localOwners;

    /** UI requests the windows pick up on their next frame. */
    public UUID renameRequest, scrollToRow, addSchematicParent, addAssemblyParent, linkProjectParent;
    public boolean focusFilter, addMenuRequest, newProjectRequest, openProjectRequest;
    public boolean popupOpen;
    public boolean resetLayout;

    private boolean dirtyFlag;
    private int dirtyTicks;
    private boolean driftRegistered;

    private WorldBoxes boxes;
    private long boxesAt;
    private Set<UUID> issues = Set.of();
    private final Map<java.nio.file.Path, Long> unreadable = new HashMap<>();
    private Map<UUID, List<Overlaps.Overlap>> overlapsByNode = Map.of();
    private List<Overlaps.Overlap> overlapList = List.of();

    private final Properties config = new Properties();
    private final Path configFile = FabricLoader.getInstance().getConfigDir().resolve("vitruvius").resolve("editor-ui.properties");

    private EditorSession() {
        actions = new EditorActions(this::undoStack, selection, new HostImpl());
        loadConfig();
        dev.cg.uihost.Theme.Preset savedTheme = dev.cg.uihost.Theme.Preset.fromId(config.getProperty("theme", dev.cg.uihost.Theme.Preset.SYNTH_TERMINAL.id));
        dev.cg.uihost.UiHost.setThemePreset(savedTheme);
        dev.vitruvius.editor.view.Ui.syncTheme();
        snap = flag("snap", false);
        try {
            pivotMode = Pivots.Mode.valueOf(config.getProperty("pivot", Pivots.Mode.MEDIAN.name()));
        } catch (IllegalArgumentException e) {
            pivotMode = Pivots.Mode.MEDIAN;
        }
        sync();
    }

    /** Saves the snap toggle and the pivot mode (call after the toolbar changed one). */
    public void saveToolbarState() {
        config.setProperty("pivot", pivotMode.name());
        setFlag("snap", snap);
    }

    /** Switches the active terminal theme preset and saves it in editor-ui.properties. */
    public void setThemePreset(dev.cg.uihost.Theme.Preset preset) {
        if (preset == null) return;
        dev.cg.uihost.UiHost.setThemePreset(preset);
        dev.vitruvius.editor.view.Ui.syncTheme();
        config.setProperty("theme", preset.id);
        saveConfig();
    }

    // ------------------------------------------------------------------------------------------ pivot, lock, local view

    /** The pivot block of a selection under the current pivot mode, or null when none of it has a visible box. */
    public Vec3i pivotOf(List<UUID> ids) {
        WorldBoxes wb = boxes();
        Scene sc = scene();
        List<Box> boxes = new ArrayList<>();
        for (UUID id : ids) {
            Box b = wb.unionOf(sc, List.of(id));
            if (b != null) boxes.add(b);
        }
        if (boxes.isEmpty()) return null;
        UUID a = selection.active();
        Box active = a == null || !ids.contains(a) ? null : wb.unionOf(sc, List.of(a));
        return Pivots.compute(pivotMode, boxes, active);
    }

    /** True when the selection cannot be moved (it is, or holds, a part of a linked assembly): the gizmo shows disabled. */
    public boolean selectionLocked() {
        return selectionLockReason() != null;
    }

    /** Why the selection cannot be moved (the command's own refusal), or null when it can. */
    public String selectionLockReason() {
        List<UUID> ids = selection.ids();
        if (ids.isEmpty()) return null;
        return new Move(ids, new Vec3i(1, 0, 0)).check(context()).map(r -> r.reason()).orElse(null);
    }

    /**
     * Local view (/): only the owners of the selected objects stay placed. Lightest way that works: the Reconciler
     * gets a runtime filter ({@code ReconcileCore.setLocalView}), so nothing is saved and nothing in Litematica's own
     * toggles changes; the other placements are removed and come back when it is switched off. The boxes follow, so
     * picking and the gizmo only see what is visible.
     */
    public void toggleLocalView() {
        if (localOwners != null) {
            setLocalOwners(null);
            return;
        }
        Set<UUID> owners = new HashSet<>();
        Scene sc = scene();
        for (UUID id : selection.ids()) {
            Node n = sc.byId(id);
            if (n == null) continue;
            for (Node x : n.subtree()) if (x instanceof ObjectNode) owners.add(sc.ownerOf(x).id());
            if (n instanceof dev.vitruvius.editor.core.AssemblyNode) owners.add(sc.ownerOf(n).id());
        }
        if (owners.isEmpty()) {
            toast(ToastQueue.Level.INFO, "Select an object first, then / isolates it");
            return;
        }
        setLocalOwners(owners);
    }

    private void setLocalOwners(Set<UUID> owners) {
        localOwners = owners;
        localView = owners != null;
        Reconciler.setLocalView(owners);
        invalidateBoxes();
    }

    private boolean visibleInLocalView(ObjectNode o) {
        if (localOwners == null) return true;
        Scene sc = scene();
        return sc.projectOf(o) == null || localOwners.contains(sc.ownerOf(o).id());   // Loose objects are not filtered
    }

    // ------------------------------------------------------------------------------------------ accessors

    public ProjectManager projects() {
        return pm;
    }

    public Scene scene() {
        sync();
        return scene;
    }

    public UndoStack undoStack() {
        sync();
        return undo;
    }

    public EditContext context() {
        sync();
        return ctx;
    }

    public Path schematicsDir() {
        return schematicsDir;
    }

    public Tool tool() {
        return tool;
    }

    public void setTool(Tool t) {
        tool = t;
    }

    public ToastQueue.Toast toast(ToastQueue.Level level, String text) {
        return toasts.add(level, text);
    }

    /** Makes the context follow the project manager's scene (it is replaced when a world is entered). */
    private void sync() {
        Scene now = pm.scene();
        if (now == scene) return;
        if (schematicsDir == null) schematicsDir = DataManager.getSchematicsBaseDirectory().toAbsolutePath().normalize();
        scene = now;
        gizmo.cancel();
        if (localOwners != null) setLocalOwners(null);          // a new world: the filter's ids do not exist there
        port = new NioFileSystemPort(schematicsDir, () -> {
            List<Path> roots = new ArrayList<>();
            if (scene != null) {
                for (ProjectNode p : scene.projects()) {
                    if (p.path() != null && !p.path().isEmpty()) roots.add(projectDir(p));
                }
            }
            return roots;
        });
        ctx = new EditContext(scene, port, new ChangeSink() {
            @Override
            public void markDirty(UUID projectId) {
                dirtyFlag = true;
                dirtyTicks = 0;
                boxesAt = 0;
            }

            @Override
            public void affected(java.util.Collection<UUID> nodeIds) {
                boxesAt = 0;
            }
        }).setPortRoot(schematicsDir).setLooseDir("").setLinks(LinkPaths::linksOf);
        undo = new UndoStack(ctx);
        selection.clear();
        boxes = null;
    }

    private void tickInternal(Minecraft mc) {
        sync();
        if (!driftRegistered) {
            driftRegistered = true;
            Reconciler.setDriftListener(this::onDrift);
            DerivedFiles.shared().setBusy(() -> gizmo.modal() || gizmo.preview() != null || (undo != null && undo.inTransaction()));
        }
        if (dirtyFlag && ++dirtyTicks >= 20) {
            dirtyFlag = false;
            try {
                pm.saveChanged();
            } catch (RuntimeException e) {
                LOG.warn("[vitruvius] saving failed", e);
            }
        }
        selection.prune(id -> scene.byId(id) != null);
    }

    private void onDrift(ExternalChange change) {
        ObjectNode n = change.node();
        undo.record(ExternalChangeCommand.transform(n.id(), n.name(), change.before(), change.after()));
        boxesAt = 0;
    }

    // ------------------------------------------------------------------------------------------ assemblies, links, files

    private final LasmCache lasms = new LasmCache();
    /** The exports (zip, bake, .litematic). */
    public final EditorExports exports = new EditorExports(this);

    /** The folder of a project as an absolute path. */
    public Path projectDir(ProjectNode p) {
        Path d = Path.of(p.path());
        return (d.isAbsolute() ? d : schematicsDir.resolve(d)).toAbsolutePath().normalize();
    }

    /** The file of an object whose files are relative to {@code base}; null base means the object is in the model. */
    public Path fileOf(ObjectNode o, Path base) {
        if (base == null) return fileOf(o);
        if (o.file().isEmpty()) return null;
        try {
            return ProjectLayout.resolveInside(base, o.file());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** The file a linked assembly points to (the link is relative to {@code base}, null for an assembly in the model), or null. */
    public Path linkTarget(AssemblyNode a, Path base) {
        if (!a.isLinked()) return null;
        Path from = base;
        if (from == null) {
            ProjectNode p = scene().projectOf(a);
            if (p == null) return null;
            from = projectDir(p);
        }
        return LinkPaths.target(from, ((AssemblyNode.Link) a.source()).path());
    }

    /** The parts of a linked assembly (read from its file, cached by modification time) with the base their files use; null when unreadable. */
    public AssemblyBounds.Linked linkedParts(AssemblyNode a, Path base) {
        Path target = linkTarget(a, base);
        if (target == null) return null;
        Path from = base != null ? base : projectDir(scene().projectOf(a));
        List<Node> parts = lasms.parts(target);
        return parts == null ? null : new AssemblyBounds.Linked(parts, LinkPaths.baseOf(from, target));
    }

    private final AssemblyBounds.Env boundsEnv = new AssemblyBounds.Env() {
        @Override
        public Vec3i size(ObjectNode o, Path base) {
            Path f = fileOf(o, base);
            return f == null ? null : sizeOfFile(f);
        }

        @Override
        public AssemblyBounds.Linked linked(AssemblyNode a, Path base) {
            return linkedParts(a, base);
        }
    };

    /** Sizes and linked parts for {@link AssemblyBounds}, with the rules of the editor. */
    public AssemblyBounds.Env boundsEnv() {
        return boundsEnv;
    }

    /** The size an array of this assembly works on (its parts union, without its own array), or null when nothing has a size. */
    public Vec3i assemblySize(AssemblyNode a) {
        return AssemblyBounds.frame(a, null, boundsEnv).size();
    }

    /** Reload from source: every linked file is read again. */
    public void reloadLinks() {
        lasms.clear();
        Reconciler.reloadLinks();
        invalidateBoxes();
    }

    /** Links a {@code .lasm} (an absolute path) into a parent as a linked assembly; shows a refusal as a toast. */
    public boolean linkAssembly(UUID parentId, Path lasm) {
        Node parent = scene().byId(parentId);
        ProjectNode proj = parent == null ? null : scene().projectOf(parent);
        if (proj == null) {
            toast(ToastQueue.Level.WARN, "Links go into a project: make one active first");
            return false;
        }
        Path target = lasm.toAbsolutePath().normalize();
        String name = target.getFileName().toString();
        if (name.toLowerCase().endsWith(ProjectLayout.ASSEMBLY_EXT)) name = name.substring(0, name.length() - ProjectLayout.ASSEMBLY_EXT.length());
        String link = LinkPaths.relativeLink(projectDir(proj), target);
        var cmd = new dev.vitruvius.editor.ops.LinkAssembly(parentId, -1, name, link, LinkPaths.identity(target), spawnBlock());
        return runLink(cmd, cmd.assemblyId(), name);
    }

    /** Links another project (its folder) into a parent as a linked assembly. */
    public boolean linkProject(UUID parentId, ProjectNode other) {
        Node parent = scene().byId(parentId);
        ProjectNode proj = parent == null ? null : scene().projectOf(parent);
        if (proj == null) {
            toast(ToastQueue.Level.WARN, "Links go into a project: make one active first");
            return false;
        }
        Path file = projectDir(other).resolve(ProjectLayout.PROJECT_FILE);
        String link = LinkPaths.relativeLink(projectDir(proj), file);
        var cmd = new dev.vitruvius.editor.ops.LinkProject(parentId, -1, other, link, spawnBlock());
        return runLink(cmd, cmd.assemblyId(), other.name());
    }

    private boolean runLink(dev.vitruvius.editor.ops.Command cmd, UUID id, String name) {
        if (!actions.execute(cmd)) return false;
        selection.set(id);
        Node n = scene().byId(id);
        if (n != null) tree.reveal(n);
        scrollToRow = id;
        toast(ToastQueue.Level.INFO, "Linked \"" + name + "\" at the camera target");
        return true;
    }

    /**
     * World block where newly added schematics/assemblies spawn: raycasts from the editor camera to the surface it
     * looks at (up to 256 blocks), falling back to a point in front of the camera clamped inside the world's build
     * height so objects never spawn underground or under the world.
     */
    public Vec3i spawnBlock() {
        EditorCamera cam = EditorCamera.get();
        Minecraft mc = Minecraft.getInstance();
        var level = mc == null ? null : mc.level;
        if (level != null) {
            var from = cam.position();
            var to = cam.target();
            var dir = to.subtract(from);
            double len = dir.length();
            if (len > 1e-4) {
                var fwd = dir.scale(1.0 / len);
                try {
                    var hit = level.clip(new net.minecraft.world.level.ClipContext(
                            from, from.add(fwd.scale(256.0)),
                            net.minecraft.world.level.ClipContext.Block.OUTLINE,
                            net.minecraft.world.level.ClipContext.Fluid.NONE,
                            net.minecraft.world.phys.shapes.CollisionContext.empty()));
                    if (hit != null && hit.getType() == net.minecraft.world.phys.HitResult.Type.BLOCK) {
                        var b = hit.getBlockPos().relative(hit.getDirection());
                        return new Vec3i(b.getX(), b.getY(), b.getZ());
                    }
                } catch (RuntimeException ignored) {
                    // fall back below
                }
                var p = len > 64.0 ? from.add(fwd.scale(16.0)) : to;
                int minY = level.getMinY();
                int maxY = level.getMaxY() - 1;
                int y = Math.max(minY, Math.min(maxY, (int) Math.floor(p.y)));
                return new Vec3i((int) Math.floor(p.x), y, (int) Math.floor(p.z));
            }
        }
        var pos = cam.target();
        return new Vec3i((int) Math.floor(pos.x), (int) Math.floor(pos.y), (int) Math.floor(pos.z));
    }

    /**
     * Open source: for a link to a project, opens that project (the editor shows it in the tree); for a {@code .lasm}
     * there is nothing to open, so a toast gives its path.
     */
    public void openSource(AssemblyNode a) {
        Path target = linkTarget(a, null);
        if (target == null) {
            toast(ToastQueue.Level.WARN, "\"" + a.name() + "\" is not linked");
            return;
        }
        if (target.getFileName().toString().equals(ProjectLayout.PROJECT_FILE)) {
            try {
                ProjectNode p = pm.open(target.getParent());
                selection.set(p.id());
                tree.reveal(p);
                scrollToRow = p.id();
                toast(ToastQueue.Level.INFO, "Opened project \"" + p.name() + "\"");
            } catch (IOException | RuntimeException e) {
                toast(ToastQueue.Level.ERROR, "Cannot open the project: " + e.getMessage());
            }
        } else {
            toast(ToastQueue.Level.INFO, "Source: " + target);
        }
    }

    /** The .lasm files the Add menu offers: below the active project folder, then below schematics/ (not in Projects). */
    public List<Path> listLasm() {
        List<Path> out = new ArrayList<>();
        ProjectNode ap = scene().activeProject();
        if (ap != null) collectLasm(projectDir(ap), 6, false, out);
        collectLasm(schematicsDir, 4, true, out);
        return out;
    }

    private void collectLasm(Path root, int depth, boolean skipProjects, List<Path> out) {
        try (var st = Files.walk(root, depth)) {
            st.filter(p -> p.getFileName().toString().toLowerCase().endsWith(ProjectLayout.ASSEMBLY_EXT)).forEach(p -> {
                String r = root.relativize(p).toString().replace('\\', '/');
                if (skipProjects && (r.startsWith(ProjectLayout.PROJECTS_DIR + "/") || r.startsWith(ZipExport.EXPORTS_DIR + "/"))) return;
                if (r.contains("/.") || r.startsWith(".")) return;
                Path abs = p.toAbsolutePath().normalize();
                if (!out.contains(abs)) out.add(abs);
            });
        } catch (IOException | RuntimeException ignored) {
            // unreadable folder: fewer entries
        }
    }


    // ------------------------------------------------------------------------------------------ sizes and boxes

    /** The file of an object as an absolute path (project files below the project folder, Loose below schematics). */
    public Path fileOf(ObjectNode o) {
        if (o.file().isEmpty()) return null;
        try {
            ProjectNode p = scene().projectOf(o);
            if (p != null) {
                Path dir = Path.of(p.path());
                if (!dir.isAbsolute()) dir = schematicsDir.resolve(dir);
                return dir.resolve(o.file()).normalize();
            }
            Path f = Path.of(o.file());
            return f.isAbsolute() ? f.normalize() : schematicsDir.resolve(f).normalize();
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** The enclosing size of an object's schematic, or null when it cannot be read. */
    public Vec3i sizeOf(ObjectNode o) {
        Path f = fileOf(o);
        return f == null ? null : sizeOfFile(f);
    }

    /** The enclosing size of a schematic file, or null when it cannot be read (a failed file is not parsed again for 3 s). */
    public Vec3i sizeOfFile(Path f) {
        long now = System.currentTimeMillis();
        Long until = unreadable.get(f);
        if (until != null && now < until) return null;          // a file that failed is not parsed again every frame
        Vec3i size = null;
        try {
            size = SchematicInfo.shared().size(f);
        } catch (RuntimeException e) {
            size = null;
        }
        if (size == null) unreadable.put(f, now + 3000);
        else unreadable.remove(f);
        return size;
    }

    /** The world boxes of the scene, recomputed at most every 100 ms and after every change. */
    public WorldBoxes boxes() {
        long now = System.nanoTime();
        if (boxes == null || boxesAt == 0 || now - boxesAt > 100_000_000L) {
            Scene sc = scene();
            boxes = new WorldBoxes(sc, o -> visibleInLocalView(o) ? sizeOf(o) : null, boundsEnv);
            boxesAt = now;
            computeOverlaps(sc);
        }
        return boxes;
    }

    /** Forces the next {@link #boxes()} call to recompute. */
    public void invalidateBoxes() {
        boxesAt = 0;
    }


    // ------------------------------------------------------------------------------------------ derived files, Boolean apply, eyedropper

    private UUID eyeObject;
    private int eyeMod = -1;
    private UUID applying;

    /** Arms the eyedropper for the Boolean modifier {@code index} of an object: the next click on an object in the world picks it. */
    public void startEyedrop(UUID objectId, int index) {
        gizmo.cancel();
        eyeObject = objectId;
        eyeMod = index;
    }

    public void cancelEyedrop() {
        eyeObject = null;
        eyeMod = -1;
    }

    public boolean eyedropping() {
        return eyeObject != null;
    }

    /** A click while the eyedropper is armed: the owner under the cursor becomes the cutter (a schematic object of the same project). */
    public void eyedropPick(UUID owner) {
        UUID obj = eyeObject;
        int idx = eyeMod;
        cancelEyedrop();
        Node target = owner == null ? null : scene().byId(owner);
        Node n = obj == null ? null : scene().byId(obj);
        if (!(n instanceof ObjectNode o) || idx < 0 || idx >= o.mods().size() || !(o.mods().get(idx) instanceof dev.vitruvius.editor.core.BooleanModifier bm)) return;
        if (!(target instanceof ObjectNode t)) {
            toast(ToastQueue.Level.WARN, target == null ? "Nothing there to pick" : "Pick a schematic object as the cutter, not an assembly");
            return;
        }
        if (t == o || scene().projectOf(t) != scene().projectOf(o)) {
            toast(ToastQueue.Level.WARN, "The cutter must be another object of the same project");
            return;
        }
        for (Node a = o; a != null; a = a.parent()) if (a == t) return;
        for (Node a = t.parent(); a != null; a = a.parent()) if (a == o) return;
        dev.vitruvius.editor.core.BooleanModifier c = bm.copy();
        c.targetId = t.id();
        actions.execute(new dev.vitruvius.editor.ops.ModifierEdits.EditModifier(o.id(), idx, "target", c));
        undoStack().breakMerge();
        toast(ToastQueue.Level.INFO, "Cutter: " + t.name());
    }

    /** The mask plan of an object as it is now (client thread). */
    public dev.vitruvius.editor.core.MaskPlan.Plan planOf(ObjectNode o, dev.vitruvius.editor.core.MaskPlan.Filter f) {
        return DerivedFiles.shared().planOf(scene(), o, f);
    }

    public boolean applying(UUID objectId) {
        return objectId.equals(applying);
    }

    /**
     * Apply Boolean: builds the file of this Boolean alone (the derived file of the plan with only this modifier; a
     * cache hit is immediate), then runs {@code ApplyBoolean}. The status bar and the button show the progress.
     */
    public void applyBoolean(ObjectNode o, int index) {
        if (applying != null) return;
        ProjectNode proj = scene().projectOf(o);
        Path source = fileOf(o);
        if (proj == null || source == null) {
            toast(ToastQueue.Level.WARN, "\"" + o.name() + "\" needs to be in a project to apply a Boolean");
            return;
        }
        var plan = planOf(o, dev.vitruvius.editor.core.MaskPlan.Filter.onlyBoolean(index));
        if (plan.isEmpty()) {
            toast(ToastQueue.Level.WARN, "The Boolean removes nothing, so there is nothing to apply");
            return;
        }
        UUID id = o.id();
        applying = id;
        ToastQueue.Toast progress = toast(ToastQueue.Level.INFO, "Building the cut for \"" + o.name() + "\"…");
        DerivedFiles.shared().build(plan, source, projectDir(proj), b -> {
            applying = null;
            if (!b.ok() || b.file() == null) {
                toast(ToastQueue.Level.ERROR, "Apply failed: " + (b.error() == null ? "no file" : b.error()));
                return;
            }
            Node now = scene().byId(id);
            if (!(now instanceof ObjectNode no) || index >= no.mods().size()) return;
            String rel = schematicsDir.relativize(b.file().toAbsolutePath().normalize()).toString().replace(java.io.File.separatorChar, '/');
            actions.transaction("Apply Boolean", List.of(new dev.vitruvius.editor.ops.ApplyBoolean(id, index, rel)));
            toast(ToastQueue.Level.INFO, "Applied: \"" + no.name() + "\" now uses " + no.file());
        });
    }

    /** Blocks the front-order trims take from this object, or -1 while unknown. */
    public long trimmedBlocks(UUID id) {
        DerivedFiles.Info i = DerivedFiles.shared().infoOf(id);
        return i == null || i.removed() < 0 ? -1 : i.removed() - i.booleanRemoved();
    }

    /** Blocks the Boolean modifiers remove from this object, or -1 while unknown. */
    public long booleanRemoved(UUID id) {
        DerivedFiles.Info i = DerivedFiles.shared().infoOf(id);
        return i == null ? -1 : i.booleanRemoved();
    }

    /** Blocks {@code loser} lost to {@code winner}, or -1 while unknown. */
    public long lostTo(UUID loser, UUID winner) {
        DerivedFiles.Info i = DerivedFiles.shared().infoOf(loser);
        if (i == null || i.removed() < 0) return -1;
        return i.trimBy().getOrDefault(winner, 0L);
    }
    private void computeOverlaps(Scene sc) {
        Set<UUID> ids = new HashSet<>();
        Map<UUID, List<Overlaps.Overlap>> by = new HashMap<>();
        List<Overlaps.Overlap> all = Overlaps.find(boxes.overlapItems());
        for (Overlaps.Overlap o : all) {
            DerivedFiles.Info li = DerivedFiles.shared().infoOf(o.loser());
            if (li == null || li.removed() != 0) ids.add(o.loser());   // the Issues list: owners that lose blocks (not known to lose none)
            by.computeIfAbsent(o.loser(), k -> new ArrayList<>()).add(o);
            by.computeIfAbsent(o.winner(), k -> new ArrayList<>()).add(o);
        }
        for (Node n : sc.allNodes()) {
            if (n instanceof ObjectNode o && (o.missing() || DerivedFiles.shared().infoOf(o.id()) != null && DerivedFiles.shared().infoOf(o.id()).status() == DerivedFiles.Status.FAILED)) ids.add(o.id());
        }
        issues = ids;
        overlapsByNode = by;
        overlapList = all;
    }

    /** Objects and assemblies that overlap another one, plus missing objects (the Issues chip). */
    public Set<UUID> issues() {
        boxes();
        return issues;
    }


    /** Every overlap box of the scene (loser and winner), for the overlay. */
    public List<Overlaps.Overlap> allOverlaps() {
        boxes();
        return overlapList;
    }
    public List<Overlaps.Overlap> overlapsOf(UUID id) {
        boxes();
        return overlapsByNode.getOrDefault(id, List.of());
    }

    // ------------------------------------------------------------------------------------------ config

    private void loadConfig() {
        try (InputStream in = Files.newInputStream(configFile)) {
            config.load(in);
        } catch (IOException ignored) {
            // no file yet
        }
    }

    private void saveConfig() {
        try {
            Files.createDirectories(configFile.getParent());
            try (OutputStream out = Files.newOutputStream(configFile)) {
                config.store(out, "Vitruvius editor UI");
            }
        } catch (IOException e) {
            LOG.debug("[vitruvius] cannot save the editor ui config", e);
        }
    }

    public boolean flag(String key, boolean def) {
        return Boolean.parseBoolean(config.getProperty(key, Boolean.toString(def)));
    }

    public void setFlag(String key, boolean value) {
        config.setProperty(key, Boolean.toString(value));
        saveConfig();
    }

    // ------------------------------------------------------------------------------------------ keys

    /**
     * An editor shortcut (DESIGN 12.5) outside text fields. {@code mods} are GLFW modifier bits. Returns true when
     * the key was used. Tree-only keys (arrows, Space, Enter) are handled by the Scene window.
     */
    public boolean onKey(int key, int mods) {
        boolean ctrl = (mods & (GLFW.GLFW_MOD_CONTROL | GLFW.GLFW_MOD_SUPER)) != 0;
        boolean shift = (mods & GLFW.GLFW_MOD_SHIFT) != 0, alt = (mods & GLFW.GLFW_MOD_ALT) != 0;
        if (dev.vitruvius.editor.view.SketchPad.onKey(key, ctrl, shift, alt)) return true;   // the sketchpad's own keys while it is in use
        if (boxEdit.active() && !ctrl && boxEdit.onKey(key, mods)) return true;   // edit mode: Esc, Enter, E, X, typed length
        if (gizmo.modal()) {                       // a gesture is running: its keys only (DESIGN 12.4)
            gizmo.onKey(key, mods);
            return true;
        }
        if (ctrl) {
            switch (key) {
                case GLFW.GLFW_KEY_Z -> {
                    if (shift) actions.redo();
                    else actions.undo();
                    return true;
                }
                case GLFW.GLFW_KEY_Y -> {
                    actions.redo();
                    return true;
                }
                case GLFW.GLFW_KEY_S -> {
                    pm.saveAll();
                    toast(ToastQueue.Level.INFO, "Saved all open projects");
                    return true;
                }
                case GLFW.GLFW_KEY_F -> {
                    focusFilter = true;
                    showScene = true;
                    return true;
                }
                case GLFW.GLFW_KEY_G -> {
                    actions.run("assembly.create");
                    return true;
                }
                default -> {
                    return false;
                }
            }
        }
        switch (key) {
            case GLFW.GLFW_KEY_W -> tool = Tool.SELECT;
            case GLFW.GLFW_KEY_G -> {
                tool = Tool.MOVE;
                if (!selection.isEmpty()) gizmo.startTypedMove(0);
            }
            case GLFW.GLFW_KEY_R -> {
                tool = Tool.ROTATE;
                if (!selection.isEmpty()) gizmo.startTypedRotate();
            }
            case GLFW.GLFW_KEY_M -> actions.run("t.mirror");
            case GLFW.GLFW_KEY_B -> gizmo.armBox();
            case GLFW.GLFW_KEY_E -> boxEdit.toggle();
            case GLFW.GLFW_KEY_A -> {
                if (shift) addMenuRequest = true;
                else actions.run(alt ? "select.none" : "select.all");
            }
            case GLFW.GLFW_KEY_H -> actions.run(alt ? "show.hidden" : "hide");
            case GLFW.GLFW_KEY_D -> {
                if (selection.isEmpty()) return false;
                if (shift) gizmo.startTypedMove(1);          // the copy starts as a typed move (the preview shows where it lands)
                else if (alt) gizmo.startTypedMove(2);
                else return false;
            }
            case GLFW.GLFW_KEY_X, GLFW.GLFW_KEY_DELETE -> {
                if (!selection.isEmpty()) actions.run("delete");
            }
            case GLFW.GLFW_KEY_PAGE_UP -> actions.run(shift ? "front.front" : "front.forward");
            case GLFW.GLFW_KEY_PAGE_DOWN -> actions.run(shift ? "front.back" : "front.backward");
            case GLFW.GLFW_KEY_F2 -> actions.run("rename");
            case GLFW.GLFW_KEY_TAB -> windowsHidden = !windowsHidden;
            case GLFW.GLFW_KEY_SLASH -> toggleLocalView();
            case GLFW.GLFW_KEY_F, GLFW.GLFW_KEY_KP_DECIMAL -> actions.run("frame");
            default -> {
                return false;
            }
        }
        return true;
    }

    // ------------------------------------------------------------------------------------------ host

    private final class HostImpl implements EditorActions.Host {
        @Override
        public Vec3i sizeOf(ObjectNode o) {
            return EditorSession.this.sizeOf(o);
        }

        @Override
        public Box boxOf(List<UUID> ids) {
            return boxes().unionOf(scene(), ids);
        }

        @Override
        public void frame(Box box) {
            Box b = box;
            if (b == null) {
                for (WorldBoxes.Owned o : boxes().owners().values()) b = b == null ? o.union() : b.union(o.union());
            }
            if (b == null) return;
            EditorCamera.get().frame(new AABB(b.min().x(), b.min().y(), b.min().z(), b.maxExclusive().x(), b.maxExclusive().y(), b.maxExclusive().z()));
        }

        @Override
        public void toast(ToastQueue.Level level, String text, String actionLabel, Runnable action) {
            toasts.add(level, text, actionLabel, action);
        }

        @Override
        public void beginRename(UUID id) {
            renameRequest = id;
            scrollToRow = id;
            showScene = true;
            windowsHidden = false;
        }

        @Override
        public void requestAddShape(UUID parentId) {
            dev.vitruvius.editor.view.ShapeWindow.open(EditorSession.this, parentId);
        }

        @Override
        public void requestAddSchematic(UUID parentId) {
            addSchematicParent = parentId;
        }

        @Override
        public void focusFilter() {
            focusFilter = true;
        }

        @Override
        public void syncPush(ProjectNode p) {
            dev.vitruvius.editor.sync.SyncService.get().push(p);
        }

        @Override
        public void syncShare(ProjectNode p) {
            var svc = dev.vitruvius.editor.sync.SyncService.get();
            if (p.sync() == ProjectNode.Sync.SHARED) {
                svc.stopSharing(p);
                toast(ToastQueue.Level.INFO, "Stopped tracking \"" + p.name() + "\". Its placements stay on the server; remove them in Syncmatica.s list.", null, null);
            } else {
                svc.share(p);
                toast(ToastQueue.Level.INFO, "\"" + p.name() + "\" is shared. Nothing is uploaded until you press Push.", null, null);
            }
        }

        @Override
        public Vec3i cameraTarget() {
            var t = dev.vitruvius.editor.cam.EditorCamera.get().target();
            return new Vec3i((int) Math.floor(t.x), (int) Math.floor(t.y), (int) Math.floor(t.z));
        }

        @Override
        public void setActive(ProjectNode p) {
            pm.setActive(p.id());
        }

        @Override
        public boolean closeProject(ProjectNode p) {
            boolean ok = pm.close(p.id());
            if (ok) {
                undo.clear();
                EditorSession.this.toast(ToastQueue.Level.INFO, "Closed \"" + p.name() + "\" (saved)");
            }
            return ok;
        }

        @Override
        public void showInExplorer(ProjectNode p) {
            try {
                Path dir = Path.of(p.path());
                if (!dir.isAbsolute()) dir = schematicsDir.resolve(dir);
                String os = System.getProperty("os.name", "").toLowerCase();
                String[] cmd = os.contains("win") ? new String[]{"explorer", dir.toString()}
                        : os.contains("mac") ? new String[]{"open", dir.toString()} : new String[]{"xdg-open", dir.toString()};
                new ProcessBuilder(cmd).start();
            } catch (RuntimeException | java.io.IOException e) {
                EditorSession.this.toast(ToastQueue.Level.WARN, "Cannot open the folder: " + e.getMessage());
            }
        }

        @Override
        public void saveAll() {
            pm.saveAll();
        }

        @Override
        public void toggleWindows() {
            windowsHidden = !windowsHidden;
        }

        @Override
        public Vec3i pivot(List<UUID> ids) {
            return pivotOf(ids);
        }

        @Override
        public void startMove() {
            tool = Tool.MOVE;
            gizmo.startTypedMove(0);
        }

        @Override
        public void startBoxSelect() {
            gizmo.armBox();
        }

        @Override
        public void toggleLocalView() {
            EditorSession.this.toggleLocalView();
        }


        @Override
        public Vec3i sizeOfAssembly(AssemblyNode a) {
            return assemblySize(a);
        }

        @Override
        public void requestAddAssembly(UUID parentId) {
            addAssemblyParent = parentId;
        }

        @Override
        public void requestLinkProject(UUID parentId) {
            linkProjectParent = parentId;
        }

        @Override
        public boolean linkProject(UUID parentId, ProjectNode project) {
            return EditorSession.this.linkProject(parentId, project);
        }

        @Override
        public void reloadLinks() {
            EditorSession.this.reloadLinks();
        }

        @Override
        public void openSource(AssemblyNode a) {
            EditorSession.this.openSource(a);
        }

        @Override
        public void applyBoolean(UUID objectId, int index) {
            Node n = scene().byId(objectId);
            if (n instanceof ObjectNode o) EditorSession.this.applyBoolean(o, index);
        }

        @Override
        public void export(String kind, List<Node> nodes) {
            Node one = nodes.size() == 1 ? nodes.get(0) : null;
            switch (kind) {
                case "zip" -> {
                    if (one instanceof AssemblyNode a) exports.exportAssembly(a);
                    else if (one instanceof dev.vitruvius.editor.core.FolderNode f) exports.exportFolder(f);
                    else if (one instanceof ProjectNode p) exports.exportProject(p);
                    else exports.exportSelection(nodes);
                }
                case "bake" -> {
                    if (one instanceof ProjectNode p) exports.bakeProject(p);
                    else exports.bake(nodes, one != null ? one.name() : "Bake");
                }
                case "manhattan" -> exports.buildJob(nodes, one != null ? one.name() : nodes.size() + " objects");
                case "litematic" -> {
                    if (one instanceof ObjectNode o) exports.exportLitematic(o);
                    else EditorSession.this.toast(ToastQueue.Level.INFO, "Select one object to export its schematic");
                }
                default -> { }
            }
        }
        @Override
        public void showInTree(UUID id) {
            Node n = scene().byId(id);
            if (n != null) tree.reveal(n);
            scrollToRow = id;
        }
    }
}
