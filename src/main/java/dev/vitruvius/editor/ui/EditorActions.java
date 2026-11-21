package dev.vitruvius.editor.ui;

import dev.vitruvius.editor.core.ArrayModifier;
import dev.vitruvius.editor.core.AssemblyNode;
import dev.vitruvius.editor.core.BooleanModifier;
import dev.vitruvius.editor.core.Box;
import dev.vitruvius.editor.core.FolderNode;
import dev.vitruvius.editor.core.LooseNode;
import dev.vitruvius.editor.core.Node;
import dev.vitruvius.editor.core.ObjectNode;
import dev.vitruvius.editor.core.ProjectNode;
import dev.vitruvius.editor.core.Scene;
import dev.vitruvius.editor.core.Vec3i;
import dev.vitruvius.editor.ops.Command;
import dev.vitruvius.editor.ops.ConvertFolderToProject;
import dev.vitruvius.editor.ops.CreateAssembly;
import dev.vitruvius.editor.ops.Delete;
import dev.vitruvius.editor.ops.Duplicate;
import dev.vitruvius.editor.ops.LinkedDuplicate;
import dev.vitruvius.editor.ops.MakeLocal;
import dev.vitruvius.editor.ops.BreakUp;
import dev.vitruvius.editor.ops.MirrorX;
import dev.vitruvius.editor.ops.ModifierEdits;
import dev.vitruvius.editor.ops.MoveToProject;
import dev.vitruvius.editor.ops.NewFolder;
import dev.vitruvius.editor.ops.RealiseArray;
import dev.vitruvius.editor.ops.Refusal;
import dev.vitruvius.editor.ops.Rotate;
import dev.vitruvius.editor.ops.SaveAsLinked;
import dev.vitruvius.editor.ops.SetFront;
import dev.vitruvius.editor.ops.SetHidden;
import dev.vitruvius.editor.ops.UndoStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Runs menu entries, keys and buttons against the current selection. Entry ids are those of {@link MenuModel}.
 * Pure: everything that needs the game (sizes, the camera, dialogs) goes through {@link Host}.
 *
 * <p>Every refusal of a command becomes a warning toast; deleting and the like offer Undo in the toast.
 */
public final class EditorActions {
    /** What the actions need from the game side. */
    public interface Host {
        /** The enclosing size of an object's schematic, or null when unknown. */
        Vec3i sizeOf(ObjectNode o);

        /** The world box around these nodes (their visible objects), or null. */
        Box boxOf(List<UUID> ids);

        /** Frames a box with the camera; null frames everything. */
        void frame(Box box);

        void toast(ToastQueue.Level level, String text, String actionLabel, Runnable action);

        /** Starts the inline rename of a row. */
        void beginRename(UUID id);

        /** Opens the file picker for a new schematic under this parent. */
        void requestAddSchematic(UUID parentId);

        /** Opens the shape generator window; its result is added under this parent. */
        default void requestAddShape(UUID parentId) {}

        void focusFilter();

        /** Pushes a shared project to Syncmatica. */
        default void syncPush(ProjectNode p) {}

        /** Starts or stops sharing a project on Syncmatica. */
        default void syncShare(ProjectNode p) {}

        /** The block the camera orbits (where new objects of other mods are put); null when unknown. */
        default Vec3i cameraTarget() {
            return null;
        }

        /** Makes a project the active one. */
        void setActive(ProjectNode p);

        /** Closes a project (saves it first). */
        boolean closeProject(ProjectNode p);

        /** Opens a project folder in the system file explorer. */
        void showInExplorer(ProjectNode p);

        /** Saves every open project. */
        void saveAll();

        void toggleWindows();

        void showInTree(UUID id);

        /** The pivot block of a selection under the current pivot mode, or null to use the selection's middle block. */
        default Vec3i pivot(List<UUID> ids) {
            return null;
        }

        /** G: starts a typed move of the selection. */
        default void startMove() {}

        /** B: arms box select. */
        default void startBoxSelect() {}

        /** Toggles local view. */
        default void toggleLocalView() {}

        /** The size an array of an assembly works on (its parts' union, without its own array), or null when unknown. */
        default Vec3i sizeOfAssembly(AssemblyNode a) {
            return null;
        }

        /** Opens the picker of .lasm files to link under this parent. */
        default void requestAddAssembly(UUID parentId) {}

        /** Opens the picker of projects to link under this parent. */
        default void requestLinkProject(UUID parentId) {}

        /** Links the project into the parent as an assembly; false when refused (the host shows why). */
        default boolean linkProject(UUID parentId, ProjectNode project) {
            return false;
        }

        /** Reload from source: reads every linked file again. */
        default void reloadLinks() {}

        /** Open source of a linked assembly. */
        default void openSource(AssemblyNode a) {}

        /** An export: {@code kind} is "zip" (the right zip for the selection), "bake" or "litematic" (one object's file). */
        default void export(String kind, List<Node> nodes) {}

        /** Apply the Boolean modifier at {@code index} of an object (builds its file first when needed). */
        default void applyBoolean(UUID objectId, int index) {}
    }

    private final Supplier<UndoStack> stack;
    private final SelectionModel sel;
    private final Host host;

    public EditorActions(Supplier<UndoStack> stack, SelectionModel sel, Host host) {
        this.stack = stack;
        this.sel = sel;
        this.host = host;
    }

    private Scene scene() {
        return stack.get().context().scene();
    }

    private List<UUID> ids() {
        return sel.ids();
    }

    private List<Node> nodes() {
        List<Node> out = new ArrayList<>();
        for (UUID id : sel.ids()) {
            Node n = scene().byId(id);
            if (n != null) out.add(n);
        }
        return out;
    }

    // ------------------------------------------------------------------------------------------ queries

    /** Which entry ids {@link #run} handles (the tests check every enabled menu entry is in it). */
    public static boolean handles(String id) {
        if (id.startsWith("move.to:") || id.startsWith("add.ext:")) return true;
        return switch (id) {
            case "add.schematic", "add.shape", "add.folder", "select.all", "select.none", "select.contents", "frame.all", "frame",
                 "show.hidden", "undo", "redo", "duplicate", "dup.linked", "rename", "t.rot.cw", "t.rot.ccw", "t.mirror",
                 "t.mirrorz", "mod.array", "mod.boolean", "mod.realise", "mod.apply", "front.front", "front.forward", "front.backward",
                 "front.back", "assembly.create", "hide", "delete", "asm.makelocal", "asm.breakup", "part.select.owner",
                 "t.move", "box", "local", "local.leave",
                 "part.makelocal", "folder.toproject", "loose.toproject", "proj.active", "proj.explorer", "proj.close",
                 "add.assembly", "add.link", "proj.link", "asm.realise", "asm.reload", "asm.open", "asm.savelasm", "assembly.createlinked",
                 "export.zip", "export.bake", "export.schem", "proj.push", "proj.share" -> true;
            default -> false;
        };
    }

    /** Why this entry cannot run now (asks the command), or empty. */
    public Optional<String> refusal(String id) {
        boolean chain = (id.equals("asm.breakup") || id.equals("asm.realise")) && single(AssemblyNode.class).filter(AssemblyNode::isLinked).isPresent();
        Optional<Command> c = build(chain ? "asm.makelocal" : id);      // a linked assembly is made local first, in the same step
        if (c.isEmpty()) return Optional.empty();
        return c.get().check(stack.get().context()).map(Refusal::reason);
    }

    // ------------------------------------------------------------------------------------------ commands

    private Vec3i pivot() {
        Vec3i chosen = host.pivot(ids());
        if (chosen != null) return chosen;
        Box b = host.boxOf(ids());
        if (b == null) return Vec3i.ZERO;
        Vec3i s = b.size();
        return new Vec3i(b.min().x() + (s.x() - 1) / 2, b.min().y(), b.min().z() + (s.z() - 1) / 2);
    }

    private Vec3i dupOffset() {
        Box b = host.boxOf(ids());
        return b == null ? new Vec3i(1, 0, 0) : new Vec3i(b.size().x(), 0, 0);
    }

    private Node addParent() {
        List<Node> n = nodes();
        if (n.size() == 1 && (n.get(0) instanceof ProjectNode || n.get(0) instanceof FolderNode) && !TreeModel.lockedPart(n.get(0))) return n.get(0);
        if (n.size() == 1 && n.get(0) instanceof LooseNode) return n.get(0);
        ProjectNode ap = scene().activeProject();
        return ap != null ? ap : scene().loose();
    }

    private Optional<ObjectNode> booleanTarget(Node n) {
        ProjectNode p = scene().projectOf(n);
        for (Node x : scene().allNodes()) {
            if (!(x instanceof ObjectNode o) || o == n || scene().projectOf(o) != p) continue;
            boolean inside = false;
            for (Node a = n; a != null; a = a.parent()) if (a == o) inside = true;
            for (Node a = o.parent(); a != null; a = a.parent()) if (a == n) inside = true;
            if (!inside) return Optional.of(o);
        }
        return Optional.empty();
    }

    /** The command an entry runs for the current selection; empty for entries that are not one command. */
    public Optional<Command> build(String id) {
        List<UUID> ids = ids();
        if (id.startsWith("move.to:")) {
            UUID target = UUID.fromString(id.substring("move.to:".length()));
            Node t = scene().byId(target);
            boolean same = t != null && !nodes().isEmpty() && nodes().stream().allMatch(n -> scene().projectOf(n) == scene().projectOf(t));
            return Optional.of(same ? (Command) new dev.vitruvius.editor.ops.Reparent(ids, target, -1) : (Command) new MoveToProject(ids, target, -1));
        }
        if (id.startsWith("add.ext:")) {
            dev.vitruvius.api.SceneObjectType t = dev.vitruvius.api.ObjectTypes.get(id.substring("add.ext:".length()));
            if (t == null) return Optional.empty();
            Vec3i at = host.cameraTarget();
            return Optional.of(new dev.vitruvius.editor.ops.AddExternal(addParent().id(), -1, t.id(), t.displayName(), t.create(), at == null ? Vec3i.ZERO : at));
        }
        return switch (id) {
            case "duplicate" -> Optional.of(new Duplicate(ids, dupOffset()));
            case "dup.linked" -> Optional.of(new LinkedDuplicate(ids, dupOffset()));
            case "t.rot.cw" -> Optional.of(new Rotate(ids, pivot(), 1));
            case "t.rot.ccw" -> Optional.of(new Rotate(ids, pivot(), -1));
            case "t.mirror" -> Optional.of(new MirrorX(ids, pivot()));
            case "front.front" -> Optional.of(SetFront.front(ids));
            case "front.forward" -> Optional.of(SetFront.forward(ids));
            case "front.backward" -> Optional.of(SetFront.backward(ids));
            case "front.back" -> Optional.of(SetFront.back(ids));
            case "assembly.create" -> Optional.of(new CreateAssembly(ids, "Assembly", null));
            case "hide" -> {
                boolean allHidden = !nodes().isEmpty() && nodes().stream().allMatch(Node::hidden);
                yield Optional.of(new SetHidden(ids, !allHidden));
            }
            case "show.hidden" -> Optional.of(new SetHidden(hiddenIds(), false));
            case "delete" -> Optional.of(new Delete(ids));
            case "add.folder" -> Optional.of(new NewFolder(addParent().id(), "Folder", -1));
            case "asm.makelocal" -> single(AssemblyNode.class).map(a -> new MakeLocal(a.id()));
            case "asm.breakup" -> single(AssemblyNode.class).map(a -> new BreakUp(a.id(), sizeOfNode(a)));
            case "asm.realise" -> single(AssemblyNode.class).map(a -> new BreakUp(a.id(), sizeOfNode(a), true));
            case "asm.savelasm" -> single(AssemblyNode.class).map(a -> new SaveAsLinked(a.id(), null));
            case "part.makelocal" -> single(Node.class).map(Node::id).map(scene()::byId).map(scene()::ownerOf).map(o -> new MakeLocal(o.id()));
            case "mod.realise" -> single(Node.class).map(n -> new RealiseArray(n.id(), sizeOfNode(n)));
            case "folder.toproject" -> single(FolderNode.class).map(f -> new ConvertFolderToProject(f.id(), null));
            case "loose.toproject" -> single(LooseNode.class).map(l -> new ConvertFolderToProject(l.id(), "Loose placements"));
            default -> Optional.empty();
        };
    }

private static boolean hasArray(Node n) {        List<dev.vitruvius.editor.core.Modifier> mods = n instanceof ObjectNode o ? o.mods() : n instanceof AssemblyNode a ? a.mods() : n instanceof dev.vitruvius.editor.core.ExternalNode x ? x.mods() : List.of();        for (dev.vitruvius.editor.core.Modifier m : mods) if (m instanceof ArrayModifier) return true;        return false;    }
    private <T extends Node> Optional<T> single(Class<T> type) {
        List<Node> n = nodes();
        if (n.size() != 1 || !type.isInstance(n.get(0))) return Optional.empty();
        return Optional.of(type.cast(n.get(0)));
    }

    private Vec3i sizeOfNode(Node n) {
        if (n instanceof ObjectNode o) {
            Vec3i s = host.sizeOf(o);
            return s == null ? new Vec3i(1, 1, 1) : s;
        }
        if (n instanceof AssemblyNode a) {
            Vec3i s = host.sizeOfAssembly(a);
            if (s != null) return s;
        }
        Box b = host.boxOf(List.of(n.id()));
        return b == null ? new Vec3i(1, 1, 1) : b.size();
    }

    private List<UUID> hiddenIds() {
        List<UUID> out = new ArrayList<>();
        for (Node n : scene().allNodes()) if (n.hidden()) out.add(n.id());
        return out;
    }

    // ------------------------------------------------------------------------------------------ running

    /** Executes a command, shows a refusal as a toast. Returns true when it ran. */
    public boolean execute(Command c) {
        UndoStack s = stack.get();
        Optional<Refusal> r;
        try {
            r = s.execute(c);
        } catch (RuntimeException e) {
            // the command rolled itself back; an exception here would escape mid-frame and break the ImGui stack
            failed(e);
            return false;
        }
        if (r.isPresent()) {
            host.toast(ToastQueue.Level.WARN, r.get().reason(), null, null);
            return false;
        }
        return true;
    }

    /** Runs a group of commands as one undo step; a refusal rolls the group back and is shown as a toast. */
    public boolean transaction(String label, List<Command> commands) {
        UndoStack s = stack.get();
        s.begin(label);
        for (Command c : commands) {
            Optional<Refusal> r;
            try {
                r = s.execute(c);
            } catch (RuntimeException e) {
                s.rollback();
                failed(e);
                return false;
            }
            if (r.isPresent()) {
                s.rollback();
                host.toast(ToastQueue.Level.WARN, r.get().reason(), null, null);
                return false;
            }
        }
        s.commit();
        return true;
    }

    private void failed(RuntimeException e) {
        org.slf4j.LoggerFactory.getLogger("vitruvius").error("Editor command failed", e);
        String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        host.toast(ToastQueue.Level.ERROR, "That did not work: " + msg, null, null);
    }

    /** Runs one entry or shortcut action. Returns true when something happened. */
    public boolean run(String id) {
        if (!handles(id)) {
            host.toast(ToastQueue.Level.INFO, MenuModel.LATER, null, null);
            return false;
        }
        Scene scene = scene();
        List<Node> nodes = nodes();
        switch (id) {
            case "undo":
                return undo();
            case "redo":
                return redo();
            case "select.all": {
                List<UUID> all = new ArrayList<>();
                for (Node n : scene.allNodes()) {
                    if ((n instanceof ObjectNode || n instanceof AssemblyNode) && scene.ownerOf(n) == n && !TreeModel.effectivelyHidden(n)) all.add(n.id());
                }
                sel.setAll(all);
                return true;
            }
            case "select.none":
                sel.clear();
                return true;
            case "select.contents": {
                List<UUID> all = new ArrayList<>();
                for (Node n : nodes) for (Node x : n.subtree()) if (x != n && (x instanceof ObjectNode || x instanceof AssemblyNode) && scene.ownerOf(x) == x) all.add(x.id());
                if (!all.isEmpty()) sel.setAll(all);
                return true;
            }
            case "frame": {
                Box b = host.boxOf(ids());
                if (b == null && nodes.isEmpty()) b = null;
                host.frame(b);
                return true;
            }
            case "frame.all":
                host.frame(null);
                return true;
            case "rename":
                if (sel.active() != null) host.beginRename(sel.active());
                return sel.active() != null;
            case "t.move":
                host.startMove();
                return true;
            case "box":
                host.startBoxSelect();
                return true;
            case "local":
            case "local.leave":
                host.toggleLocalView();
                return true;
            case "add.assembly":
                host.requestAddAssembly(addParent().id());
                return true;
            case "add.link":
                host.requestLinkProject(addParent().id());
                return true;
            case "proj.link": {
                ProjectNode active = scene.activeProject();
                if (active == null) {
                    host.toast(ToastQueue.Level.WARN, "There is no active project to link into", null, null);
                    return false;
                }
                return single(ProjectNode.class).map(p -> host.linkProject(active.id(), p)).orElse(false);
            }
            case "asm.reload":
                host.reloadLinks();
                host.toast(ToastQueue.Level.INFO, "Reloaded the linked files", null, null);
                return true;
            case "asm.open":
                return single(AssemblyNode.class).map(a -> {
                    host.openSource(a);
                    return true;
                }).orElse(false);
            case "export.zip":
                host.export("zip", nodes);
                return true;
            case "export.bake":
                host.export("bake", nodes);
                return true;
            case "export.schem":
                host.export("litematic", nodes);
                return true;
            case "add.schematic":
                host.requestAddSchematic(addParent().id());
                return true;
            case "add.shape":
                host.requestAddShape(addParent().id());
                return true;
            case "proj.push":
                return single(ProjectNode.class).map(p -> {
                    host.syncPush(p);
                    return true;
                }).orElse(false);
            case "proj.share":
                return single(ProjectNode.class).map(p -> {
                    host.syncShare(p);
                    return true;
                }).orElse(false);
            case "part.select.owner": {
                if (nodes.size() == 1) {
                    Node owner = scene.ownerOf(nodes.get(0));
                    sel.set(owner.id());
                    host.showInTree(owner.id());
                }
                return true;
            }
            case "proj.active":
                return single(ProjectNode.class).map(p -> {
                    host.setActive(p);
                    return true;
                }).orElse(false);
            case "proj.explorer":
                return single(ProjectNode.class).map(p -> {
                    host.showInExplorer(p);
                    return true;
                }).orElse(false);
            case "proj.close":
                return single(ProjectNode.class).map(p -> {
                    boolean ok = host.closeProject(p);
                    if (ok) sel.clear();
                    return ok;
                }).orElse(false);
            default:
                break;
        }
        return runCommand(id, nodes);
    }

    private boolean runCommand(String id, List<Node> nodes) {
        UndoStack s = stack.get();
        switch (id) {
            case "asm.breakup":
            case "asm.realise": {
                Optional<AssemblyNode> asm = single(AssemblyNode.class);
                if (asm.isEmpty() || !asm.get().isLinked()) break;          // a local assembly is one command (below)
                AssemblyNode a = asm.get();
                boolean realise = id.equals("asm.realise");
                // a linked assembly: make it local (its files are copied into the project, so they are its own), then break it up
                boolean ok = transaction((realise ? "Realise " : "Break up ") + a.name(),
                        List.of(new MakeLocal(a.id()), new BreakUp(a.id(), sizeOfNode(a), false)));
                if (ok) host.toast(ToastQueue.Level.INFO, (realise ? "Realised " : "Broke up ") + "\"" + a.name() + "\"", "Undo", this::undo);
                return ok;
            }
            case "assembly.createlinked": {
                CreateAssembly create = new CreateAssembly(ids(), "Assembly", null);
                Optional<Refusal> first = create.check(s.context());
                if (first.isPresent()) {
                    host.toast(ToastQueue.Level.WARN, first.get().reason(), null, null);
                    return false;
                }
                SaveAsLinked save = new SaveAsLinked(create.assemblyId(), null);
                boolean ok = transaction("Create linked assembly", List.of(create, save));
                if (ok) {
                    sel.set(create.assemblyId());
                    host.showInTree(create.assemblyId());
                    host.toast(ToastQueue.Level.INFO, "Saved as " + save.lasmPath() + " and linked", "Undo", this::undo);
                }
                return ok;
            }
            case "t.mirrorz": {
                Vec3i p = pivot();
                return transaction("Mirror Z", List.of(new MirrorX(ids(), p), new Rotate(ids(), p, 2)));
            }
            case "mod.array": {
                List<Command> cs = new ArrayList<>();
                for (Node n : nodes) {
                    if (hasArray(n)) {
                        host.toast(ToastQueue.Level.WARN, "\"" + n.name() + "\" has an array already", null, null);
                        return false;
                    }
                    cs.add(new ModifierEdits.AddModifier(n.id(), new ArrayModifier(), -1));
                }
                return runEach("Add Array", cs);
            }
            case "mod.apply": {
                if (nodes.size() != 1 || !(nodes.get(0) instanceof ObjectNode o)) return false;
                for (int i = 0; i < o.mods().size(); i++) {
                    if (o.mods().get(i) instanceof BooleanModifier) {
                        host.applyBoolean(o.id(), i);
                        return true;
                    }
                }
                host.toast(ToastQueue.Level.WARN, "\"" + o.name() + "\" has no Boolean to apply", null, null);
                return false;
            }
            case "mod.boolean": {
                List<Command> cs = new ArrayList<>();
                for (Node n : nodes) {
                    Optional<ObjectNode> t = booleanTarget(n);
                    if (t.isEmpty()) {
                        host.toast(ToastQueue.Level.WARN, "There is no other object in this project to cut with", null, null);
                        return false;
                    }
                    cs.add(new ModifierEdits.AddModifier(n.id(), new BooleanModifier(BooleanModifier.Op.DIFFERENCE, t.get().id()), -1));
                }
                return runEach("Add Boolean", cs);
            }
            default:
                break;
        }
        Optional<Command> c = build(id);
        if (c.isEmpty()) return false;
        boolean ok;
        Command cmd = c.get();
        if (cmd instanceof Duplicate d) {
            ok = execute(d);
            if (ok) {
                List<UUID> created = d.createdIds();
                if (!created.isEmpty()) {
                    sel.setAll(created);
                    host.showInTree(created.get(created.size() - 1));
                }
            }
            return ok;
        }
        if (cmd instanceof Delete) {
            String what = nodes.size() == 1 ? "\"" + nodes.get(0).name() + "\"" : nodes.size() + " nodes";
            ok = execute(cmd);
            if (ok) {
                sel.clear();
                host.toast(ToastQueue.Level.INFO, "Deleted " + what, "Undo", this::undo);
            }
            return ok;
        }
        if (cmd instanceof dev.vitruvius.editor.ops.AddExternal ae) {
            ok = execute(ae);
            if (ok) {
                sel.set(ae.nodeId());
                host.showInTree(ae.nodeId());
            }
            return ok;
        }
        if (cmd instanceof NewFolder nf) {
            ok = execute(nf);
            if (ok) {
                sel.set(nf.folderId());
                host.showInTree(nf.folderId());
                host.beginRename(nf.folderId());
            }
            return ok;
        }
        if (cmd instanceof ConvertFolderToProject cp) {
            ok = execute(cp);
            if (ok) {
                sel.set(cp.projectId());
                host.toast(ToastQueue.Level.INFO, "Converted to a project", "Undo", this::undo);
            }
            return ok;
        }
        if (cmd instanceof MoveToProject) {
            ok = execute(cmd);
            if (ok) host.toast(ToastQueue.Level.INFO, "Moved. Files are moved, or copied when other objects still use them", "Undo", this::undo);
            return ok;
        }
        if (cmd instanceof RealiseArray || cmd instanceof BreakUp || cmd instanceof MakeLocal || cmd instanceof SaveAsLinked) {
            ok = execute(cmd);
            if (ok) host.toast(ToastQueue.Level.INFO, cmd instanceof SaveAsLinked sv ? "Saved as " + sv.lasmPath() + " and linked" : cmd.label(), "Undo", this::undo);
            return ok;
        }
        return execute(cmd);
    }

    private boolean runEach(String label, List<Command> cs) {
        if (cs.isEmpty()) return false;
        if (cs.size() == 1) return execute(cs.get(0));
        return transaction(label, cs);
    }

    /** Turns the selection by quarter turns about its centre (the rotation segments in Properties). */
    public boolean rotate(int quarterTurns) {
        return execute(new Rotate(ids(), pivot(), quarterTurns));
    }

    /** Moves the selection by a delta (Location fields and steppers; consecutive moves merge into one undo step). */
    public boolean move(Vec3i delta) {
        return execute(new dev.vitruvius.editor.ops.Move(ids(), delta));
    }

    /** Undo; a toast says there is nothing to undo when the stack is empty. */
    public boolean undo() {
        UndoStack s = stack.get();
        if (!s.undo()) {
            host.toast(ToastQueue.Level.INFO, "Nothing to undo", null, null);
            return false;
        }
        sel.prune(id -> scene().byId(id) != null);
        return true;
    }

    public boolean redo() {
        UndoStack s = stack.get();
        if (!s.redo()) {
            host.toast(ToastQueue.Level.INFO, "Nothing to redo", null, null);
            return false;
        }
        sel.prune(id -> scene().byId(id) != null);
        return true;
    }
}
