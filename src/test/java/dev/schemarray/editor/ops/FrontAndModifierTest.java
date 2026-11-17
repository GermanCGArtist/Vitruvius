package dev.schemarray.editor.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.schemarray.editor.core.ArrayModifier;
import dev.schemarray.editor.core.AssemblyNode;
import dev.schemarray.editor.core.BooleanModifier;
import dev.schemarray.editor.core.ExternalNode;
import dev.schemarray.editor.core.FolderNode;
import dev.schemarray.editor.core.Mirror;
import dev.schemarray.editor.core.Modifier;
import dev.schemarray.editor.core.Node;
import dev.schemarray.editor.core.ObjectNode;
import dev.schemarray.editor.core.Rot;
import dev.schemarray.editor.core.Transform;
import dev.schemarray.editor.core.Vec3i;
import dev.schemarray.editor.ops.ModifierEdits.AddModifier;
import dev.schemarray.editor.ops.ModifierEdits.EditModifier;
import dev.schemarray.editor.ops.ModifierEdits.RemoveModifier;
import dev.schemarray.editor.ops.ModifierEdits.ReorderModifier;
import dev.schemarray.editor.ops.ModifierEdits.SetModifierEnabled;

import java.util.List;
import org.junit.jupiter.api.Test;

class FrontAndModifierTest {
    private final Fx fx = new Fx();
    private final ObjectNode a = fx.obj(fx.p, "a", 10, 0, 0);
    private final ObjectNode b = fx.obj(fx.p, "b", 20, 0, 0);
    private final ObjectNode c = fx.obj(fx.p, "c", 30, 0, 0);

    {
        a.setFront(1);
        b.setFront(2);
    }

    private static ArrayModifier array(int count, double rx) {
        ArrayModifier m = new ArrayModifier();
        m.count = count;
        m.relative = new double[] {rx, 0, 0};
        return m;
    }

    private List<Integer> fronts() {
        return List.of(a.front(), b.front(), c.front());
    }

    // ---- front order ----

    @Test
    void bringToFront() {
        fx.roundTrip(SetFront.front(Fx.ids(c)));
        assertEquals(List.of(2, 3, 1), fronts());
    }

    @Test
    void sendToBack() {
        fx.roundTrip(SetFront.back(Fx.ids(a)));
        assertEquals(List.of(3, 1, 2), fronts());
    }

    @Test
    void forwardAndBackward() {
        fx.roundTrip(SetFront.forward(Fx.ids(b)));
        assertEquals(List.of(2, 1, 3), fronts());
        fx.roundTrip(SetFront.backward(Fx.ids(a)));
        assertEquals(List.of(3, 1, 2), fronts());   // order b, a, c becomes b, c, a
    }

    @Test
    void setFrontToAnExactPlace() {
        fx.roundTrip(SetFront.to(c.id(), 1));
        assertEquals(List.of(2, 3, 1), fronts());
        fx.roundTrip(SetFront.to(a.id(), 99));
        assertEquals(3, a.front());
        assertNotNull(fx.refusal(SetFront.to(a.id(), 0)));
    }

    @Test
    void theOrderIsSharedByAllProjectsOfAWorldButNotOtherWorlds() {
        var same = fx.project("Same", "w");
        var far = fx.project("Far", "z");
        ObjectNode x = fx.obj(same, "x", 0, 0, 0);
        ObjectNode y = fx.obj(far, "y", 0, 0, 0);
        y.setFront(7);
        fx.roundTrip(SetFront.front(Fx.ids(x)));
        assertEquals(List.of(2, 3, 4), fronts());
        assertEquals(1, x.front());
        assertEquals(7, y.front());
    }

    @Test
    void frontOrderRefusals() {
        AssemblyNode asm = fx.asm(fx.p, "asm", 0, 0, 0);
        ObjectNode part = fx.obj(asm, "part", 0, 0, 0);
        assertTrue(fx.refusal(SetFront.front(Fx.ids(part))).reason().contains("whole assembly"));
        FolderNode f = fx.folder(fx.p, "f");
        assertNotNull(fx.refusal(SetFront.front(Fx.ids(f))));
        ExternalNode area = fx.ext(fx.p, "area", 0, 0, 0);
        assertNotNull(fx.refusal(SetFront.back(Fx.ids(area))));
    }

    // ---- modifiers ----

    @Test
    void addAndRemoveAModifier() {
        fx.roundTrip(new AddModifier(a.id(), array(3, 1), -1));
        assertEquals(1, a.mods().size());
        assertEquals("Add Array to a", fx.stack.undoLabel().orElseThrow());
        fx.roundTrip(new RemoveModifier(a.id(), 0));
        assertTrue(a.mods().isEmpty());
    }

    @Test
    void addBooleanToAnAssemblyAtAnIndex() {
        AssemblyNode asm = fx.asm(fx.p, "asm", 0, 0, 0);
        asm.mods().add(array(2, 1));
        fx.roundTrip(new AddModifier(asm.id(), new BooleanModifier(BooleanModifier.Op.INTERSECT, a.id()), 0));
        assertTrue(asm.mods().get(0) instanceof BooleanModifier);
    }

    @Test
    void reorderAndToggle() {
        a.mods().add(array(2, 1));
        a.mods().add(new BooleanModifier(BooleanModifier.Op.DIFFERENCE, b.id()));
        fx.roundTrip(new ReorderModifier(a.id(), 1, 0));
        assertTrue(a.mods().get(0) instanceof BooleanModifier);
        fx.roundTrip(new SetModifierEnabled(a.id(), 0, false));
        assertFalse(a.mods().get(0).enabled());
        assertNotNull(fx.refusal(new ReorderModifier(a.id(), 0, 5)));
        assertNotNull(fx.refusal(new RemoveModifier(a.id(), 2)));
    }

    @Test
    void editingTheSameFieldRepeatedlyIsOneUndoStep() {
        a.mods().add(array(2, 1));
        for (int n = 3; n <= 8; n++) fx.stack.execute(new EditModifier(a.id(), 0, "count", array(n, 1)));
        assertEquals(1, fx.stack.undoSize());
        assertEquals(8, ((ArrayModifier) a.mods().get(0)).count);
        fx.stack.execute(new EditModifier(a.id(), 0, "relative", array(8, 2)));   // another field: a new step
        assertEquals(2, fx.stack.undoSize());
        fx.stack.undo();
        fx.stack.undo();
        assertEquals(2, ((ArrayModifier) a.mods().get(0)).count);
    }

    @Test
    void editRoundTrip() {
        a.mods().add(array(2, 1));
        fx.roundTrip(new EditModifier(a.id(), 0, "count", array(5, 1)));
        assertNotNull(fx.refusal(new EditModifier(a.id(), 0, "x", new BooleanModifier())));
    }

    @Test
    void arraysTooBigAreRefused() {
        Refusal r = fx.refusal(new AddModifier(a.id(), array(2000, 1), -1));
        assertTrue(r.reason().contains("1999") && r.reason().contains("1024"), r.reason());
        assertNotNull(fx.refusal(new AddModifier(a.id(), array(0, 1), -1)));
        ArrayModifier big = array(2000, 1);
        big.enabled = false;
        a.mods().add(big);
        assertNotNull(fx.refusal(new SetModifierEnabled(a.id(), 0, true)));
    }

    @Test
    void booleanRules() {
        BooleanModifier self = new BooleanModifier(BooleanModifier.Op.DIFFERENCE, a.id());
        assertTrue(fx.refusal(new AddModifier(a.id(), self, -1)).reason().contains("cannot cut itself"));
        var other = fx.project("Other", "w");
        ObjectNode far = fx.obj(other, "far", 0, 0, 0);
        assertTrue(fx.refusal(new AddModifier(a.id(), new BooleanModifier(BooleanModifier.Op.DIFFERENCE, far.id()), -1)).reason().contains("another project"));
        fx.roundTrip(new AddModifier(a.id(), new BooleanModifier(BooleanModifier.Op.DIFFERENCE, b.id()), -1));
        Refusal loop = fx.refusal(new AddModifier(b.id(), new BooleanModifier(BooleanModifier.Op.DIFFERENCE, a.id()), -1));
        assertTrue(loop.reason().contains("cannot cut each other"), loop.reason());
        // a target without a choice yet is fine
        fx.roundTrip(new AddModifier(c.id(), new BooleanModifier(), -1));
    }

    @Test
    void modifierRefusals() {
        ExternalNode area = fx.ext(fx.p, "area", 0, 0, 0);
        assertNull(fx.refusal(new AddModifier(area.id(), array(2, 1), -1)), "an object of another mod can be arrayed");
        assertNotNull(fx.refusal(new AddModifier(area.id(), new BooleanModifier(), -1)), "but never cut");
        AssemblyNode l = fx.asm(fx.p, "l", 0, 0, 0);
        l.setSource(new AssemblyNode.Link("files/l.lasm"));
        ObjectNode part = fx.obj(l, "lp", 0, 0, 0);
        assertNotNull(fx.refusal(new AddModifier(part.id(), array(2, 1), -1)));
    }

    // ---- realise ----

    @Test
    void realiseAnArrayIntoAFolderOfObjects() {
        a.mods().add(array(3, 1));
        a.mods().add(new BooleanModifier(BooleanModifier.Op.DIFFERENCE, b.id()));
        a.placementIds().put(ObjectNode.BASE_CELL, "P0");
        a.placementIds().put("1", "P1");
        a.placementIds().put("2", "P2");
        fx.roundTrip(new RealiseArray(a.id(), new Vec3i(4, 2, 2)));
        FolderNode f = (FolderNode) fx.p.children().get(0);
        assertEquals("a (realised)", f.name());
        assertEquals(List.of("a", "a 2", "a 3"), f.children().stream().map(Node::name).toList());
        ObjectNode a2 = (ObjectNode) f.children().get(1), a3 = (ObjectNode) f.children().get(2);
        assertEquals(new Vec3i(10, 0, 0), fx.pos(a));
        assertEquals(new Vec3i(14, 0, 0), fx.pos(a2));
        assertEquals(new Vec3i(18, 0, 0), fx.pos(a3));
        assertEquals(1, a.mods().size());   // the array is gone, the Boolean stays, on every copy
        assertEquals(1, a3.mods().size());
        assertEquals(a.file(), a2.file());
        assertEquals(a.front(), a2.front());
        assertEquals("P0", a.placementIds().get(ObjectNode.BASE_CELL));
        assertEquals(1, a.placementIds().size());
        assertEquals("P1", a2.placementIds().get(ObjectNode.BASE_CELL));
        assertEquals("P2", a3.placementIds().get(ObjectNode.BASE_CELL));
        // the copies can be arrayed again
        fx.roundTrip(new AddModifier(a2.id(), array(2, 1), -1));
    }

    @Test
    void realiseFollowsTheObjectsOwnAxes() {
        a.setTransform(new Transform(new Vec3i(10, 0, 0), Rot.CW_90, Mirror.NONE));
        a.mods().add(array(2, 1));
        fx.roundTrip(new RealiseArray(a.id(), new Vec3i(4, 2, 2)));
        ObjectNode a2 = (ObjectNode) fx.find("a 2");
        assertEquals(new Vec3i(10, 0, 4), fx.pos(a2));   // along the turned x axis: +z
        assertEquals(Rot.CW_90, a2.transform().rot());
    }

    @Test
    void realiseAnAssemblyArrayCopiesTheParts() {
        AssemblyNode asm = fx.asm(fx.p, "asm", 0, 0, 0);
        fx.obj(asm, "part", 1, 0, 0);
        ArrayModifier m = new ArrayModifier();
        m.count = 2;
        m.relative = new double[] {0, 0, 1};
        asm.mods().add(m);
        fx.roundTrip(new RealiseArray(asm.id(), new Vec3i(1, 1, 3)));
        AssemblyNode copy = (AssemblyNode) fx.find("asm 2");
        assertEquals(new Vec3i(0, 0, 3), fx.pos(copy));
        assertEquals(1, copy.children().size());
        assertTrue(asm.mods().isEmpty());
    }

    @Test
    void realiseRefusals() {
        assertNotNull(fx.refusal(new RealiseArray(a.id(), new Vec3i(1, 1, 1))));
        ArrayModifier off = array(3, 1);
        off.enabled = false;
        a.mods().add(off);
        assertNotNull(fx.refusal(new RealiseArray(a.id(), new Vec3i(1, 1, 1))));
        a.mods().clear();
        a.mods().add(array(1, 1));
        assertNotNull(fx.refusal(new RealiseArray(a.id(), new Vec3i(1, 1, 1))));
    }

    // ---- apply boolean ----

    @Test
    void applyBooleanCopiesTheDerivedFileAndDropsTheModifier() {
        a.mods().add(new BooleanModifier(BooleanModifier.Op.DIFFERENCE, b.id()));
        fx.fs.put("Projects/Metro/.cache/derived/a-1.litematic", "cut");
        fx.roundTrip(new ApplyBoolean(a.id(), 0, "Projects/Metro/.cache/derived/a-1.litematic"));
        assertEquals("files/a_bool.litematic", a.file());
        assertEquals("cut", new String(fx.fs.bytes("Projects/Metro/files/a_bool.litematic")));
        assertTrue(a.mods().isEmpty());
        assertTrue(fx.fs.exists("Projects/Metro/.cache/derived/a-1.litematic"));
        assertTrue(fx.fs.exists("Projects/Metro/files/a.litematic"));
        fx.stack.undo();
        assertFalse(fx.fs.exists("Projects/Metro/files/a_bool.litematic"));
        assertEquals(2, fx.fs.list("Projects/Metro/.trash").size());   // moved to trash, not deleted (roundTrip undid once already)
    }

    @Test
    void applyBooleanRefusals() {
        a.mods().add(array(2, 1));
        a.mods().add(new BooleanModifier(BooleanModifier.Op.DIFFERENCE, b.id()));
        assertNotNull(fx.refusal(new ApplyBoolean(a.id(), 0, "x")));
        assertTrue(fx.refusal(new ApplyBoolean(a.id(), 1, "Projects/Metro/.cache/derived/none.litematic")).reason().contains("not built"));
        List<Modifier> none = b.mods();
        assertTrue(none.isEmpty());
        assertNotNull(fx.refusal(new ApplyBoolean(b.id(), 0, "x")));
    }
}
