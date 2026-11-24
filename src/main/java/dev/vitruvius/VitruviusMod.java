package dev.vitruvius;

import dev.vitruvius.editor.EditorMode;
import dev.vitruvius.editor.mc.Reconciler;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;

public final class VitruviusMod implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        migrateOldConfig();   // the mod used to be called Litematica Array (id schemarray)
        VitruviusPaths.fs();   // Liskov's filesystem (and its one-time copy of the old folders) before any setting is read
        EditorMode.init();   // F6 editor mode; needs no Litematica
        if (!FabricLoader.getInstance().isModLoaded("litematica")) return;
        LiskovIntegration.init();
        dev.vitruvius.editor.world.WorldOverlay.init();
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            Reconciler.onDisconnect();   // saves the open projects
        });
        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            try {
                Reconciler.tick(mc);
                dev.vitruvius.editor.EditorSession.tick(mc);   // undo stack, drift, autosave
            } catch (Throwable t) {
                System.out.println("[Vitruvius] editor failed: " + t);
            }
            try {
                LiskovIntegration.tick(mc);
                Folders.tick(mc);
            } catch (Throwable t) {
                System.out.println("[Vitruvius] folders failed: " + t);
            }
        });
    }

    /** Renames config/schemarray to config/vitruvius once, so projects, shapes and folders survive the rename. */
    private static void migrateOldConfig() {
        try {
            java.nio.file.Path cfg = FabricLoader.getInstance().getConfigDir();
            java.nio.file.Path old = cfg.resolve("schemarray"), now = cfg.resolve("vitruvius");
            if (java.nio.file.Files.isDirectory(old) && !java.nio.file.Files.exists(now)) {
                java.nio.file.Files.move(old, now);
                System.out.println("[Vitruvius] moved config/schemarray to config/vitruvius");
            }
        } catch (Exception e) {
            System.out.println("[Vitruvius] could not move the old config folder: " + e);
        }
    }
}
