package dev.schemarray;

import dev.schemarray.editor.EditorMode;
import dev.schemarray.editor.mc.Reconciler;
import net.fabricmc.api.ClientModInitializer;
import dev.schemarray.shape.mc.ShapePreview;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;

public final class SchemArrayMod implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        EditorMode.init();   // F6 editor mode; needs no Litematica
        if (!FabricLoader.getInstance().isModLoaded("litematica")) return;
        ThumbnailCompat.register();
        dev.schemarray.editor.world.WorldOverlay.init();
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            ShapePreview.clear();
            Reconciler.onDisconnect();   // saves the open projects
        });
        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            try {
                Reconciler.tick(mc);   // before ArraySync: it decides which arrays the editor owns
                dev.schemarray.editor.EditorSession.tick(mc);   // undo stack, drift, autosave
            } catch (Throwable t) {
                System.out.println("[SchematicArray] editor failed: " + t);
            }
            try {
                ArraySync.tick(mc);
            } catch (Throwable t) {
                System.out.println("[SchematicArray] sync failed: " + t);
            }
            try {
                Folders.tick(mc);
            } catch (Throwable t) {
                System.out.println("[SchematicArray] folders failed: " + t);
            }
        });
    }
}
