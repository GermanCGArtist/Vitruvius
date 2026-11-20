package dev.vitruvius.editor.sync.history;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.vitruvius.editor.sync.SyncManifest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class HistoryTest {
    private static JsonObject project(String... objs) {
        return JsonParser.parseString("{\"format\":1,\"id\":\"p\",\"name\":\"P\",\"nodes\":[" + String.join(",", objs) + "]}").getAsJsonObject();
    }

    private static String obj(String id, int x) {
        return "{\"id\":\"" + id + "\",\"type\":\"object\",\"name\":\"" + id + "\",\"pos\":[" + x + ",0,0]}";
    }

    private static Commit c(String id, String branch, long at, boolean draft, JsonObject project, Map<String, String> cells, String... parents) {
        return new Commit(id, List.of(parents), branch, "someone", at, "msg " + id, draft, project, cells, "pl-" + id);
    }

    // ------------------------------------------------------------------------------------------ names and payloads

    @Test
    void manifestNamesCarryTheBranch() {
        assertEquals("[LA2] Metro", SyncManifest.nameFor("Metro", "main"));
        assertEquals("[LA2:alex] Metro", SyncManifest.nameFor("Metro", "alex"));
        assertTrue(SyncManifest.isManifest("[LA2:alex] Metro"));
        assertTrue(SyncManifest.isManifest("[LA2] Metro"));
        assertFalse(SyncManifest.isManifest("[LA2:alex Metro"));
        assertEquals("Metro", SyncManifest.projectName("[LA2:alex] Metro"));
        assertEquals("alex", SyncManifest.branchOf("[LA2:alex] Metro"));
        assertEquals("main", SyncManifest.branchOf("[LA2] Metro"));
        assertNull(SyncManifest.branchOf("[Metro] wall"));
        // a ']' in a branch name must not end the bracket early
        assertEquals("[LA2:a)b] Metro", SyncManifest.nameFor("Metro", "a]b"));
    }

    @Test
    void payloadV2RoundTripsAndV1ReadsAsMainDraft() {
        CommitMeta meta = new CommitMeta("c1", List.of("c0"), "alex", false, "Add the roof");
        var pl = SyncManifest.parse(SyncManifest.payloadJson(project(obj("a", 0)), Map.of("s", "id1"), 5, "Alex", meta));
        assertEquals(meta, pl.meta());
        Commit commit = Commit.of(pl, "plc");
        assertEquals("c1", commit.id());
        assertEquals("alex", commit.branch());
        assertEquals("Add the roof", commit.title());

        var old = SyncManifest.parse(SyncManifest.payloadJson(project(), Map.of()));
        assertNull(old.meta());
        Commit legacy = Commit.of(old, "plc2");
        assertTrue(legacy.main());
        assertTrue(legacy.draft());
        assertTrue(legacy.parents().isEmpty());
    }

    // ------------------------------------------------------------------------------------------ graph

    /** m1 - m2 on main; alex forks at m1 with a1 - a2. */
    private static CommitGraph forked() {
        JsonObject p = project(obj("a", 0));
        List<Commit> l = new ArrayList<>();
        l.add(c("m1", "main", 10, false, p, Map.of()));
        l.add(c("m2", "main", 30, false, p, Map.of(), "m1"));
        l.add(c("a1", "alex", 20, false, p, Map.of(), "m1"));
        l.add(c("a2", "alex", 40, true, p, Map.of(), "a1"));
        return new CommitGraph(l);
    }

    @Test
    void branchesHeadsAndLog() {
        CommitGraph g = forked();
        assertEquals(List.of("main", "alex"), g.branches());
        assertEquals("m2", g.head("main").orElseThrow().id());
        assertEquals("a2", g.head("alex").orElseThrow().id());
        assertEquals("a1", g.sealedHead("alex").id());
        assertFalse(g.diverged("main"));
        assertEquals(List.of("a2", "a1", "m1"), g.log("a2").stream().map(Commit::id).toList());
        assertEquals("m1", g.mergeBase("m2", "a2").id());
        assertTrue(g.isAncestor("m1", "a2"));
        assertFalse(g.isAncestor("m2", "a2"));
        assertTrue(g.ahead("alex", "main"));
    }

    @Test
    void twoHeadsOnOneBranchAreDiverged() {
        JsonObject p = project();
        CommitGraph g = new CommitGraph(List.of(c("x", "main", 1, false, p, Map.of()), c("y", "main", 2, false, p, Map.of(), "x"),
                c("z", "main", 3, false, p, Map.of(), "x")));
        assertTrue(g.diverged("main"));
        assertEquals("z", g.head("main").orElseThrow().id());
    }

    @Test
    void sealedCopyBeatsTheDraftOfTheSameCommit() {
        JsonObject p = project();
        Commit draft = c("k", "main", 50, true, p, Map.of());
        Commit sealed = c("k", "main", 40, false, p, Map.of());
        assertFalse(new CommitGraph(List.of(draft, sealed)).get("k").draft());
        assertFalse(new CommitGraph(List.of(sealed, draft)).get("k").draft());
    }

    @Test
    void missingParentsEndTheWalk() {
        CommitGraph g = new CommitGraph(List.of(c("b", "main", 2, false, project(), Map.of(), "pruned")));
        assertEquals(List.of("b"), g.log("b").stream().map(Commit::id).toList());
        assertNull(g.mergeBase("b", "pruned"));
    }

    // ------------------------------------------------------------------------------------------ merge

    @Test
    void mergeCombinesBothBranches() {
        JsonObject base = project(obj("a", 0));
        JsonObject main = project(obj("a", 5));                  // main moved a
        JsonObject alex = project(obj("a", 0), obj("b", 1));     // alex added b
        CommitGraph g = new CommitGraph(List.of(
                c("m1", "main", 10, false, base, Map.of("a", "A1")),
                c("m2", "main", 30, false, main, Map.of("a", "A1"), "m1"),
                c("a1", "alex", 20, false, alex, Map.of("a", "A1", "b", "B1"), "m1")));
        var r = BranchOps.merge(g, g.head("main").orElseThrow(), g.head("alex").orElseThrow());
        assertEquals(BranchOps.Kind.MERGE, r.kind());
        assertEquals(0, r.conflicts());
        assertEquals(List.of("m2", "a1"), r.parents());
        assertEquals(Map.of("a", "A1", "b", "B1"), r.snapshot().cells());
        String text = r.snapshot().project().toString();
        assertTrue(text.contains("\"id\":\"b\""));
        assertTrue(text.contains("[5,0,0]"));
    }

    @Test
    void mergeCountsConflictsAndKeepsOneVersion() {
        JsonObject base = project(obj("a", 0));
        CommitGraph g = new CommitGraph(List.of(
                c("m1", "main", 10, false, base, Map.of("a", "A1")),
                c("m2", "main", 30, false, project(obj("a", 5)), Map.of("a", "A2"), "m1"),
                c("a1", "alex", 20, false, project(obj("a", 9)), Map.of("a", "A3"), "m1")));
        var r = BranchOps.merge(g, g.head("main").orElseThrow(), g.head("alex").orElseThrow());
        assertEquals(2, r.conflicts());   // the node and the cell file
        assertEquals("A3", r.snapshot().cells().get("a"));
        // the same result from the other side: both clients end with the same scene
        var back = BranchOps.merge(g, g.head("alex").orElseThrow(), g.head("main").orElseThrow());
        assertEquals(r.snapshot().project(), back.snapshot().project());
        assertEquals(r.snapshot().cells(), back.snapshot().cells());
    }

    @Test
    void fastForwardAndUpToDate() {
        JsonObject p1 = project(obj("a", 0)), p2 = project(obj("a", 1));
        CommitGraph g = new CommitGraph(List.of(
                c("m1", "main", 10, false, p1, Map.of()),
                c("a1", "alex", 20, false, p2, Map.of("x", "X"), "m1")));
        var ff = BranchOps.merge(g, g.head("main").orElseThrow(), g.head("alex").orElseThrow());
        assertEquals(BranchOps.Kind.FAST_FORWARD, ff.kind());
        assertEquals(p2, ff.snapshot().project());
        var same = BranchOps.merge(g, g.head("alex").orElseThrow(), g.head("main").orElseThrow());
        assertEquals(BranchOps.Kind.UP_TO_DATE, same.kind());
    }

    @Test
    void unrelatedHistoriesMergeAgainstAnEmptyBase() {
        CommitGraph g = new CommitGraph(List.of(
                c("m1", "main", 10, false, project(obj("a", 0)), Map.of()),
                c("a1", "alex", 20, false, project(obj("b", 0)), Map.of())));
        var r = BranchOps.merge(g, g.head("main").orElseThrow(), g.head("alex").orElseThrow());
        assertEquals(BranchOps.Kind.MERGE, r.kind());
        assertTrue(r.snapshot().project().toString().contains("\"id\":\"a\""));
        assertTrue(r.snapshot().project().toString().contains("\"id\":\"b\""));
    }

    @Test
    void cellMergeKeepsAChangeOverARemoval() {
        int[] n = {0};
        Map<String, String> m = BranchOps.mergeCells(Map.of("a", "1", "b", "1"), Map.of("a", "2"), Map.of("a", "1", "b", "3"), n);
        assertEquals(Map.of("a", "2", "b", "3"), m);   // ours changed a; theirs changed b where ours removed it: the change wins
        assertEquals(1, n[0]);
    }

    // ------------------------------------------------------------------------------------------ retention

    private static CommitGraph history() {
        JsonObject p = project();
        return new CommitGraph(List.of(
                c("c1", "main", 10, false, p, Map.of("a", "A1", "b", "B1")),
                c("c2", "main", 20, false, p, Map.of("a", "A2", "b", "B1"), "c1"),
                c("c3", "main", 30, true, p, Map.of("a", "A2", "b", "B2"), "c2"),
                c("b1", "alex", 25, true, p, Map.of("a", "A2", "b", "B1", "c", "C1"), "c2")));
    }

    @Test
    void aPushMayNotRemoveWhatAnotherCommitStillUses() {
        CommitGraph g = history();
        // the draft c3 replaces B1 by B2, but c1, c2 and alex's draft still list B1
        assertTrue(Retention.referenced(g, "c3").contains("B1"));
        // A2 is used by c2 and alex
        assertTrue(Retention.referenced(g, "c3").contains("A2"));
        // B2 only by c3 itself
        assertFalse(Retention.referenced(g, "c3").contains("B2"));
    }

    @Test
    void pruneRemovesOldCommitsAndOnlyTheirOrphans() {
        CommitGraph g = history();
        var p = Retention.olderThan(g, 25);      // c1 (10) and c2 (20) are old; heads c3 and b1 stay
        assertEquals(List.of("c1", "c2"), p.commits().stream().map(Commit::id).sorted().toList());
        // A1 only c1 used; A2 and B1 are still used by the kept heads
        assertTrue(p.placements().contains("A1"));
        assertFalse(p.placements().contains("A2"));
        assertFalse(p.placements().contains("B1"));
        assertTrue(p.placements().contains("pl-c1"));
    }

    @Test
    void deleteBranchKeepsMergedWorkAndNeverMain() {
        JsonObject p = project();
        CommitGraph g = new CommitGraph(List.of(
                c("m1", "main", 10, false, p, Map.of("a", "A1")),
                c("a1", "alex", 20, false, p, Map.of("a", "A1", "x", "X1"), "m1"),
                c("a2", "alex", 30, false, p, Map.of("a", "A1", "x", "X2"), "a1"),
                c("m2", "main", 40, false, p, Map.of("a", "A1", "x", "X1"), "m1", "a1")));  // main merged a1 only
        var d = Retention.deleteBranch(g, "alex");
        assertEquals(List.of("a2"), d.commits().stream().map(Commit::id).toList());
        assertTrue(d.placements().contains("X2"));
        assertFalse(d.placements().contains("X1"));   // main still lists it
        assertTrue(Retention.deleteBranch(g, "main").isEmpty());
    }

    @Test
    void graphLayoutOrdersChildrenBeforeParentsAndAssignsLanes() {
        JsonObject p = project();
        CommitGraph g = new CommitGraph(List.of(
                c("m1", "main", 10, false, p, Map.of()),
                c("m2", "main", 20, false, p, Map.of(), "m1"),
                c("a1", "alex", 25, false, p, Map.of(), "m1"),
                c("m3", "main", 30, false, p, Map.of(), "m2", "a1")));
        GraphLayout.Layout lay = GraphLayout.build(g);
        assertEquals(4, lay.rows().size());
        assertEquals("m3", lay.rows().get(0).commit().id());
        assertEquals("m1", lay.rows().get(3).commit().id());
        assertTrue(lay.laneCount() >= 2);
        for (GraphLayout.Edge e : lay.edges()) {
            assertTrue(e.fromRow() < e.toRow());
        }
        assertEquals(1, lay.edges().stream().filter(GraphLayout.Edge::merge).count());
        assertEquals(List.of("main"), lay.rows().get(0).heads());
        assertTrue(lay.rows().stream().filter(r -> r.commit().id().equals("a1")).findFirst().orElseThrow().heads().contains("alex"));

        GraphLayout.Layout alexOnly = GraphLayout.build(g, "alex");
        assertEquals(List.of("a1", "m1"), alexOnly.rows().stream().map(r -> r.commit().id()).toList());
        assertEquals(1, alexOnly.laneCount());
    }
}
