package dev.schemarray;

import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.level.block.state.BlockState;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

/**
 * The only place that touches Minecraft APIs which differ between versions (current screen, mouse button state,
 * block registry and block state parsing, the local player).
 */
public final class Compat {
    private Compat() {}

    /** The screen that is open right now, or null. */
    public static Screen screen() {
        return Minecraft.getInstance().screen;
    }

    /**
     * Whether the left mouse button is held right now. Asks GLFW: Minecraft's MouseHandler.isLeftPressed is not
     * updated while a screen handles the click, so it stays false inside menus.
     */
    public static boolean leftDown() {
        return GLFW.glfwGetMouseButton(Minecraft.getInstance().getWindow().handle(), GLFW.GLFW_MOUSE_BUTTON_LEFT) == GLFW.GLFW_PRESS;
    }

    /**
     * Parses "oak_log[axis=x]" (namespace optional) against the block registry. Throws a CommandSyntaxException with
     * a readable raw message when it is not a block state; tags, NBT and trailing text are refused.
     */
    public static BlockState parseBlockState(String text) throws CommandSyntaxException {
        StringReader reader = new StringReader(text);
        BlockState state = BlockStateParser.parseForBlock(BuiltInRegistries.BLOCK, reader, false).blockState();
        if (reader.canRead()) throw new SimpleCommandExceptionType(() -> "Unexpected text after the block state").createWithContext(reader);
        return state;
    }

    /** All block ids ("minecraft:stone", ...) in registration order. */
    public static List<String> blockIds() {
        List<String> ids = new ArrayList<>();
        for (Identifier id : BuiltInRegistries.BLOCK.keySet()) ids.add(id.toString());
        return ids;
    }

    /** The id of the block in the local player's main hand, or null (empty hand, not a block item, no player). */
    public static String mainHandBlockId() {
        var player = Minecraft.getInstance().player;
        if (player == null || !(player.getMainHandItem().getItem() instanceof BlockItem item)) return null;
        return BuiltInRegistries.BLOCK.getKey(item.getBlock()).toString();
    }

    /** The local player's block position, or null when there is no player. */
    public static BlockPos playerBlockPos() {
        var player = Minecraft.getInstance().player;
        return player == null ? null : player.blockPosition();
    }

    /** The local player's name, or "Shape Generator" when there is no player. */
    public static String playerName() {
        var player = Minecraft.getInstance().player;
        return player == null ? "Shape Generator" : player.getGameProfile().name();
    }
}
