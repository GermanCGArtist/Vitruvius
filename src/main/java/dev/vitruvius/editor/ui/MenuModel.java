package dev.vitruvius.editor.ui;

import dev.vitruvius.editor.core.ArrayModifier;
import dev.vitruvius.editor.core.AssemblyNode;
import dev.vitruvius.editor.core.BooleanModifier;
import dev.vitruvius.editor.core.ExternalNode;
import dev.vitruvius.editor.core.FolderNode;
import dev.vitruvius.editor.core.HasFront;
import dev.vitruvius.editor.core.LooseNode;
import dev.vitruvius.editor.core.Modifier;
import dev.vitruvius.editor.core.Node;
import dev.vitruvius.editor.core.ObjectNode;
import dev.vitruvius.editor.core.ProjectNode;
import dev.vitruvius.editor.core.Scene;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The context menus of DESIGN section 10 as data: which entries a menu has for a selection, and which of them are
 * enabled. The window code only draws what this returns and runs the entry's id through {@link EditorActions}.
 *
 * <p>Rules (DESIGN 10): an entry that never applies to the kind of item is hidden (it is not in the list at all);
 * one that exists but cannot run now is {@link State#DISABLED}; features that are not built yet are disabled with
 * the tip {@link #LATER}.
 */
public final class MenuModel {
    private MenuModel() {}

    public static final String LATER = "Coming in a later 2.0 alpha";

    /** What the menus need to know about Syncmatica (set by the sync service; by default it is not installed). */
    public interface SyncHook {
        /** The mod is present: the Push and Share entries show at all. */
        boolean installed();

        /** Its API was found: Share and Push can work. */
        boolean usable();

        /** There is a Syncmatica server connection right now. */
        boolean connected();

        /** Why Push or Share cannot run now (shown as a tip), else empty. */
        String reason();
    }

    private static volatile SyncHook sync = new SyncHook() {
        @Override
        public boolean installed() {
            return false;
        }

        @Override
        public boolean usable() {
            return false;
        }

        @Override
        public boolean connected() {
            return false;
        }

        @Override
        public String reason() {
            return "Syncmatica is not installed";
        }
    };

    public static void setSyncHook(SyncHook hook) {
        if (hook != null) sync = hook;
    }

    public static SyncHook syncHook() {
        return sync;
    }
    public static final String SEP = "-";

    public enum State { ENABLED, DISABLED }

    /**
     * One entry. {@code id} is what {@link EditorActions#run} understands; {@link #SEP} for a separator and
     * {@code "header"} for the title line. {@code children} makes it a submenu. {@code tip} explains a disabled entry.
     */
    public record Item(String id, String label, String shortcut, String icon, State state, boolean danger, String tip, List<Item> children) {
        public boolean enabled() {
            return state == State.ENABLED;
        }

        public boolean separator() {
            return SEP.equals(id);
        }

        public boolean header() {
            return "header".equals(id);
        }

        public boolean submenu() {
            return !children.isEmpty();
        }
    }

    /** What the menus depend on. */
    public record Ctx(Scene scene, List<Node> selection, String undoLabel, String redoLabel, boolean hasHidden, boolean localView) {
        public Ctx {
            selection = List.copyOf(selection);
        }
    }

    // ------------------------------------------------------------------------------------------ builders

    private static Item sep() {
        return new Item(SEP, "", "", "", State.ENABLED, false, "", List.of());
    }

    private static Item header(String text) {
        return new Item("header", text.toUpperCase(Locale.ROOT), "", "", State.ENABLED, false, "", List.of());
    }

    private static Item item(String id, String label, String shortcut, String icon) {
        return new Item(id, label, shortcut, icon, State.ENABLED, false, "", List.of());
    }

    private static Item item(String id, String label, String shortcut, String icon, boolean enabled, String tip) {
        return new Item(id, label, shortcut, icon, enabled ? State.ENABLED : State.DISABLED, false, enabled ? "" : tip, List.of());
    }

    private static Item later(String id, String label, String shortcut, String icon) {
        return new Item(id, label, shortcut, icon, State.DISABLED, false, LATER, List.of());
    }

    private static Item danger(String id, String label, String shortcut) {
        return new Item(id, label, shortcut, "trash", State.ENABLED, true, "", List.of());
    }

    private static Item sub(String label, String icon, List<Item> children) {
        return sub(label, icon, "", children);
    }

    private static Item sub(String label, String icon, String shortcut, List<Item> children) {
        return new Item("sub:" + label, label, shortcut, icon, State.ENABLED, false, "", List.copyOf(children));
    }

    private static List<Item> clean(List<Item> in) {
        List<Item> out = new ArrayList<>();
        for (Item i : in) {
            if (i == null) continue;
            if (i.separator() && (out.isEmpty() || out.get(out.size() - 1).separator())) continue;
            out.add(i);
        }
        while (!out.isEmpty() && out.get(out.size() - 1).separator()) out.remove(out.size() - 1);
        return out;
    }

    // ------------------------------------------------------------------------------------------ viewport

    /** The "Add" submenu items for a parent (the active project, a folder, or Loose). */
    public static List<Item> addItems(boolean canAdd, String whyNot) {
        List<Item> out = new ArrayList<>();
        out.add(item("add.schematic", "Schematic…", "", "object", canAdd, whyNot));
        out.add(item("add.assembly", "Assembly (.lasm)…", "", "assembly", canAdd, whyNot));
        out.add(item("add.link", "Link project as assembly…", "", "link", canAdd, whyNot));
        for (dev.vitruvius.api.SceneObjectType t : dev.vitruvius.api.ObjectTypes.all()) {      // objects of other mods (API 2)
            out.add(item("add.ext:" + t.id(), t.displayName(), "", "areas", canAdd, whyNot));
        }
        out.add(item("add.folder", "Folder", "", "folder", canAdd, whyNot));
        return out;
    }

    /** Empty space in the viewport (10.1). */
    public static List<Item> viewport(Ctx c) {
        ProjectNode ap = c.scene().activeProject();
        List<Item> m = new ArrayList<>();
        m.add(header(ap == null ? "Add" : "Add to " + ap.name()));
        m.add(sub("Add", "plus", "Shift A", addItems(true, "")));
        m.add(sep());
        m.add(item("select.all", "Select all", "A", ""));
        m.add(item("select.none", "Select none", "Alt A", "", !c.selection().isEmpty(), "Nothing is selected"));
        m.add(item("box", "Box select", "B", "box"));
        m.add(sep());
        m.add(item("frame.all", "Frame all", "F", "frame"));
        m.add(item("show.hidden", "Show hidden", "Alt H", "eye", c.hasHidden(), "Nothing is hidden"));
        if (c.localView()) m.add(item("local.leave", "Leave local view", "/", "local"));
        m.add(sep());
        m.add(item("undo", c.undoLabel() == null ? "Undo" : "Undo " + c.undoLabel(), "Ctrl Z", "undo", c.undoLabel() != null, "Nothing to undo"));
        m.add(item("redo", c.redoLabel() == null ? "Redo" : "Redo " + c.redoLabel(), "Ctrl Shift Z", "redo", c.redoLabel() != null, "Nothing to redo"));
        return clean(m);
    }

    // ------------------------------------------------------------------------------------------ nodes

    /** The menu for what was right-clicked, by the kind of the selection (10.2 to 10.8). */
    public static List<Item> forSelection(Ctx c) {
        List<Node> sel = c.selection();
        if (sel.isEmpty()) return viewport(c);
        Node first = sel.get(0);
        if (sel.size() == 1) {
            if (first instanceof ProjectNode p) return project(c, p);
            if (first instanceof LooseNode) return loose(c);
            if (first instanceof FolderNode f) return folder(c, f);
            if (TreeModel.lockedPart(first)) return lockedPart(c, first);
            if (first instanceof AssemblyNode a) return assembly(c, a);
        }
        for (Node n : sel) {
            if (TreeModel.lockedPart(n)) return lockedPart(c, n);
        }
        return objects(c);
    }

    private static boolean hasMod(Node n, Class<? extends Modifier> type) {
        List<Modifier> mods = n instanceof ObjectNode o ? o.mods() : n instanceof AssemblyNode a ? a.mods() : n instanceof ExternalNode x ? x.mods() : List.of();
        for (Modifier m : mods) if (type.isInstance(m)) return true;
        return false;
    }

    private static String title(List<Node> sel) {
        return sel.size() == 1 ? sel.get(0).name() : sel.size() + " objects";
    }

    private static boolean allHidden(List<Node> sel) {
        for (Node n : sel) if (!n.hidden()) return false;
        return true;
    }

    private static List<Item> transform(boolean single) {
        return List.of(
                item("t.move", "Move", "G", "move"),
                item("t.rot.cw", "Rotate +90°", "", "rotate"),
                item("t.rot.ccw", "Rotate −90°", "", "rotate"),
                item("t.mirror", "Mirror X", "M", "mirror"),
                item("t.mirrorz", "Mirror Z (= X + 180°)", "", "mirror"));
    }

    private static List<Item> frontOrder(Node n, boolean several) {
        String label = "Front order";
        if (!several && n instanceof HasFront f && f.front() > 0) label += " (P" + f.front() + ")";
        return List.of(sub(label, "front", List.of(
                item("front.front", "Bring to front", "Shift PgUp", "front"),
                item("front.forward", "Bring forward", "PgUp", "front"),
                item("front.backward", "Send backward", "PgDn", "back"),
                item("front.back", "Send to back", "Shift PgDn", "back"))));
    }

    /** "Move to" entries: every open project and its folders; the current parent is disabled. */
    private static Item moveTo(Scene scene, List<Node> sel) {
        List<Item> items = new ArrayList<>();
        for (ProjectNode p : scene.projects()) addMoveTargets(p, p.name(), sel, items);
        if (items.isEmpty()) return new Item("sub:Move to", "Move to", "", "", State.DISABLED, false, "No project is open", List.of());
        return sub("Move to", "", items);
    }

    private static void addMoveTargets(Node target, String label, List<Node> sel, List<Item> out) {
        boolean here = true;
        for (Node n : sel) if (n.parent() != target) here = false;
        out.add(item("move.to:" + target.id(), label, "", target instanceof ProjectNode ? "project" : "folder", !here, "Already here"));
        for (Node ch : target.children()) {
            if (ch instanceof FolderNode f && !TreeModel.lockedPart(f)) addMoveTargets(f, label + " / " + f.name(), sel, out);
        }
    }

    /** One or several objects, mixed with assemblies or areas (10.2, 10.3). */
    private static List<Item> objects(Ctx c) {
        List<Node> sel = c.selection();
        boolean single = sel.size() == 1;
        Node first = sel.get(0);
        boolean area = true, cutter = single && first instanceof ObjectNode o && o.wire();
        for (Node n : sel) if (!(n instanceof ExternalNode)) area = false;
        boolean hasArray = false, hasBool = false;
        for (Node n : sel) {
            hasArray |= hasMod(n, ArrayModifier.class);
            hasBool |= hasMod(n, BooleanModifier.class);
        }
        List<Item> m = new ArrayList<>();
        m.add(header(title(sel)));
        m.add(item("duplicate", "Duplicate", "Shift D", "dup"));
        m.add(item("dup.linked", "Linked duplicate", "Alt D", "link"));
        if (single) m.add(item("rename", "Rename", "F2", ""));
        m.add(sep());
        m.add(sub("Transform", "move", transform(single)));
        if (!area) {
            List<Item> mods = new ArrayList<>();
            mods.add(item("mod.array", "Add Array", "", "array", !hasArray, "It has an array already"));
            mods.add(item("mod.boolean", "Add Boolean", "", "cutter", !cutter, "A cutter cannot have a Boolean"));
            if (single && hasArray) {
                mods.add(sep());
                mods.add(item("mod.realise", "Realise array", "", "realise"));
            }
            if (single && hasBool) {
                if (!hasArray) mods.add(sep());
                mods.add(item("mod.apply", "Apply Boolean → .litematic", "", "cutter"));
            }
            m.add(sub("Modifiers", "array", mods));
        } else {
            m.add(sub("Modifiers", "array", List.of(item("mod.array", "Add Array", "", "array", !hasArray, "It has an array already"))));
        }
        if (!area && !cutter) m.addAll(frontOrder(first, !single));
        m.add(item("assembly.create", "Create assembly from selection", "Ctrl G", "group"));
        m.add(item("assembly.createlinked", "Create linked assembly (.lasm)", "", "link"));
        m.add(moveTo(c.scene(), sel));
        m.add(sep());
        m.add(item("hide", allHidden(sel) ? "Show" : "Hide", "H", allHidden(sel) ? "eye" : "eyeoff"));
        m.add(item("local", "Local view", "/", "local"));
        m.add(item("frame", "Frame selected", "F", "frame"));
        if (!area) {
            m.add(sep());
            m.add(sub("Export", "export", single
                    ? List.of(item("export.schem", "Schematic (.litematic)", "", "export"), item("export.bake", "Baked with modifiers (.litematic)", "", "export"))
                    : List.of(item("export.zip", "Selection (.zip)", "", "export"), item("export.bake", "Bake into one .litematic", "", "export"))));
        }
        m.add(sep());
        m.add(danger("delete", "Delete", "X"));
        return clean(m);
    }

    /** An assembly instance (10.4). */
    private static List<Item> assembly(Ctx c, AssemblyNode a) {
        boolean linked = a.isLinked();
        List<Item> m = new ArrayList<>();
        m.add(header(linked ? a.name() + " · linked" : a.name()));
        m.add(item("duplicate", "Duplicate", "Shift D", "dup"));
        m.add(item("dup.linked", "Linked duplicate", "Alt D", "link"));
        m.add(item("rename", "Rename", "F2", ""));
        m.add(sep());
        m.add(sub("Transform", "move", transform(true)));
        List<Item> amods = new ArrayList<>();
        amods.add(item("mod.array", "Add Array", "", "array", !hasMod(a, ArrayModifier.class), "It has an array already"));
        amods.add(item("mod.boolean", "Add Boolean", "", "cutter"));
        if (hasMod(a, ArrayModifier.class)) {
            amods.add(sep());
            amods.add(item("mod.realise", "Realise array", "", "realise"));
        }
        m.add(sub("Modifiers", "array", amods));
        m.addAll(frontOrder(a, false));
        List<Item> asm = new ArrayList<>();
        if (linked) asm.add(item("asm.makelocal", "Make local (copy files)", "", "local"));
        asm.add(item("asm.breakup", "Break up in place", "", "explode"));
        asm.add(item("asm.realise", "Realise (independent copies)", "", "realise"));
        if (linked) {
            asm.add(item("asm.reload", "Reload from source", "", "sync"));
            asm.add(item("asm.open", "Open source", "", "project"));
        } else {
            asm.add(item("asm.savelasm", "Save as linked .lasm", "", "export"));
        }
        m.add(sub("Assembly", "assembly", asm));
        m.add(sep());
        m.add(item("hide", a.hidden() ? "Show" : "Hide", "H", a.hidden() ? "eye" : "eyeoff"));
        m.add(item("local", "Local view", "/", "local"));
        m.add(item("frame", "Frame selected", "F", "frame"));
        m.add(sep());
        m.add(sub("Export", "export", List.of(item("export.zip", "Assembly (.zip)", "", "export"), item("export.bake", "Bake to one .litematic", "", "export"))));
        m.add(sep());
        m.add(danger("delete", "Delete", "X"));
        return clean(m);
    }

    /** A part of a linked assembly (10.5). */
    private static List<Item> lockedPart(Ctx c, Node part) {
        Node owner = c.scene().ownerOf(part);
        String ownerName = owner == null ? "assembly" : owner.name();
        List<Item> m = new ArrayList<>();
        m.add(header(part.name() + " · in " + ownerName + " (linked)"));
        m.add(item("part.select.owner", "Select assembly", "", "assembly"));
        m.add(item("part.makelocal", "Make assembly local", "", "local"));
        m.add(sep());
        m.add(item("frame", "Frame selected", "F", "frame"));
        return clean(m);
    }

    /** A folder (10.6). */
    private static List<Item> folder(Ctx c, FolderNode f) {
        boolean ro = TreeModel.lockedPart(f);
        List<Item> m = new ArrayList<>();
        m.add(header(f.name()));
        m.add(item("add.folder", "New folder", "", "folder", !ro, "Read-only"));
        m.add(item("rename", "Rename", "F2", "", !ro, "Read-only"));
        m.add(item("select.contents", "Select contents", "", "", !f.children().isEmpty(), "The folder is empty"));
        m.add(item("hide", f.hidden() ? "Show" : "Hide", "H", f.hidden() ? "eye" : "eyeoff"));
        m.add(sep());
        m.add(item("assembly.create", "Create assembly from folder", "Ctrl G", "group", !f.children().isEmpty(), "The folder is empty"));
        m.add(item("folder.toproject", "Convert to project", "", "project", !ro, "Read-only"));
        m.add(sep());
        m.add(item("export.zip", "Export folder (.zip)", "", "export", !f.children().isEmpty(), "The folder is empty"));
        m.add(sep());
        m.add(danger("delete", "Delete folder", "X"));
        return clean(m);
    }

    /** A project (10.7). */
    private static List<Item> project(Ctx c, ProjectNode p) {
        List<Item> m = new ArrayList<>();
        m.add(header(p.name()));
        m.add(item("proj.active", "Make active project", "", "active", !p.active(), "It is the active project"));
        m.add(item("rename", "Rename", "F2", ""));
        m.add(item("add.folder", "New folder", "", "folder"));
        m.add(sub("Add", "plus", addItems(true, "")));
        if (!p.active()) m.add(item("proj.link", "Link into active project as assembly", "", "link", c.scene().activeProject() != null, "There is no active project"));
        m.add(sep());
        SyncHook sh = sync;
        if (sh.installed()) {                                  // hidden without the mod (DESIGN 10.7)
            boolean shared = p.sync() == ProjectNode.Sync.SHARED;
            if (shared) m.add(item("proj.push", "Push changes to Syncmatica", "", "sync", sh.usable() && sh.connected(), sh.reason().isEmpty() ? "Not connected" : sh.reason()));
            m.add(item("proj.share", shared ? "Stop sharing" : "Share on Syncmatica", "", "sync", shared || sh.usable(), sh.reason().isEmpty() ? "Syncmatica is not usable" : sh.reason()));
            m.add(sep());
        }
        m.add(sub("Export", "export", List.of(item("export.zip", "Project (.zip)", "", "export"), item("export.bake", "Bake to one .litematic", "", "export"))));
        m.add(item("proj.explorer", "Show in file explorer", "", ""));
        m.add(sep());
        m.add(item("proj.close", "Close project", "", "close"));
        return clean(m);
    }

    /** The Loose node (10.8). */
    private static List<Item> loose(Ctx c) {
        List<Item> m = new ArrayList<>();
        m.add(header("Loose placements"));
        m.add(item("select.contents", "Select contents", "", "", !c.selection().get(0).children().isEmpty(), "Nothing is loose"));
        m.add(item("show.hidden", "Show hidden", "Alt H", "eye", c.hasHidden(), "Nothing is hidden"));
        m.add(sep());
        m.add(item("loose.toproject", "Convert to project…", "", "project", !c.selection().get(0).children().isEmpty(), "Nothing is loose"));
        return clean(m);
    }

    /** All ids of the entries in a menu, submenus included (tests and the action table use it). */
    public static List<Item> flatten(List<Item> menu) {
        List<Item> out = new ArrayList<>();
        for (Item i : menu) {
            out.add(i);
            out.addAll(flatten(i.children()));
        }
        return out;
    }
}
