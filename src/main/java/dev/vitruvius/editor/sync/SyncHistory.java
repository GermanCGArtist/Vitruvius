package dev.vitruvius.editor.sync;

import com.google.gson.JsonObject;
import dev.vitruvius.editor.EditorSession;
import dev.vitruvius.editor.core.Node;
import dev.vitruvius.editor.core.ObjectNode;
import dev.vitruvius.editor.core.ProjectNode;
import dev.vitruvius.editor.core.Scene;
import dev.vitruvius.editor.io.FormatException;
import dev.vitruvius.editor.io.ProjectIo;
import dev.vitruvius.editor.io.ProjectLayout;
import dev.vitruvius.editor.mc.ReconcileCore;
import dev.vitruvius.editor.sync.history.BranchOps;
import dev.vitruvius.editor.sync.history.Commit;
import dev.vitruvius.editor.sync.history.CommitGraph;
import dev.vitruvius.editor.sync.history.CommitMeta;
import dev.vitruvius.editor.sync.history.Retention;
import dev.vitruvius.editor.ui.ToastQueue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Branches and version history of a shared project, on the client: which branch the open copy is on, committing the draft,
 * switching branch, merging one branch into the one that is checked out, restoring an old commit and cleaning up history.
 * The commits themselves are manifest placements ({@link CommitGraph} reads them); the pure rules are in
 * {@code sync.history}. Everything that changes the open project (switch, merge, restore) is one {@link Op}: the files it
 * needs are fetched first, then the project is brought to the new state in place, and a commit follows when one was asked for.
 */
public final class SyncHistory {
    private static final int FETCH_EVERY = 20;
    private static final int FETCH_POLLS = 45;
    private static final int SETTLE_TICKS = 80;

    private final SyncService svc;
    private final Map<UUID, String> commitMessage = new HashMap<>();
    private Op op;
    private int ticks;

    SyncHistory(SyncService svc) {
        this.svc = svc;
    }

    /** What the History window shows for a project. */
    public record View(String me, String branch, CommitGraph graph, boolean canCommit, boolean busy) {}

    private static final class Op {
        final ProjectNode project;
        final JsonObject target;
        final Map<String, String> cells;
        final String done;
        final boolean force;
        // what the copy records once the target is in
        final String branch, parents;
        final boolean checkout;
        final String thenCommit;
        int waited, settleUntil = -1;

        Op(ProjectNode p, JsonObject target, Map<String, String> cells, String done, boolean force, String branch, String parents,
           boolean checkout, String thenCommit) {
            this.project = p;
            this.target = target;
            this.cells = cells;
            this.done = done;
            this.force = force;
            this.branch = branch;
            this.parents = parents;
            this.checkout = checkout;
            this.thenCommit = thenCommit;
        }
    }

    // ------------------------------------------------------------------------------------------ branch of a copy

    /**
     * The branch this copy of the project pushes to. Chosen at the first push: main for the player who shared the project,
     * a branch named after the player when somebody else already pushed it (or when the project was received).
     */
    public String branchOf(ProjectNode p) {
        String b = SyncPlan.branch(p.extra());
        if (b != null) return b;
        if (SyncPlan.recordedManifest(p.extra()) != null) return CommitMeta.MAIN;   // pushed before branches existed
        String me = SyncService.me();
        if (me.isEmpty()) return CommitMeta.MAIN;
        boolean others = false;
        for (Commit c : svc.graph(p).all()) if (!c.by().isEmpty() && !c.by().equals(me)) others = true;
        return others ? CommitMeta.cleanBranch(me) : CommitMeta.MAIN;
    }

    /** A project received from the server starts on this player's own branch, based on what was received. */
    void startOnBranch(ProjectNode tree, SyncManifest.Payload received) {
        String me = SyncService.me();
        CommitMeta m = received.meta();
        boolean mine = !me.isEmpty() && me.equals(received.by());
        String branch = mine ? (m == null ? CommitMeta.MAIN : m.branch()) : me.isEmpty() ? CommitMeta.MAIN : CommitMeta.cleanBranch(me);
        SyncPlan.setBranch(tree.extra(), branch);
        if (m != null) SyncPlan.setParent(tree.extra(), m.draft() ? (m.parents().isEmpty() ? null : m.parents().get(0)) : m.commit());
    }

    String takeCommitMessage(ProjectNode p) {
        return commitMessage.remove(p.id());
    }

    /** Whether an operation of this class is changing the project (live pushes wait). */
    boolean holds(ProjectNode p) {
        return op != null && op.project == p;
    }

    public boolean busy() {
        return op != null;
    }

    public View view(ProjectNode p) {
        String branch = branchOf(p);
        return new View(SyncService.me(), branch, svc.graph(p), canCommit(p), op != null);
    }

    /** Whether there is something to commit: a draft on the server or changes not pushed yet. */
    public boolean canCommit(ProjectNode p) {
        return SyncPlan.draft(p.extra()) != null || svc.pending(p) > 0;
    }

    // ------------------------------------------------------------------------------------------ commit and branch

    /** Seals the working changes as a commit with a message (pushing them first when needed). */
    public void commit(ProjectNode p, String message) {
        if (!canCommit(p)) {
            toast(ToastQueue.Level.INFO, "Nothing to commit");
            return;
        }
        commitMessage.put(p.id(), message == null ? "" : message.strip());
        svc.push(p);
    }

    /** Starts a new branch at the current state: the working changes move to it. */
    public void newBranch(ProjectNode p, String name) {
        String b = CommitMeta.cleanBranch(name);
        if (name == null || name.isBlank() || CommitMeta.isMain(b)) {
            toast(ToastQueue.Level.WARN, "Choose a name other than main for the branch");
            return;
        }
        CommitGraph g = svc.graph(p);
        if (g.branches().contains(b)) {
            toast(ToastQueue.Level.WARN, "There is a branch called " + b + " already");
            return;
        }
        String cur = branchOf(p);
        Commit base = g.sealedHead(cur);
        SyncPlan.setBranch(p.extra(), b);
        SyncPlan.setParent(p.extra(), base == null ? null : base.id());
        SyncPlan.setDraft(p.extra(), null);
        SyncPlan.clearManifest(p.extra());      // the old branch's manifest stays where it is
        svc.push(p);
        toast(ToastQueue.Level.INFO, "Started branch " + b + (base == null ? "" : " from " + base.shortId()));
    }

    /** Switches the open project to the head of another branch. Needs everything pushed or committed first. */
    public void checkout(ProjectNode p, String branch) {
        if (op != null) return;
        if (svc.pending(p) > 0) {
            toast(ToastQueue.Level.WARN, "Push or commit your changes before switching branches");
            return;
        }
        CommitGraph g = svc.graph(p);
        Commit head = g.head(branch).orElse(null);
        if (head == null) {
            toast(ToastQueue.Level.WARN, "The branch " + branch + " has no commits");
            return;
        }
        String parent = head.draft() ? (head.parents().isEmpty() ? "" : head.parents().get(0)) : head.id();
        start(new Op(p, head.project(), head.cells(), "Switched to " + branch, true, branch, parent, true, null), head.draft() && head.by().equals(SyncService.me()) ? head.id() : null);
    }

    /** Merges the newest commit of another branch into the checked-out one and commits the result. */
    public void merge(ProjectNode p, String from) {
        if (op != null) return;
        String cur = branchOf(p);
        if (from.equals(cur)) return;
        if (canCommit(p)) {
            toast(ToastQueue.Level.WARN, "Commit your changes before merging");
            return;
        }
        CommitGraph g = svc.graph(p);
        Commit into = g.head(cur).orElse(null);
        Commit src = g.sealedHead(from) != null ? g.sealedHead(from) : g.head(from).orElse(null);
        if (into == null || src == null) {
            toast(ToastQueue.Level.WARN, "Nothing to merge");
            return;
        }
        BranchOps.MergeResult r = BranchOps.merge(g, into, src);
        if (r.kind() == BranchOps.Kind.UP_TO_DATE) {
            toast(ToastQueue.Level.INFO, cur + " already contains " + from);
            return;
        }
        String parents = String.join(",", r.parents());
        String msg = "Merge " + from + " into " + cur + (r.conflicts() > 0 ? " (" + r.conflicts() + " edited by both)" : "");
        start(new Op(p, r.snapshot().project(), r.snapshot().cells(), msg, true, cur, parents, false, msg), null);
    }

    /** Brings the project back to an old commit and commits that as a new commit on the checked-out branch. */
    public void restore(ProjectNode p, String commitId) {
        if (op != null) return;
        if (canCommit(p)) {
            toast(ToastQueue.Level.WARN, "Commit your changes before restoring an older state");
            return;
        }
        CommitGraph g = svc.graph(p);
        Commit c = g.get(commitId);
        if (c == null) return;
        String cur = branchOf(p);
        Commit head = g.head(cur).orElse(null);
        String msg = "Restore " + c.shortId() + (c.draft() ? "" : ": " + c.title());
        start(new Op(p, c.project(), c.cells(), msg, true, cur, head == null ? "" : head.draft() ? String.join(",", head.parents()) : head.id(), false, msg), null);
    }

    // ------------------------------------------------------------------------------------------ cleanup

    /** What a cleanup of sealed commits older than a number of days would remove. */
    public Retention.Prune previewOlderThan(ProjectNode p, int days) {
        return Retention.olderThan(svc.graph(p), System.currentTimeMillis() - days * 86_400_000L);
    }

    public Retention.Prune previewDeleteBranch(ProjectNode p, String branch) {
        if (branch.equals(branchOf(p))) return new Retention.Prune(List.of(), List.of());
        return Retention.deleteBranch(svc.graph(p), branch);
    }

    /** Removes the placements of a cleanup from the server (the ones the server refuses stay). */
    public void cleanup(Retention.Prune plan) {
        for (String id : plan.placements()) svc.removePlacement(id);
        toast(ToastQueue.Level.INFO, "Removed " + plan.commits().size() + " commit" + (plan.commits().size() == 1 ? "" : "s")
                + " and " + plan.placements().size() + " placement" + (plan.placements().size() == 1 ? "" : "s") + " from the server");
    }

    // ------------------------------------------------------------------------------------------ the operation

    private void start(Op o, String draftToKeep) {
        op = o;
        // a switch to a draft of mine continues that draft; everything else starts a new one on the next push
        SyncPlan.setDraft(o.project.extra(), draftToKeep);
    }

    void tick(Scene scene, ReconcileCore core) {
        ticks++;
        if (op == null) return;
        Op o = op;
        if (!scene.projects().contains(o.project)) {
            op = null;
            return;
        }
        try {
            if (o.settleUntil < 0) {
                if (ticks % FETCH_EVERY != 0) return;
                if (!apply(o, core)) return;
                o.settleUntil = ticks + SETTLE_TICKS;
                return;
            }
            if (ticks < o.settleUntil) return;
            op = null;
            if (o.thenCommit != null) {
                commit(o.project, o.thenCommit);
            } else if (o.checkout) {
                SyncService.Desired d = svc.desired(o.project, core);
                SyncPlan.clearManifest(o.project.extra());
                SyncPlan.markManifestSynced(o.project.extra(), d.manifestSig());
                SyncPlan.setContent(o.project.extra(), d.contentSig());
            }
        } catch (RuntimeException e) {
            op = null;
            toast(ToastQueue.Level.ERROR, "History: " + e);
        }
    }

    /** Fetches what is missing and applies the target. Returns false while files are still on their way (or after a failure). */
    private boolean apply(Op o, ReconcileCore core) {
        ProjectNode p = o.project;
        Path dir = core.projectDir(p);
        if (dir == null) {
            fail(o, "The project has no folder");
            return false;
        }
        ProjectNode fresh;
        try {
            fresh = ProjectIo.fromJson(o.target.toString());
        } catch (FormatException e) {
            fail(o, "That state cannot be read: " + e.getMessage());
            return false;
        }
        Map<String, SyncPlan.Pushed> recorded = SyncPlan.recordedCells(p.extra());
        boolean waiting = false;
        int missing = 0;
        for (Node n : fresh.subtree()) {
            if (!(n instanceof ObjectNode obj) || obj.file().isEmpty()) continue;
            String key = obj.id().toString();
            String id = o.cells.get(key);
            if (id == null) continue;
            Path target;
            try {
                target = ProjectLayout.resolveInside(dir, obj.file());
            } catch (IllegalArgumentException e) {
                continue;
            }
            SyncPlan.Pushed mine = recorded.get(key);
            boolean needed = !Files.exists(target) || o.force && (mine == null || !mine.id().equals(id));
            if (!needed) continue;
            SyncmaticaBridge.Remote src = svc.remoteOf(id);
            if (src == null) {
                missing++;
                continue;
            }
            Path local = SyncmaticaBridge.localFile(src);
            if (local == null) {
                svc.startDownload(src);
                waiting = true;
                continue;
            }
            try {
                Files.createDirectories(target.getParent());
                Files.copy(local, target, StandardCopyOption.COPY_ATTRIBUTES, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                System.out.println("[Vitruvius] cannot copy " + local + ": " + e.getMessage());
            }
        }
        if (waiting) {
            if (++o.waited >= FETCH_POLLS) fail(o, "Timed out waiting for schematic files from the server");
            return false;
        }
        Set<UUID> affected = ProjectApply.apply(p, fresh);
        SyncPlan.setBranch(p.extra(), o.branch);
        SyncPlan.setParent(p.extra(), o.parents.isEmpty() ? null : o.parents);
        SyncService.writeBase(dir, o.target);
        EditorSession s = EditorSession.get();
        if (s != null) s.invalidateBoxes();
        toast(missing > 0 ? ToastQueue.Level.WARN : ToastQueue.Level.INFO,
                o.done + " (" + affected.size() + " node" + (affected.size() == 1 ? "" : "s") + " changed"
                        + (missing > 0 ? ", " + missing + " file" + (missing == 1 ? "" : "s") + " no longer on the server" : "") + ")");
        return true;
    }

    private void fail(Op o, String text) {
        op = null;
        toast(ToastQueue.Level.ERROR, text);
    }

    private static void toast(ToastQueue.Level level, String text) {
        EditorSession s = EditorSession.get();
        if (s != null) s.toasts.add(level, text);
    }

}
