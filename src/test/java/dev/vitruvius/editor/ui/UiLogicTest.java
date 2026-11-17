package dev.vitruvius.editor.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vitruvius.editor.core.ArrayModifier;
import dev.vitruvius.editor.core.AssemblyNode;
import dev.vitruvius.editor.core.FolderNode;
import dev.vitruvius.editor.core.Node;
import dev.vitruvius.editor.core.ObjectNode;
import dev.vitruvius.editor.core.ProjectNode;
import dev.vitruvius.editor.core.Vec3i;
import dev.vitruvius.editor.ui.MenuModel.Item;
import dev.vitruvius.editor.ui.TreeModel.Chip;
import dev.vitruvius.editor.ui.TreeModel.Row;
import dev.vitruvius.editor.ui.TreeRules.Zone;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.UUID;

class UiLogicTest {
    // ------------------------------------------------------------------ selection

    @Test
    void selectionClickToggleRange() {
        UiFx fx = new UiFx();
        ObjectNode a = fx.obj(fx.metro, "a", 0, 0, 0), b = fx.obj(fx.metro, "b", 5, 0, 0), c = fx.obj(fx.metro, "c", 10, 0, 0),
                d = fx.obj(fx.metro, "d", 15, 0, 0);
        List<UUID> order = List.of(a.id(), b.id(), c.id(), d.id());
        SelectionModel s = fx.sel;
        s.set(a.id());
        assertEquals(a.id(), s.active());
        s.toggle(c.id());
        assertEquals(List.of(a.id(), c.id()), s.ids());
        assertEquals(c.id(), s.active());
        s.toggle(c.id());                       // off again: the active one falls back to what is left
        assertEquals(List.of(a.id()), s.ids());
        assertEquals(a.id(), s.active());
        s.range(order, c.id());                 // Ctrl click: a..c
        assertEquals(Set.of(a.id(), b.id(), c.id()), Set.copyOf(s.ids()));
        assertEquals(c.id(), s.active());
        s.range(order, a.id());                 // a range back from c keeps what was selected
        assertEquals(3, s.size());
        s.set(d.id());
        s.range(order, a.id());
        assertEquals(4, s.size());
        s.prune(id -> !id.equals(b.id()));
        assertFalse(s.isSelected(b.id()));
        s.clear();
        assertTrue(s.isEmpty());
        assertNull(s.active());
        s.range(order, b.id());                 // no active row: a plain click
        assertEquals(List.of(b.id()), s.ids());
    }

    // ------------------------------------------------------------------ tree drag rules

    @Test
    void zonesFollowQuarterRule() {
        assertEquals(Zone.BEFORE, TreeRules.zone(0.1f, true));
        assertEquals(Zone.INTO, TreeRules.zone(0.5f, true));
        assertEquals(Zone.AFTER, TreeRules.zone(0.9f, true));
        assertEquals(Zone.BEFORE, TreeRules.zone(0.4f, false));
        assertEquals(Zone.AFTER, TreeRules.zone(0.6f, false));
    }

    @Test
    void dropReorderReparentAndRefusals() {
        UiFx fx = new UiFx();
        FolderNode f = fx.folder(fx.metro, "Tunnels");
        ObjectNode a = fx.obj(fx.metro, "a", 0, 0, 0), b = fx.obj(fx.metro, "b", 5, 0, 0);
        TreeRules.Plan into = TreeRules.plan(fx.ctx, List.of(a.id()), f, Zone.INTO);
        assertTrue(into.valid(), into.refusal());
        assertEquals(f, into.drop().parent());
        TreeRules.Plan after = TreeRules.plan(fx.ctx, List.of(a.id()), b, Zone.AFTER);
        assertTrue(after.valid());
        assertEquals(3, after.drop().index());
        assertNull(fx.stack.execute(after.command()).orElse(null));
        assertEquals(List.of(b, a), List.copyOf(fx.metro.children().subList(1, 3)).stream().map(n -> (Node) n).toList());

        // a folder into itself
        TreeRules.Plan self = TreeRules.plan(fx.ctx, List.of(f.id()), f, Zone.INTO);
        assertFalse(self.valid());
        assertNotNull(self.refusal());
        // a project row has no before/after
        assertFalse(TreeRules.plan(fx.ctx, List.of(a.id()), fx.metro, Zone.BEFORE).valid());
        // a project into a folder
        ProjectNode other = fx.project("Other", "w");
        assertFalse(TreeRules.plan(fx.ctx, List.of(other.id()), f, Zone.INTO).valid());
        // into a linked assembly
        AssemblyNode linked = fx.asm(fx.metro, "Station", 0, 0, 0);
        linked.setSource(new AssemblyNode.Link("station.lasm"));
        TreeRules.Plan intoLinked = TreeRules.plan(fx.ctx, List.of(b.id()), linked, Zone.INTO);
        assertFalse(intoLinked.valid());
        assertTrue(intoLinked.refusal().toLowerCase().contains("linked"));
        assertFalse(TreeRules.canContainRow(linked));
        assertTrue(TreeRules.canContainRow(f));
    }

    @Test
    void dropOnAnotherProjectIsAMove() {
        UiFx fx = new UiFx();
        ObjectNode a = fx.obj(fx.metro, "a", 0, 0, 0);
        ProjectNode other = fx.project("Other", "w");
        TreeRules.Plan p = TreeRules.plan(fx.ctx, List.of(a.id()), other, Zone.INTO);
        assertTrue(p.valid(), p.refusal());
        assertNull(fx.stack.execute(p.command()).orElse(null));
        assertEquals(1, other.children().size());
        assertTrue(fx.stack.undo());
        assertEquals(1, fx.metro.children().size());
    }

    // ------------------------------------------------------------------ tree rows

    @Test
    void rowsListProjectsThenLooseAndHonourExpansion() {
        UiFx fx = new UiFx();
        FolderNode f = fx.folder(fx.metro, "Tunnels");
        fx.obj(f, "inner", 0, 0, 0);
        fx.obj(fx.scene.loose(), "stray", 0, 0, 0);
        TreeModel m = new TreeModel();
        List<Row> rows = m.rows(fx.scene, Set.of());
        assertEquals(List.of("Metro", "Tunnels", "inner", "Loose", "stray"), rows.stream().map(r -> r.node().name()).toList());
        m.setOpen(f, false);
        rows = m.rows(fx.scene, Set.of());
        assertEquals(List.of("Metro", "Tunnels", "Loose", "stray"), rows.stream().map(r -> r.node().name()).toList());
        assertFalse(rows.get(1).open());
        assertTrue(rows.get(1).hasChildren());
    }

    @Test
    void searchAndChipsFilterWithAncestors() {
        UiFx fx = new UiFx();
        FolderNode f = fx.folder(fx.metro, "Tunnels");
        fx.obj(f, "vent_shaft", 0, 0, 0);
        fx.obj(fx.metro, "roof", 0, 0, 0);
        fx.asm(fx.metro, "Station", 0, 0, 0);
        TreeModel m = new TreeModel();
        m.setSearch("VENT");
        List<Row> rows = m.rows(fx.scene, Set.of());
        assertEquals(List.of("Metro", "Tunnels", "vent_shaft"), rows.stream().map(r -> r.node().name()).toList());
        assertTrue(rows.get(0).ancestorOnly());
        assertTrue(rows.get(1).ancestorOnly());
        assertFalse(rows.get(2).ancestorOnly());
        assertEquals(0, rows.get(2).matchStart());
        assertEquals(4, rows.get(2).matchLen());
        m.setSearch("");
        m.setChip(Chip.ASSEMBLIES);
        rows = m.rows(fx.scene, Set.of());
        assertEquals(List.of("Metro", "Station"), rows.stream().map(r -> r.node().name()).toList());
    }

    @Test
    void issuesChipIsFlatAndSortedByFront() {
        UiFx fx = new UiFx();
        ObjectNode a = fx.obj(fx.metro, "a", 0, 0, 0), b = fx.obj(fx.metro, "b", 0, 0, 0);
        a.setFront(2);
        b.setFront(1);
        TreeModel m = new TreeModel();
        m.setChip(Chip.ISSUES);
        List<Row> rows = m.rows(fx.scene, Set.of(a.id(), b.id()));
        assertEquals(List.of("b", "a"), rows.stream().map(r -> r.node().name()).toList());
        assertEquals("Metro", rows.get(0).projectName());
        assertEquals(0, rows.get(0).depth());
    }

    @Test
    void lockedAndHiddenRows() {
        UiFx fx = new UiFx();
        AssemblyNode linked = fx.asm(fx.metro, "Station", 0, 0, 0);
        linked.setSource(new AssemblyNode.Link("station.lasm"));
        ObjectNode part = fx.obj(linked, "roof", 0, 0, 0);
        fx.metro.setHidden(true);
        TreeModel m = new TreeModel();
        m.setOpen(linked, true);
        List<Row> rows = m.rows(fx.scene, Set.of());
        Row r = rows.stream().filter(x -> x.node() == part).findFirst().orElseThrow();
        assertTrue(r.locked());
        assertTrue(r.hidden());
        assertFalse(rows.stream().filter(x -> x.node() == linked).findFirst().orElseThrow().locked());
    }

    // ------------------------------------------------------------------ menus

    private static Item find(List<Item> menu, String id) {
        return MenuModel.flatten(menu).stream().filter(i -> i.id().equals(id)).findFirst().orElse(null);
    }

    @Test
    void objectMenuHidesAndDisablesPerKind() {
        UiFx fx = new UiFx();
        ObjectNode o = fx.obj(fx.metro, "tunnel", 0, 0, 0);
        List<Item> menu = MenuModel.forSelection(fx.menuCtx(o));
        assertEquals("TUNNEL", menu.get(0).label());
        assertTrue(find(menu, "mod.array").enabled());
        assertNull(find(menu, "mod.realise"), "no realise without an array");
        assertTrue(find(menu, "front.front").enabled());
        assertNull(find(menu, "asm.breakup"));
        assertTrue(find(menu, "t.move").enabled());
        assertTrue(find(menu, "delete").danger());

        o.setMods(List.of(new ArrayModifier()));
        menu = MenuModel.forSelection(fx.menuCtx(o));
        assertFalse(find(menu, "mod.array").enabled(), "one array only");
        assertTrue(find(menu, "mod.realise").enabled());

        o.setWire(true);
        menu = MenuModel.forSelection(fx.menuCtx(o));
        assertNull(find(menu, "front.front"), "cutters have no front order");
        assertFalse(find(menu, "mod.boolean").enabled());

        ObjectNode p = fx.obj(fx.metro, "pipe", 5, 0, 0);
        menu = MenuModel.forSelection(fx.menuCtx(o, p));
        assertEquals("2 OBJECTS", menu.get(0).label());
        assertNull(find(menu, "rename"));
    }

    @Test
    void assemblyFolderProjectLooseAndLockedMenus() {
        UiFx fx = new UiFx();
        AssemblyNode local = fx.asm(fx.metro, "Local", 0, 0, 0);
        List<Item> menu = MenuModel.forSelection(fx.menuCtx(local));
        assertNull(find(menu, "asm.makelocal"), "make local never applies to a local assembly");
        assertNotNull(find(menu, "asm.breakup"));
        assertNotNull(find(menu, "asm.savelasm"));

        AssemblyNode linked = fx.asm(fx.metro, "Station", 0, 0, 0);
        linked.setSource(new AssemblyNode.Link("station.lasm"));
        ObjectNode part = fx.obj(linked, "roof", 0, 0, 0);
        menu = MenuModel.forSelection(fx.menuCtx(linked));
        assertTrue(menu.get(0).label().endsWith("LINKED"));
        assertNotNull(find(menu, "asm.makelocal"));
        assertNull(find(menu, "asm.savelasm"));
        menu = MenuModel.forSelection(fx.menuCtx(part));
        assertEquals(List.of("header", "part.select.owner", "part.makelocal", "-", "frame"), menu.stream().map(Item::id).toList());

        FolderNode f = fx.folder(fx.metro, "Tunnels");
        menu = MenuModel.forSelection(fx.menuCtx(f));
        assertFalse(find(menu, "select.contents").enabled(), "empty folder");
        assertNotNull(find(menu, "folder.toproject"));

        menu = MenuModel.forSelection(fx.menuCtx(fx.metro));
        assertFalse(find(menu, "proj.active").enabled(), "the first project is active");
        assertNull(find(menu, "proj.link"), "hidden for the active project");
        assertNull(find(menu, "proj.push"));
        ProjectNode other = fx.project("Other", "w");
        menu = MenuModel.forSelection(fx.menuCtx(other));
        assertTrue(find(menu, "proj.active").enabled());
        assertNotNull(find(menu, "proj.link"));

        menu = MenuModel.forSelection(fx.menuCtx(fx.scene.loose()));
        assertNotNull(find(menu, "loose.toproject"));
        assertFalse(find(menu, "loose.toproject").enabled());
    }

    @Test
    void viewportMenuReflectsUndoAndHidden() {
        UiFx fx = new UiFx();
        List<Item> menu = MenuModel.viewport(new MenuModel.Ctx(fx.scene, List.of(), null, null, false, false));
        assertEquals("ADD TO METRO", menu.get(0).label());
        assertFalse(find(menu, "undo").enabled());
        assertFalse(find(menu, "show.hidden").enabled());
        assertNull(find(menu, "local.leave"));
        menu = MenuModel.viewport(new MenuModel.Ctx(fx.scene, List.of(), "Move tunnel", null, true, true));
        assertEquals("Undo Move tunnel", find(menu, "undo").label());
        assertTrue(find(menu, "show.hidden").enabled());
        assertNotNull(find(menu, "local.leave"));
    }

    @Test
    void everyEnabledMenuEntryIsWiredAndTheRestSaysLater() {
        UiFx fx = new UiFx();
        ObjectNode o = fx.obj(fx.metro, "tunnel", 0, 0, 0);
        o.setMods(List.of(new ArrayModifier()));
        AssemblyNode local = fx.asm(fx.metro, "Local", 0, 0, 0);
        AssemblyNode linked = fx.asm(fx.metro, "Station", 5, 0, 0);
        linked.setSource(new AssemblyNode.Link("station.lasm"));
        ObjectNode part = fx.obj(linked, "roof", 0, 0, 0);
        FolderNode f = fx.folder(fx.metro, "Tunnels");
        fx.obj(f, "inner", 0, 0, 0);
        fx.obj(fx.scene.loose(), "stray", 0, 0, 0);
        ProjectNode other = fx.project("Other", "w");
        List<List<Item>> menus = List.of(
                MenuModel.viewport(fx.menuCtx()),
                MenuModel.forSelection(fx.menuCtx(o)),
                MenuModel.forSelection(fx.menuCtx(o, local)),
                MenuModel.forSelection(fx.menuCtx(local)),
                MenuModel.forSelection(fx.menuCtx(linked)),
                MenuModel.forSelection(fx.menuCtx(part)),
                MenuModel.forSelection(fx.menuCtx(f)),
                MenuModel.forSelection(fx.menuCtx(fx.metro)),
                MenuModel.forSelection(fx.menuCtx(other)),
                MenuModel.forSelection(fx.menuCtx(fx.scene.loose())));
        for (List<Item> menu : menus) {
            for (Item i : MenuModel.flatten(menu)) {
                if (i.separator() || i.header() || i.submenu()) continue;
                if (i.enabled()) assertTrue(EditorActions.handles(i.id()), "not wired: " + i.id());
                else if (i.tip().isEmpty()) throw new AssertionError("disabled without a tip: " + i.id());
                if (!EditorActions.handles(i.id())) {
                    assertFalse(i.enabled(), i.id());
                    assertEquals(MenuModel.LATER, i.tip(), i.id());
                }
            }
        }
    }

    // ------------------------------------------------------------------ actions

    @Test
    void actionsRunCommandsAndShowRefusals() {
        UiFx fx = new UiFx();
        ObjectNode a = fx.obj(fx.metro, "a", 0, 0, 0), b = fx.obj(fx.metro, "b", 10, 0, 0);
        fx.sel.setAll(List.of(a.id(), b.id()));
        assertTrue(fx.actions.run("hide"));
        assertTrue(a.hidden() && b.hidden());
        assertTrue(fx.actions.run("show.hidden"));
        assertFalse(a.hidden());

        fx.sel.set(a.id());
        assertTrue(fx.actions.run("t.rot.cw"));
        assertEquals(1, a.transform().rot().steps());
        assertTrue(fx.actions.run("t.mirrorz"));
        int depth = fx.stack.undoSize();
        assertTrue(fx.stack.undo(), "mirror Z is one undo step");
        assertEquals(depth - 1, fx.stack.undoSize());

        assertTrue(fx.actions.run("duplicate"));
        assertEquals(1, fx.sel.size(), "the copy is selected");
        assertEquals(3, fx.metro.children().size());

        assertTrue(fx.actions.run("mod.array"));
        assertFalse(fx.actions.run("mod.array"), "a second array is refused");
        assertTrue(fx.toasts.stream().anyMatch(t -> t.startsWith("WARN:")));
        assertTrue(fx.toasts.stream().anyMatch(t -> t.contains("array already")));

        fx.sel.set(b.id());
        assertTrue(fx.actions.run("delete"));
        assertNull(fx.scene.byId(b.id()));
        assertTrue(fx.sel.isEmpty());
        assertTrue(fx.actions.undo());
        assertNotNull(fx.scene.byId(b.id()));
    }

    @Test
    void frontOrderCreateAssemblyAndMoveTo() {
        UiFx fx = new UiFx();
        ObjectNode a = fx.obj(fx.metro, "a", 0, 0, 0), b = fx.obj(fx.metro, "b", 10, 0, 0);
        fx.sel.set(b.id());
        assertTrue(fx.actions.run("front.front"));
        assertEquals(1, b.front());
        fx.sel.setAll(List.of(a.id(), b.id()));
        assertTrue(fx.actions.run("assembly.create"));
        assertEquals(1, fx.metro.children().size());
        assertTrue(fx.metro.children().get(0) instanceof AssemblyNode);
        FolderNode f = fx.folder(fx.metro, "Sub");
        fx.sel.set(fx.metro.children().get(0).id());
        assertTrue(fx.actions.run("move.to:" + f.id()));
        assertEquals(1, f.children().size());
        assertFalse(fx.actions.run("add.area"), "not built: toast, no change");
    }

    @Test
    void frameUsesTheSelectionBox() {
        UiFx fx = new UiFx();
        ObjectNode a = fx.obj(fx.metro, "a", 3, 0, 0);
        fx.sel.set(a.id());
        assertTrue(fx.actions.run("frame"));
        assertTrue(fx.framed);
        assertEquals(new Vec3i(3, 0, 0), fx.frameBox.min());
        fx.sel.clear();
        fx.actions.run("frame.all");
        assertNull(fx.frameBox);
    }

    // ------------------------------------------------------------------ toasts

    @Test
    void toastQueueLevelsPauseAndFade() {
        ToastQueue q = new ToastQueue();
        ToastQueue.Toast info = q.add(ToastQueue.Level.INFO, "one");
        q.tick(0, null);
        q.tick(3000, null);
        assertEquals(1, q.toasts().size());
        q.tick(3000 + 700, null);                       // 3.6 s are over: fading
        assertTrue(info.alpha() < 1f);
        q.tick(7000, null);
        assertTrue(q.toasts().isEmpty());

        ToastQueue.Toast warn = q.add(ToastQueue.Level.WARN, "careful");
        q.tick(10_000, null);
        q.tick(16_000, warn);                           // hovered: the timer stands still
        assertEquals(1, q.toasts().size());
        assertEquals(ToastQueue.Level.WARN.lifeMillis + ToastQueue.FADE_MILLIS - 3000, warn.remainingMillis());

        int[] ran = {0};
        ToastQueue.Toast act = q.add(ToastQueue.Level.INFO, "deleted", "Undo", () -> ran[0]++);
        q.click(act);
        assertEquals(1, ran[0]);
        q.tick(16_100, null);
        assertFalse(q.toasts().contains(act));

        for (int i = 0; i < 5; i++) q.add(ToastQueue.Level.ERROR, "e" + i);
        assertEquals(ToastQueue.MAX, q.toasts().size());
        assertEquals("e4", q.toasts().get(ToastQueue.MAX - 1).text);
    }
}
