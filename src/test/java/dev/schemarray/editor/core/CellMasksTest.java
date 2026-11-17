package dev.schemarray.editor.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class CellMasksTest {
    private final MaskPlan.Env env = new MaskPlan.Env() {
        @Override
        public Path file(ObjectNode o, Path base) {
            return Path.of(o.file());
        }

        @Override
        public long mtime(Path file) {
            return 100;
        }

        @Override
        public MaskPlan.Occupancy occupancy(List<MaskPlan.Placed> placed) {
            return (x, y, z) -> {
                for (MaskPlan.Placed p : placed) if (p.box().contains(new Vec3i(x, y, z))) return true;
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

    CellMasksTest() {
        scene.openProject(proj);
    }

    private ObjectNode obj(String file, int x, int front) {
        ObjectNode o = new ObjectNode(file, file);
        o.setTransform(Transform.IDENTITY.withPos(new Vec3i(x, 0, 0)));
        o.setFront(front);
        proj.addChild(o);
        return o;
    }

    private CellMasks.Cell cell(ObjectNode o, String key, int x, boolean loose) {
        Transform w = Transform.IDENTITY.withPos(new Vec3i(x, 0, 0));
        return new CellMasks.Cell(Ids.derived(o.id() + "#" + key), new MaskPlan.Subject(o, null, w, o, loose));
    }

    private CellMasks.Result plan(List<CellMasks.Cell> cells, int cap) {
        return CellMasks.plan(scene, cells, scene.frontOwnersInWorld(proj.world()), env, MaskPlan.Filter.ALL, cap);
    }

    @Test
    void everyCellHasItsOwnMask() {
        obj("w", 10, 1);
        ObjectNode l = obj("l", 0, 2);
        var cells = List.of(cell(l, "base", 0, false), cell(l, "1", 8, false), cell(l, "2", 24, false));
        var r = plan(cells, 256);
        assertNull(r.plans().get(cells.get(0).slot()), "the base cell does not touch the winner");
        assertTrue(r.plans().get(cells.get(1).slot()).removes(10, 0, 0), "cell 1 is trimmed where the winner is");
        assertFalse(r.plans().containsKey(cells.get(2).slot()), "cell 2 is far away");
    }

    @Test
    void cellsWithTheSameSurroundingsShareOneFile() {
        obj("w", 10, 1);
        obj("w", 30, 1);   // a second copy of the winner, the same distance from another cell
        ObjectNode l = obj("l", 0, 2);
        var cells = List.of(cell(l, "1", 8, false), cell(l, "2", 28, false));
        var r = plan(cells, 256);
        String h1 = r.plans().get(cells.get(0).slot()).hash(), h2 = r.plans().get(cells.get(1).slot()).hash();
        assertEquals(h1, h2, "the same relations give the same mask hash");
        assertEquals(1, r.files().get(l.id()));
    }

    @Test
    void differentSurroundingsGiveDifferentFiles() {
        obj("w", 10, 1);
        ObjectNode l = obj("l", 0, 2);
        var cells = List.of(cell(l, "1", 8, false), cell(l, "2", 9, false));
        var r = plan(cells, 256);
        assertNotEquals(r.plans().get(cells.get(0).slot()).hash(), r.plans().get(cells.get(1).slot()).hash());
        assertEquals(2, r.files().get(l.id()));
    }

    @Test
    void theCapLeavesTheRestOnTheOriginalAndCountsThem() {
        obj("w", 10, 1);
        ObjectNode l = obj("l", 0, 2);
        List<CellMasks.Cell> cells = new ArrayList<>();
        for (int i = 0; i < 5; i++) cells.add(cell(l, "c" + i, 7 + i, false));   // five different offsets: five distinct masks
        var r = plan(cells, 3);
        assertEquals(3, r.plans().size());
        assertEquals(2, r.capped().get(l.id()));
        assertTrue(r.anyCapped());
        assertTrue(r.plans().containsKey(cells.get(0).slot()), "the first cells keep their files");
        assertFalse(r.plans().containsKey(cells.get(4).slot()));
    }

    @Test
    void aCellWhoseMaskIsAlreadyKnownIsNotCapped() {
        obj("w", 10, 1);
        obj("w", 30, 1);
        ObjectNode l = obj("l", 0, 2);
        var cells = List.of(cell(l, "1", 8, false), cell(l, "2", 9, false), cell(l, "3", 28, false));
        var r = plan(cells, 2);
        assertTrue(r.plans().containsKey(cells.get(2).slot()), "cell 3 equals cell 1's mask, which already has a file");
        assertFalse(r.anyCapped());
    }

    @Test
    void aLooseObjectIsOnlyTrimmedByANumberedWinner() {
        ObjectNode w = obj("w", 10, 0);
        ObjectNode l = new ObjectNode("l", "l");
        scene.loose().addChild(l);
        l.setFront(0);
        var cells = List.of(cell(l, "base", 8, true));
        var r = CellMasks.plan(scene, cells, scene.frontOwnersInWorld(proj.world()), env, MaskPlan.Filter.ALL, 256);
        assertTrue(r.plans().isEmpty(), "an unnumbered winner never trims a Loose object");
        w.setFront(1);
        r = CellMasks.plan(scene, cells, scene.frontOwnersInWorld(proj.world()), env, MaskPlan.Filter.ALL, 256);
        assertEquals(1, r.plans().size());
    }

    @Test
    void theHashDoesNotDependOnWhereTheCopyIs() {
        obj("w", 10, 1);
        ObjectNode l = obj("l", 0, 2);
        var a = plan(List.of(cell(l, "x", 8, false)), 256).plans().values().iterator().next();
        // the same relation, shifted along: winner and copy both moved by 100
        proj.children().get(0).setName("w");
        ((ObjectNode) proj.children().get(0)).setTransform(Transform.IDENTITY.withPos(new Vec3i(110, 0, 0)));
        var b = plan(List.of(cell(l, "x", 108, false)), 256).plans().values().iterator().next();
        assertEquals(a.hash(), b.hash());
    }
}
