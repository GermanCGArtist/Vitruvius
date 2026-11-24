package dev.vitruvius.editor.sync;

import dev.liskov.core.acl.AclRole;
import dev.liskov.net.LiskovSyncClient;
import dev.vitruvius.editor.core.FolderNode;
import dev.vitruvius.editor.core.Node;
import dev.vitruvius.editor.core.ProjectNode;
import dev.vitruvius.editor.mc.ProjectManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.PlayerInfo;

/**
 * Two-player check of sharing on a real server, for automated runs only: off unless the game is started with
 * {@code -Dvitruvius.sharetest=a} (the sharing player) or {@code =b} (the other one, named by
 * {@code -Dvitruvius.sharetest.peer} on a). Prints {@code VITSHARE <role> PASS} or {@code VITSHARE <role> FAIL <why>}.
 *
 * <p>A makes and shares project "ShareTest" with a folder "fa", gives the peer EDIT in Liskov, waits for the peer's
 * folder "fb" to be merged in, then adds "fa2". B opens the shared project, adds "fb" (pushed once its access arrives),
 * and waits for "fa2". Then both edit at once, from the same version: A adds "ca" the moment the server takes "fa2",
 * B adds "cb" the moment "fa2" arrives, and both push; each must end with both folders and nothing left to push.
 */
final class ShareSelfTest {
    private ShareSelfTest() {}

    static final String ROLE = System.getProperty("vitruvius.sharetest");
    private static final String PEER = System.getProperty("vitruvius.sharetest.peer", "VitB");
    private static final String NAME = "ShareTest";
    private static final int TIMEOUT_TICKS = 20 * 300;

    private static int step;
    private static int stepAt;
    private static int ticks;

    static void tick(LiskovShare share, ProjectManager pm) {
        if (step < 0) return;
        if (++ticks > TIMEOUT_TICKS) {
            done("FAIL timeout at step " + step);
            return;
        }
        if (share.serverDrive() == null || !pm.inWorld()) return;
        if ("a".equals(ROLE)) sharer(share, pm);
        else if ("b".equals(ROLE)) peer(share, pm);
    }

    private static void sharer(LiskovShare share, ProjectManager pm) {
        ProjectNode p = shared(pm);
        switch (step) {
            case 0 -> {
                if (ticks < 60) return;   // let the HELLO and the session settle
                ProjectNode local = pm.newProject(NAME);
                local.addChild(new FolderNode("fa"));
                pm.saveAll();
                share.share(local);
                log("shared");
                step = 1;
            }
            case 1 -> {
                if (p == null || share.busy() || share.pending(p) > 0) return;
                PlayerInfo peer = playerNamed(PEER);
                if (peer == null) return;
                LiskovSyncClient.get().requestAclMutate(share.repoIdOf(p), peer.getProfile().id().toString(), PEER, false, AclRole.EDIT);
                log("granted EDIT to " + PEER);
                step = 2;
            }
            case 2 -> {
                if (p == null || !has(p, "fb")) return;
                log("got fb from the peer");
                p.addChild(new FolderNode("fa2"));
                stepAt = ticks;
                step = 3;
            }
            case 3 -> {
                // past the count, the quiet wait and the push: the server has taken fa2
                if (p == null || ticks - stepAt < 30 || share.busy() || share.pending(p) > 0) return;
                p.addChild(new FolderNode("ca"));   // the server has fa2 now, which is when B sees it: both edit at once
                log("added ca");
                step = 4;
            }
            case 4 -> settled(share, p);
            default -> {}
        }
    }

    /** Both concurrent edits are in, and nothing is waiting to be pushed, for two seconds. */
    private static int settledFor;

    private static void settled(LiskovShare share, ProjectNode p) {
        boolean ok = p != null && !share.busy() && share.pending(p) == 0
                && has(p, "fa") && has(p, "fb") && has(p, "fa2") && has(p, "ca") && has(p, "cb");
        settledFor = ok ? settledFor + 1 : 0;
        if (settledFor >= 40) done("PASS");
    }

    private static void peer(LiskovShare share, ProjectManager pm) {
        ProjectNode p = shared(pm);
        switch (step) {
            case 0 -> {
                for (LiskovShare.Entry e : share.sharedProjectList()) {
                    if (e.name().equals(NAME) && !share.receiving()) {
                        share.receive(e);
                        log("opening " + e.relativePath());
                        step = 1;
                    }
                }
            }
            case 1 -> {
                if (p == null || !has(p, "fa")) return;
                p.addChild(new FolderNode("fb"));
                log("added fb");
                step = 2;
            }
            case 2 -> {
                if (p == null || !has(p, "fa2")) return;
                p.addChild(new FolderNode("cb"));
                log("added cb");
                step = 3;
            }
            case 3 -> settled(share, p);
            default -> {}
        }
    }

    private static ProjectNode shared(ProjectManager pm) {
        for (ProjectNode p : pm.projects()) if (p.name().equals(NAME) && p.sync() == ProjectNode.Sync.SHARED) return p;
        return null;
    }

    private static boolean has(ProjectNode p, String name) {
        for (Node n : p.subtree()) if (n instanceof FolderNode && n.name().equals(name)) return true;
        return false;
    }

    private static PlayerInfo playerNamed(String name) {
        var conn = Minecraft.getInstance().getConnection();
        if (conn == null) return null;
        for (PlayerInfo i : conn.getOnlinePlayers()) if (name.equals(i.getProfile().name())) return i;
        return null;
    }

    private static void log(String what) {
        System.out.println("[Vitruvius] VITSHARE " + ROLE + " " + what);
    }

    private static void done(String what) {
        step = -1;
        log(what);
    }
}
