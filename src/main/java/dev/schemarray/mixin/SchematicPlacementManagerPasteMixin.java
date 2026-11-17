package dev.schemarray.mixin;

import dev.schemarray.ArrayPaste;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacementManager;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/**
 * Pasting an array original also pastes its copies. In Litematica's 4-argument pastePlacementToWorld the task paths
 * (command and direct) wrap the placement in Collections.singletonList(..) for the task constructor; we hand the task
 * the whole array there instead. The Servux path sends one placement, so copies are pasted after it. The mcfunction
 * path is left alone. require makes a changed Litematica fail at launch instead of silently doing nothing.
 */
@Mixin(value = SchematicPlacementManager.class, remap = false)
abstract class SchematicPlacementManagerPasteMixin {
    private static final String PASTE = "pastePlacementToWorld(Lfi/dy/masa/litematica/schematic/placement/SchematicPlacement;ZZLnet/minecraft/client/Minecraft;)V";

    /** Both TaskPasteSchematicPerChunkCommand and ...Direct call singletonList(placement); each gets the whole array. */
    @Redirect(method = PASTE,
            at = @At(value = "INVOKE", target = "Ljava/util/Collections;singletonList(Ljava/lang/Object;)Ljava/util/List;"),
            require = 2, expect = 2, remap = false)
    private List<SchematicPlacement> schemarray$arrayList(Object placement) {
        List<SchematicPlacement> list = ArrayPaste.placementsFor((SchematicPlacement) placement);
        ArrayPaste.announce(list.size());
        return list;
    }

    /** Servux path: right after the original is sent, send each copy through the same method. */
    @Inject(method = PASTE,
            at = @At(value = "INVOKE", shift = At.Shift.AFTER,
                    target = "Lfi/dy/masa/litematica/network/ServuxLitematicaHandler;encodeClientData(Lfi/dy/masa/malilib/network/IClientPayloadData;)V"),
            require = 1, remap = false)
    private void schemarray$servuxCopies(SchematicPlacement placement, boolean setCommandBlocks, boolean message, Minecraft mc, CallbackInfo ci) {
        List<SchematicPlacement> copies = ArrayPaste.copiesOf(placement);
        if (copies.isEmpty()) return;
        ArrayPaste.announce(copies.size() + 1);
        ArrayPaste.reentrant = true;
        try {
            for (SchematicPlacement c : copies) ((SchematicPlacementManager) (Object) this).pastePlacementToWorld(c, setCommandBlocks, message, mc);
        } finally {
            ArrayPaste.reentrant = false;
        }
    }
}
