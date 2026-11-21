package dev.vitruvius.editor.cam;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.phys.Vec3;

/**
 * In spectator mode the player follows the editor camera, so the server streams the chunks around it (a spectator's
 * client-side movement is accepted). In every other game mode the player stays where it is.
 *
 * <p>The player moves at most {@link FollowStep#MAX_STEP} blocks per tick, from where it is now: a server refuses
 * longer moves ("moved too quickly") and puts the player back, which would repeat every tick while the camera is far
 * away. Starting from the current position also picks up the server's correction when it does put the player back.
 */
public final class SpectatorFollow {
    private SpectatorFollow() {}

    /** Call every client tick while editor mode is on. */
    public static void tick(Minecraft mc, LocalPlayer player, EditorCamera cam) {
        if (!player.isSpectator()) return;
        Vec3 eye = cam.position();
        double[] p = FollowStep.next(player.getX(), player.getY(), player.getZ(),
                eye.x, eye.y - player.getEyeHeight(), eye.z, FollowStep.MAX_STEP);
        // snapTo sets position and rotation and the "old" values, so the player does not interpolate behind the camera
        player.snapTo(p[0], p[1], p[2], cam.yaw(), cam.pitch());
        player.setDeltaMovement(Vec3.ZERO);
    }

    /** Whether the player is still on its way to the camera (spectator only; it walks at most 8 blocks per tick). */
    public static boolean catchingUp(Minecraft mc, EditorCamera cam) {
        if (mc.player == null || !mc.player.isSpectator()) return false;
        Vec3 eye = cam.position();
        double dx = eye.x - mc.player.getX(), dy = eye.y - mc.player.getEyeHeight() - mc.player.getY(), dz = eye.z - mc.player.getZ();
        return dx * dx + dy * dy + dz * dz > 1;
    }

    /** Whether the chunk under the camera has not arrived yet (spectator only; the HUD shows "loading chunks"). */
    public static boolean chunksLoading(Minecraft mc, EditorCamera cam) {
        if (mc.level == null || mc.player == null || !mc.player.isSpectator()) return false;
        Vec3 p = cam.position();
        int cx = (int) Math.floor(p.x) >> 4, cz = (int) Math.floor(p.z) >> 4;
        return mc.level.getChunkSource().getChunk(cx, cz, ChunkStatus.FULL, false) == null;
    }
}
