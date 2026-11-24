package dev.vitruvius;

import dev.liskov.core.LiskovDrives;
import dev.liskov.core.LiskovPaths;
import dev.liskov.core.vcs.VcsCommit;
import dev.liskov.core.vcs.VcsHead;
import dev.liskov.core.vcs.VcsStore;
import dev.liskov.net.LiskovReloadBus;
import dev.vitruvius.editor.core.ProjectNode;
import dev.vitruvius.editor.io.ProjectLayout;
import dev.vitruvius.editor.mc.ProjectManager;
import net.minecraft.client.Minecraft;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Project versions in Liskov's version store ({@code <game>/liskov/.liskov-vcs/}), shown in the Liskov Explorer's
 * inspector for the project's {@code project.vproj}:
 * <ul>
 *   <li>Every save that {@link ProjectManager} reports (Ctrl S as a named version, autosave and close as autosaves)
 *       records the project folder: {@code project.vproj} and {@code files/**}, not the hidden {@code .cache}/{@code .trash}.
 *       Liskov drops identical autosaves and prunes old ones itself; a named save identical to the last version is skipped.
 *   <li>Restoring a version in the explorer rewrites the folder and fires a reload notice; an open project is then read
 *       again from disk (without saving over the restored files) and the undo history is cleared.
 * </ul>
 * Snapshots run on one background thread so a save never waits for reading and hashing the schematics.
 */
public final class LiskovHistory {
    private LiskovHistory() {}

    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "Vitruvius history");
        t.setDaemon(true);
        return t;
    });
    private static VcsStore vcs;
    private static boolean listening;

    private static synchronized VcsStore store() throws IOException {
        if (vcs == null) vcs = new VcsStore(VitruviusPaths.fs().vcsRootDir());
        return vcs;
    }

    /** {@link ProjectManager.HistoryHook}: queues a snapshot of the saved project. Client thread. */
    public static void onSaved(ProjectNode project, Path projectDir, boolean explicit) {
        Minecraft mc = Minecraft.getInstance();
        String uuid = mc.player != null ? mc.player.getUUID().toString() : "local";
        String name = mc.player != null ? mc.player.getGameProfile().name() : "Player";
        String projectName = project.name();
        Path localRoot = VitruviusPaths.fs().localRoot();
        WORKER.execute(() -> {
            try {
                snapshot(store(), localRoot, projectDir, uuid, name, projectName, explicit);
            } catch (IOException | RuntimeException e) {
                System.out.println("[Vitruvius] cannot record a version of " + projectName + ": " + e.getMessage());
            }
        });
    }

    /** The path Liskov keys a project's history by: its project.vproj relative to the local drive, or null outside it. */
    public static String repoPath(Path localRoot, Path projectDir) {
        Path root = localRoot.toAbsolutePath().normalize(), dir = projectDir.toAbsolutePath().normalize();
        if (!dir.startsWith(root) || dir.equals(root)) return null;
        return root.relativize(dir.resolve(ProjectLayout.PROJECT_FILE)).toString().replace(java.io.File.separatorChar, '/');
    }

    /**
     * Records the project folder as a version. Returns the commit, or null when the project is outside the local
     * drive or a named save would be identical to the last version.
     */
    public static VcsCommit snapshot(VcsStore vcs, Path localRoot, Path projectDir, String authorUuid, String authorName,
                                     String projectName, boolean explicit) throws IOException {
        String rel = repoPath(localRoot, projectDir);
        if (rel == null) return null;
        String repoId = VcsStore.repoIdFor(rel);
        Map<String, byte[]> files = dev.vitruvius.editor.sync.ShareCore.bundle(projectDir);
        if (!files.containsKey(ProjectLayout.PROJECT_FILE)) return null;
        if (explicit && sameAsTip(vcs, repoId, files)) return null;
        return vcs.snapshot(LiskovDrives.LOCAL_DRIVE_ID, repoId, authorUuid, authorName,
                explicit ? "Save " + projectName : "Autosave", false, !explicit, files);
    }

    private static boolean sameAsTip(VcsStore vcs, String repoId, Map<String, byte[]> files) {
        VcsHead head = vcs.loadHead(LiskovDrives.LOCAL_DRIVE_ID, repoId);
        String tip = head.branchTips.get(head.activeBranch);
        if (tip == null) return false;
        Map<String, String> hashes = new LinkedHashMap<>();
        for (var e : files.entrySet()) hashes.put(e.getKey(), LiskovPaths.sha256Hex(e.getValue()));
        for (VcsCommit c : vcs.loadCommits(LiskovDrives.LOCAL_DRIVE_ID, repoId)) {
            if (tip.equals(c.commitId)) return hashes.equals(c.tree);
        }
        return false;
    }

    /** Reads open projects again when Liskov restores a version of them. Call once. */
    public static synchronized void listen(ProjectManager pm) {
        if (listening) return;
        listening = true;
        LiskovReloadBus.subscribe(notice -> {
            if (!LiskovDrives.LOCAL_DRIVE_ID.equals(notice.driveId())) return;
            Minecraft.getInstance().execute(() -> reloaded(pm, notice.relativePath()));
        });
    }

    private static void reloaded(ProjectManager pm, String relativePath) {
        Path localRoot = VitruviusPaths.fs().localRoot();
        for (ProjectNode p : List.copyOf(pm.projects())) {
            if (!relativePath.equals(repoPath(localRoot, pm.projectDir(p)))) continue;
            UUID id = p.id();
            ProjectNode fresh = pm.revertToDisk(id);
            if (fresh != null) dev.vitruvius.editor.EditorSession.projectReplaced(fresh);
        }
    }
}
