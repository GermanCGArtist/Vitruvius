package dev.vitruvius.shape.mc;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.vitruvius.Compat;
import dev.vitruvius.shape.BlockEntry;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;

/** Block state strings <-> Minecraft block states, for the screen and the schematic builder. */
public final class BlockParsing {
    private BlockParsing() {}


    /** Parses "oak_log[axis=x]" (namespace optional). Null if invalid. */
    public static BlockState parse(String blockState) {
        try {
            return Compat.parseBlockState(blockState == null ? "" : blockState.trim());
        } catch (CommandSyntaxException | RuntimeException e) {
            return null;
        }
    }

    /** Human readable reason why the string does not parse, or null when it is valid. */
    public static String error(String blockState) {
        String text = blockState == null ? "" : blockState.trim();
        if (text.isEmpty()) return "Block is empty";
        try {
            Compat.parseBlockState(text);
            return null;
        } catch (CommandSyntaxException e) {
            return e.getRawMessage().getString();
        } catch (RuntimeException e) {
            return "Invalid block";
        }
    }

    /** The block state string of the item in the player's main hand (with its default state), or null. */
    public static String fromMainHand() {
        return Compat.mainHandBlockId();
    }

    /**
     * Parses one block field: "stone" -> [stone x1]; "60%stone_bricks,30%cracked_stone_bricks" -> weighted entries
     * (a part without "%" weighs 1). Does not validate the block names (use error() per entry for that).
     */
    public static List<BlockEntry> parseEntries(String text) {
        List<BlockEntry> out = new ArrayList<>();
        if (text == null) return out;
        for (String part : splitTopLevel(text)) {
            String s = part.trim();
            if (s.isEmpty()) continue;
            int weight = 1;
            int pct = s.indexOf('%');
            if (pct > 0 && s.substring(0, pct).trim().matches("[0-9]{1,9}")) {
                weight = Integer.parseInt(s.substring(0, pct).trim());
                s = s.substring(pct + 1).trim();
            }
            out.add(new BlockEntry(s, weight));
        }
        return out;
    }

    /** Inverse of parseEntries: "60%a,30%b" (or just "a" for one entry with weight 1). */
    public static String formatEntries(List<BlockEntry> entries) {
        if (entries.size() == 1 && entries.get(0).weight() == 1) return entries.get(0).blockState();
        StringBuilder sb = new StringBuilder();
        for (BlockEntry e : entries) {
            if (sb.length() > 0) sb.append(',');
            sb.append(e.weight()).append('%').append(e.blockState());
        }
        return sb.toString();
    }

    /** Splits at commas that are not inside [...] (block state properties contain commas). */
    private static List<String> splitTopLevel(String text) {
        List<String> parts = new ArrayList<>();
        int depth = 0, start = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '[' || c == '{') depth++;
            else if ((c == ']' || c == '}') && depth > 0) depth--;
            else if (c == ',' && depth == 0) {
                parts.add(text.substring(start, i));
                start = i + 1;
            }
        }
        parts.add(text.substring(start));
        return parts;
    }
}
