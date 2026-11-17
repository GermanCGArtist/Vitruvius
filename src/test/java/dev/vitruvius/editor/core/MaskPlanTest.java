package dev.vitruvius.editor.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class MaskPlanTest {
    /** Every object file is 4x4x4; occupancy per file name is a predicate on world coordinates (full by default). */
    private final Map<String, Long> mtimes = new HashMap<>();
    private final Map<String, MaskPlan.Occupancy> occ = new HashMap<>();

    private final MaskPlan.Env env = new MaskPlan.Env() {
        @Override
        public Path file(ObjectNode o, Path base) {
            return Path.of(o.file());
        }

        @Override
        public long mtime(Path file) {
            return mtimes.getOrDefault(file.toString(), 100L);
        }

        @Override
        public MaskPlan.Occupancy occupancy(List<MaskPlan.Placed> placed) {
            return (x, y, z) -> {
                for (MaskPlan.Placed p : placed) {
                    MaskPlan.Occupancy o = occ.get(p.file().toString());
                    if (p.box().contains(new Vec3i(x, y, z)) && (o == null || o.solid(x, y, z))) return true;
                }
                return false;
            };
        }

        @Override
        public Vec3i size(ObjectNode o, Path base) {
            return new Vec3i(4, 4, 4);
        }

        @Override
        public AssemblyBounds.Linked linked(AssemblyNode a, Path base) {
            return null;
        }
    };

    private final Scene scene = new Scene();
    private final ProjectNode proj = new ProjectNode("p");

    MaskPlanTest() {
        scene.openProject(proj);
    }

    private ObjectNode obj(String file, int x, int front) {
        ObjectNode o = new ObjectNode(file, file);
        o.setTransform(Transform.IDENTITY.withPos(new Vec3i(x, 0, 0)));
        o.setFront(front);
        proj.addChild(o);
        return o;
    }

    private MaskPlan.Plan plan(ObjectNode o) {
        return MaskPlan.plan(scene, o, scene.frontOwnersInWorld(proj.world()), env, MaskPlan.Filter.ALL);
    }

    @Test
    void theLoserIsTrimmedOnlyWhereTheWinnerHasBlocks() {
        ObjectNode win = obj("a", 2, 1), lose = obj("b", 0, 2);
        occ.put("a", (x, y, z) -> y < 2);              // the winner is only the lower half
        MaskPlan.Plan p = plan(lose);
        assertEquals(1, p.contributors().size());
        assertTrue(p.removes(2, 0, 0));
        assertTrue(p.removes(3, 1, 3));
        assertFalse(p.removes(2, 2, 0), "air of the winner keeps the loser block");
        assertFalse(p.removes(1, 0, 0), "outside the overlap");
        assertTrue(plan(win).isEmpty(), "the winner loses nothing");
    }

    @Test
    void frontTiesBreakByIdSoExactlyOneLoses() {
        ObjectNode a = obj("a", 0, 3), b = obj("b", 1, 3);
        assertNotEquals(plan(a).isEmpty(), plan(b).isEmpty());
    }

    @Test
    void unnumberedLosesToNumbered() {
        ObjectNode n0 = obj("a", 0, 0), n1 = obj("b", 1, 5);
        assertFalse(plan(n0).isEmpty());
        assertTrue(plan(n1).isEmpty());
    }

    @Test
    void wireHiddenAndTouchingOnlyObjectsDoNotTrim() {
        ObjectNode lose = obj("b", 0, 9);
        ObjectNode wire = obj("w", 1, 1);
        wire.setWire(true);
        ObjectNode hid = obj("h", 1, 2);
        hid.setHidden(true);
        obj("far", 4, 3);                              // touches the face only: no overlap
        assertTrue(plan(lose).isEmpty());
        assertTrue(plan(wire).isEmpty(), "a wire object is never trimmed");
    }

    @Test
    void differenceRemovesWhereTheTargetIsSolid() {
        ObjectNode cutter = obj("c", 2, 5), o = obj("o", 0, 1);
        occ.put("c", (x, y, z) -> x == 2);
        o.mods().add(new BooleanModifier(BooleanModifier.Op.DIFFERENCE, cutter.id()));
        MaskPlan.Plan p = plan(o);
        assertEquals(1, p.contributors().size());
        assertEquals(MaskPlan.Kind.DIFFERENCE, p.contributors().get(0).kind());
        assertTrue(p.removes(2, 1, 1));
        assertFalse(p.removes(3, 1, 1));
        assertFalse(p.removes(0, 0, 0));
    }

    @Test
    void intersectKeepsOnlyWhereTheTargetIsSolid() {
        ObjectNode cutter = obj("c", 2, 5), o = obj("o", 0, 1);
        occ.put("c", (x, y, z) -> x == 2);
        o.mods().add(new BooleanModifier(BooleanModifier.Op.INTERSECT, cutter.id()));
        MaskPlan.Plan p = plan(o);
        assertFalse(p.removes(2, 1, 1), "kept");
        assertTrue(p.removes(3, 1, 1), "inside the target box but air there");
        assertTrue(p.removes(0, 0, 0), "outside the target");
    }

    @Test
    void disabledModifiersAndMissingTargetsAreIgnored() {
        ObjectNode cutter = obj("c", 2, 5), o = obj("o", 0, 1);
        BooleanModifier bm = new BooleanModifier(BooleanModifier.Op.DIFFERENCE, cutter.id());
        bm.enabled = false;
        o.mods().add(bm);
        o.mods().add(new BooleanModifier(BooleanModifier.Op.DIFFERENCE, new java.util.UUID(1, 1)));
        assertTrue(plan(o).isEmpty());
    }

    @Test
    void aCutterThatDoesNotTouchGivesAnEmptyPlan() {
        ObjectNode cutter = obj("c", 50, 5), o = obj("o", 0, 1);
        o.mods().add(new BooleanModifier(BooleanModifier.Op.DIFFERENCE, cutter.id()));
        assertTrue(plan(o).isEmpty());
    }

    @Test
    void booleanOnlyFilterLeavesTrimsOut() {
        ObjectNode win = obj("w", 2, 1), cutter = obj("c", 3, 7), o = obj("o", 0, 2);
        o.mods().add(new BooleanModifier(BooleanModifier.Op.DIFFERENCE, cutter.id()));
        MaskPlan.Plan all = plan(o);
        assertEquals(2, all.contributors().size());
        MaskPlan.Plan b = MaskPlan.plan(scene, o, scene.frontOwnersInWorld(proj.world()), env, MaskPlan.Filter.onlyBoolean(0));
        assertEquals(1, b.contributors().size());
        assertNotEquals(all.hash(), b.hash());
        assertEquals(win.id(), all.contributors().stream().filter(c -> c.kind() == MaskPlan.Kind.TRIM).findFirst().get().id());
    }

    @Test
    void hashIsStableAndChangesWithEveryInput() {
        ObjectNode win = obj("a", 2, 1), lose = obj("b", 0, 2);
        String h = plan(lose).hash();
        assertEquals(h, plan(lose).hash());
        assertEquals(16, h.length());
        mtimes.put("a", 555L);
        assertNotEquals(h, plan(lose).hash(), "contributor mtime");
        mtimes.put("a", 100L);
        mtimes.put("b", 7L);
        assertNotEquals(h, plan(lose).hash(), "source mtime");
        mtimes.remove("b");
        win.setTransform(Transform.IDENTITY.withPos(new Vec3i(1, 0, 0)));
        assertNotEquals(h, plan(lose).hash(), "contributor transform");
        win.setTransform(Transform.IDENTITY.withPos(new Vec3i(2, 0, 0)));
        assertEquals(h, plan(lose).hash());
        lose.setTransform(Transform.IDENTITY.withPos(new Vec3i(1, 0, 0)));
        assertNotEquals(h, plan(lose).hash(), "own transform");
    }

    @Test
    void equivalentMirrorFormsHashTheSame() {
        ObjectNode win = obj("a", 2, 1), lose = obj("b", 0, 2);
        win.setTransform(new Transform(new Vec3i(2, 0, 0), Rot.NONE, Mirror.LEFT_RIGHT));
        String h = plan(lose).hash();
        win.setTransform(new Transform(new Vec3i(2, 0, 0), Rot.CW_180, Mirror.FRONT_BACK));
        assertEquals(h, plan(lose).hash());
    }

    @Test
    void looseObjectsAreNotPlanned() {
        ObjectNode l = new ObjectNode("l", "l");
        scene.loose().addChild(l);
        assertTrue(plan(l).isEmpty());
    }
}
