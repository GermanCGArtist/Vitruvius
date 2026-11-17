package dev.schemarray.editor.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SceneTest {
    private static ObjectNode obj(String n) {
        return new ObjectNode(n, "files/" + n + ".litematic");
    }

    @Test
    void parentLinksFollowAddAndRemove() {
        FolderNode f = new FolderNode("f");
        ObjectNode a = obj("a"), b = obj("b");
        f.addChild(a);
        f.addChild(b);
        assertSame(f, a.parent());
        assertEquals(List.of(a, b), f.children());
        f.addChild(0, b);   // moving inside the same parent
        assertEquals(List.of(b, a), f.children());
        FolderNode g = new FolderNode("g");
        g.addChild(a);   // reparent
        assertSame(g, a.parent());
        assertEquals(List.of(b), f.children());
        assertTrue(g.removeChild(a));
        assertNull(a.parent());
        assertFalse(g.removeChild(a));
    }

    @Test
    void moveToLaterSlotOfSameParent() {
        FolderNode f = new FolderNode("f");
        ObjectNode a = obj("a"), b = obj("b"), c = obj("c");
        f.addChild(a);
        f.addChild(b);
        f.addChild(c);
        f.addChild(3, a);   // to the end: index counts before a is taken out
        assertEquals(List.of(b, c, a), f.children());
    }

    @Test
    void containmentRules() {
        FolderNode f = new FolderNode("f");
        FolderNode inner = new FolderNode("inner");
        f.addChild(inner);
        assertThrows(IllegalArgumentException.class, () -> inner.addChild(f));   // loop
        assertThrows(IllegalArgumentException.class, () -> f.addChild(f));
        assertThrows(IllegalArgumentException.class, () -> obj("o").addChild(obj("p")));   // leaf
        assertThrows(IllegalArgumentException.class, () -> f.addChild(new ProjectNode("p")));
        assertThrows(IllegalArgumentException.class, () -> f.addChild(new LooseNode()));
        assertThrows(IllegalArgumentException.class, () -> new ExternalNode("x", "t:x").addChild(obj("p")));
        new AssemblyNode("asm").addChild(new AssemblyNode("nested"));
    }

    @Test
    void activeProjectAndLifecycle() {
        Scene s = new Scene();
        assertNull(s.activeProject());
        ProjectNode p1 = new ProjectNode("p1"), p2 = new ProjectNode("p2");
        s.openProject(p1);
        s.openProject(p2);
        assertSame(p1, s.activeProject());
        assertTrue(p1.active());
        s.setActiveProject(p2);
        assertFalse(p1.active());
        assertTrue(p2.active());
        assertThrows(IllegalArgumentException.class, () -> s.setActiveProject(new ProjectNode("other")));
        assertThrows(IllegalArgumentException.class, () -> s.openProject(new ProjectNode(p1.id(), "same id")));
        s.closeProject(p2);
        assertSame(p1, s.activeProject());
        s.closeProject(p1);
        assertNull(s.activeProject());
        assertEquals(List.of(s.loose()), s.roots());
    }

    @Test
    void byIdParentOfAndProjectOf() {
        Scene s = new Scene();
        ProjectNode p = new ProjectNode("p");
        FolderNode f = new FolderNode("f");
        ObjectNode o = obj("o");
        ObjectNode l = obj("l");
        p.addChild(f);
        f.addChild(o);
        s.loose().addChild(l);
        s.openProject(p);
        assertSame(o, s.byId(o.id()));
        assertSame(l, s.byId(l.id()));
        assertSame(p, s.byId(p.id()));
        assertNull(s.byId(UUID.randomUUID()));
        assertSame(f, s.parentOf(o));
        assertNull(s.parentOf(p));
        assertSame(p, s.projectOf(o));
        assertNull(s.projectOf(l));
        assertEquals(5, s.allNodes().size());
        assertSame(o, s.index().get(o.id()));
    }

    @Test
    void worldTransformComposesDownFromTheRoot() {
        Scene s = new Scene();
        ProjectNode p = new ProjectNode("p");
        p.setOrigin(new Vec3i(1000, 0, 1000));   // an origin does not move children
        FolderNode f = new FolderNode("f");
        AssemblyNode asm = new AssemblyNode("asm");
        asm.setTransform(new Transform(new Vec3i(10, 0, 0), Rot.CW_90, Mirror.NONE));
        AssemblyNode inner = new AssemblyNode("inner");
        inner.setTransform(new Transform(new Vec3i(0, 5, 0), Rot.NONE, Mirror.LEFT_RIGHT));
        ObjectNode o = obj("o");
        o.setTransform(new Transform(new Vec3i(1, 0, 0), Rot.NONE, Mirror.NONE));
        p.addChild(f);
        f.addChild(asm);
        asm.addChild(inner);
        inner.addChild(o);
        s.openProject(p);

        assertEquals(Transform.IDENTITY, s.worldTransform(p));
        assertEquals(Transform.IDENTITY, s.worldTransform(f));
        assertEquals(asm.transform(), s.worldTransform(asm));
        // the object's origin: o at (1,0,0) -> inner (mirror z, +5 y) -> asm (turn, +10 x)
        assertEquals(asm.transform().apply(inner.transform().apply(new Vec3i(1, 0, 0))), s.worldTransform(o).apply(Vec3i.ZERO));
        // and every point equals composing by hand: asm(inner(o(p)))
        for (Vec3i v : List.of(new Vec3i(0, 0, 0), new Vec3i(1, 2, 3), new Vec3i(-4, 0, 7))) {
            Vec3i expect = asm.transform().apply(inner.transform().apply(o.transform().apply(v)));
            assertEquals(expect, s.worldTransform(o).apply(v));
        }
        assertTrue(s.worldTransform(o).mirror() != Mirror.LEFT_RIGHT);
    }

    @Test
    void ownerIsTheTopmostAssembly() {
        Scene s = new Scene();
        ProjectNode p = new ProjectNode("p");
        FolderNode f = new FolderNode("f");
        AssemblyNode top = new AssemblyNode("top");
        AssemblyNode sub = new AssemblyNode("sub");
        ObjectNode part = obj("part");
        ObjectNode free = obj("free");
        ExternalNode ext = new ExternalNode("x", "heavymatica:area");
        p.addChild(f);
        f.addChild(top);
        top.addChild(sub);
        sub.addChild(part);
        f.addChild(free);
        f.addChild(ext);
        s.openProject(p);
        assertSame(top, s.ownerOf(part));
        assertSame(top, s.ownerOf(sub));
        assertSame(top, s.ownerOf(top));
        assertSame(free, s.ownerOf(free));
        assertSame(ext, s.ownerOf(ext));
        assertSame(f, s.ownerOf(f));
    }

    @Test
    void frontOwnersInWorld() {
        Scene s = new Scene();
        ProjectNode p1 = new ProjectNode("p1"), p2 = new ProjectNode("p2");
        p1.setWorld("w1");
        p2.setWorld("w2");
        AssemblyNode asm = new AssemblyNode("asm");
        ObjectNode part = obj("part"), a = obj("a"), b = obj("b"), l = obj("l");
        asm.addChild(part);
        p1.addChild(asm);
        p1.addChild(a);
        p1.addChild(new ExternalNode("x", "t:x"));
        p2.addChild(b);
        s.loose().addChild(l);
        s.openProject(p1);
        s.openProject(p2);
        List<HasFront> w1 = s.frontOwnersInWorld("w1");
        assertEquals(3, w1.size());
        assertTrue(w1.contains(asm) && w1.contains(a) && w1.contains(l));
        assertFalse(w1.contains(part) || w1.contains(b));
    }

    @Test
    void structurallyEqualsLooksAtEverythingSaved() {
        ObjectNode a = obj("a"), b = obj("a");
        UUID same = UUID.randomUUID();
        a = new ObjectNode(same, "a");
        b = new ObjectNode(same, "a");
        assertTrue(a.structurallyEquals(b));
        b.setFront(2);
        assertFalse(a.structurallyEquals(b));
        b.setFront(0);
        b.mods().add(new ArrayModifier());
        assertFalse(a.structurallyEquals(b));
        b.mods().clear();
        JsonObject x = new JsonObject();
        x.addProperty("k", 1);
        b.setExtra(x);
        assertFalse(a.structurallyEquals(b));
    }
}
