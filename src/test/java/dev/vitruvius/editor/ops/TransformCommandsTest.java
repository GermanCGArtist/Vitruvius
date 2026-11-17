package dev.vitruvius.editor.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vitruvius.editor.core.AssemblyNode;
import dev.vitruvius.editor.core.ExternalNode;
import dev.vitruvius.editor.core.FolderNode;
import dev.vitruvius.editor.core.Mirror;
import dev.vitruvius.editor.core.ObjectNode;
import dev.vitruvius.editor.core.Rot;
import dev.vitruvius.editor.core.Transform;
import dev.vitruvius.editor.core.Vec3i;

import java.util.List;
import org.junit.jupiter.api.Test;

class TransformCommandsTest {
    private final Fx fx = new Fx();
    private final ObjectNode a = fx.obj(fx.p, "a", 10, 0, 0);
    private final ObjectNode b = fx.obj(fx.p, "b", 11, 0, 0);

    @Test
    void moveOne() {
        fx.roundTrip(new Move(Fx.ids(a), new Vec3i(3, 1, -2)));
        assertEquals(new Vec3i(13, 1, -2), fx.pos(a));
        assertEquals(new Vec3i(11, 0, 0), fx.pos(b));
    }

    @Test
    void moveMany() {
        fx.roundTrip(new Move(Fx.ids(a, b), new Vec3i(0, 5, 0)));
        assertEquals(new Vec3i(10, 5, 0), fx.pos(a));
        assertEquals(new Vec3i(11, 5, 0), fx.pos(b));
        assertEquals("Move 2 objects", fx.stack.undoLabel().orElseThrow());
    }

    @Test
    void moveFolderMovesTheObjectsInside() {
        FolderNode f = fx.folder(fx.p, "f");
        ObjectNode c = fx.obj(f, "c", 0, 0, 0);
        fx.roundTrip(new Move(Fx.ids(f), new Vec3i(2, 0, 0)));
        assertEquals(new Vec3i(2, 0, 0), fx.pos(c));
    }

    @Test
    void aNodeUnderAnotherSelectedNodeMovesOnlyOnce() {
        AssemblyNode asm = fx.asm(fx.p, "asm", 0, 0, 0);
        ObjectNode part = fx.obj(asm, "part", 1, 0, 0);
        fx.roundTrip(new Move(Fx.ids(asm, part), new Vec3i(4, 0, 0)));
        assertEquals(new Vec3i(4, 0, 0), fx.pos(asm));
        assertEquals(new Vec3i(1, 0, 0), fx.pos(part));
    }

    @Test
    void moveWorksInTheSpaceOfATurnedParent() {
        AssemblyNode asm = fx.asm(fx.p, "asm", 0, 0, 0);
        asm.setTransform(new Transform(Vec3i.ZERO, Rot.CW_90, Mirror.NONE));
        ObjectNode part = fx.obj(asm, "part", 1, 0, 0);
        assertEquals(new Vec3i(0, 0, 1), fx.world(part));
        fx.roundTrip(new Move(Fx.ids(part), new Vec3i(0, 0, 5)));
        assertEquals(new Vec3i(6, 0, 0), fx.pos(part));
        assertEquals(new Vec3i(0, 0, 6), fx.world(part));
    }

    @Test
    void moveAlsoWorksForAnExternalNode() {
        ExternalNode area = fx.ext(fx.p, "area", 0, 0, 0);
        fx.roundTrip(new Move(Fx.ids(area), new Vec3i(1, 2, 3)));
        assertEquals(new Vec3i(1, 2, 3), area.transform().pos());
    }

    @Test
    void movingAPartOfALinkedAssemblyIsRefused() {
        AssemblyNode linked = fx.asm(fx.p, "station", 0, 0, 0);
        linked.setSource(new AssemblyNode.Link("files/station.lasm"));
        ObjectNode part = fx.obj(linked, "roof", 0, 0, 0);
        Refusal r = fx.refusal(new Move(Fx.ids(part), new Vec3i(1, 0, 0)));
        assertNotNull(r);
        assertTrue(r.reason().contains("linked assembly 'station'"), r.reason());
        // the linked assembly itself can be moved
        assertTrue(fx.stack.execute(new Move(Fx.ids(linked), new Vec3i(1, 0, 0))).isEmpty());
        assertEquals(new Vec3i(1, 0, 0), fx.pos(linked));
    }

    @Test
    void movingNothingIsRefused() {
        FolderNode empty = fx.folder(fx.p, "empty");
        assertNotNull(fx.refusal(new Move(Fx.ids(empty), new Vec3i(1, 0, 0))));
        assertNotNull(fx.refusal(new Move(List.of(), new Vec3i(1, 0, 0))));
        assertNotNull(fx.refusal(new Move(List.of(java.util.UUID.randomUUID()), new Vec3i(1, 0, 0))));
    }

    @Test
    void setTransform() {
        Transform t = new Transform(new Vec3i(1, 2, 3), Rot.CW_180, Mirror.FRONT_BACK);
        fx.roundTrip(new SetTransform(a.id(), t));
        assertEquals(t, a.transform());
        FolderNode f = fx.folder(fx.p, "f");
        assertNotNull(fx.refusal(new SetTransform(f.id(), t)));
    }

    @Test
    void rotateClockwiseAboutTheWorldOrigin() {
        fx.roundTrip(Rotate.clockwise(Fx.ids(a), Vec3i.ZERO));
        assertEquals(new Transform(new Vec3i(0, 0, 10), Rot.CW_90, Mirror.NONE), a.transform());
    }

    @Test
    void rotateCounterClockwiseAndHalfTurn() {
        fx.roundTrip(Rotate.counterClockwise(Fx.ids(a), Vec3i.ZERO));
        assertEquals(new Transform(new Vec3i(0, 0, -10), Rot.CCW_90, Mirror.NONE), a.transform());
        fx.roundTrip(new Rotate(Fx.ids(b), Vec3i.ZERO, 2));
        assertEquals(new Transform(new Vec3i(-11, 0, 0), Rot.CW_180, Mirror.NONE), b.transform());
    }

    @Test
    void thePivotBlockStaysPut() {
        Vec3i pivot = new Vec3i(10, 3, 0);
        fx.roundTrip(Rotate.clockwise(Fx.ids(a), pivot));
        assertEquals(new Vec3i(10, 0, 0), fx.pos(a));   // a sits on the pivot column
        assertEquals(Rot.CW_90, a.transform().rot());
        // b one block east of the pivot goes one block south (+z), as a clockwise turn seen from above does
        fx.roundTrip(Rotate.clockwise(Fx.ids(b), pivot));
        assertEquals(new Vec3i(10, 0, 1), fx.pos(b));
    }

    @Test
    void severalNodesTurnAsAGroupAboutOneSharedPivot() {
        fx.roundTrip(Rotate.clockwise(Fx.ids(a, b), new Vec3i(10, 0, 0)));
        assertEquals(new Vec3i(10, 0, 0), fx.pos(a));
        assertEquals(new Vec3i(10, 0, 1), fx.pos(b));
        fx.stack.undo();
        fx.roundTrip(Rotate.counterClockwise(Fx.ids(a, b), new Vec3i(10, 0, 0)));
        assertEquals(new Vec3i(10, 0, -1), fx.pos(b));
    }

    @Test
    void fourQuarterTurnsAreTheIdentity() {
        Transform before = a.transform();
        for (int i = 0; i < 4; i++) fx.stack.execute(Rotate.clockwise(Fx.ids(a), new Vec3i(3, 0, 7)));
        assertEquals(before, a.transform());
    }

    @Test
    void aFullTurnIsRefused() {
        assertNotNull(fx.refusal(new Rotate(Fx.ids(a), Vec3i.ZERO, 4)));
    }

    @Test
    void rotateAPartOfATurnedAssemblyTurnsItInTheWorld() {
        AssemblyNode asm = fx.asm(fx.p, "asm", 0, 0, 0);
        asm.setTransform(new Transform(Vec3i.ZERO, Rot.CW_90, Mirror.NONE));
        ObjectNode part = fx.obj(asm, "part", 1, 0, 0);
        fx.roundTrip(Rotate.clockwise(Fx.ids(part), Vec3i.ZERO));
        Transform w = fx.scene.worldTransform(part);
        assertEquals(new Vec3i(-1, 0, 0), w.pos());
        assertEquals(Rot.CW_180, w.rot());
    }

    @Test
    void mirrorAboutAPivot() {
        fx.roundTrip(new MirrorX(Fx.ids(a), new Vec3i(4, 0, 0)));
        assertEquals(new Transform(new Vec3i(-2, 0, 0), Rot.NONE, Mirror.FRONT_BACK), a.transform());
    }

    @Test
    void mirrorTwiceGivesTheOriginalAndMirrorZIsMirrorXPlusAHalfTurn() {
        Transform before = a.transform();
        fx.stack.execute(new MirrorX(Fx.ids(a), new Vec3i(4, 0, 0)));
        fx.stack.execute(new MirrorX(Fx.ids(a), new Vec3i(4, 0, 0)));
        assertEquals(before, a.transform());
        // Mirror Z about x = 0: Mirror X, then a half turn, in one step
        fx.stack.begin("Mirror Z");
        fx.stack.execute(new MirrorX(Fx.ids(a), Vec3i.ZERO));
        fx.stack.execute(new Rotate(Fx.ids(a), Vec3i.ZERO, 2));
        fx.stack.commit();
        assertEquals(new Vec3i(10, 0, 0), fx.pos(a));
        assertEquals(Rot.CW_180, a.transform().rot());
        assertEquals(Mirror.FRONT_BACK, a.transform().mirror());
        // a point (0,0,5) of the object lands at z = -5: z is mirrored
        assertEquals(new Vec3i(10, 0, -5), a.transform().apply(new Vec3i(0, 0, 5)));
    }

    @Test
    void mirrorAnObjectThatIsAlreadyTurned() {
        a.setTransform(new Transform(new Vec3i(10, 0, 0), Rot.CW_90, Mirror.NONE));
        fx.roundTrip(new MirrorX(Fx.ids(a), Vec3i.ZERO));
        assertEquals(new Vec3i(-10, 0, 0), fx.pos(a));
        assertEquals(Mirror.FRONT_BACK, a.transform().mirror());
    }
}
