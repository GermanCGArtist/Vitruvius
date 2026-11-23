package dev.vitruvius.editor.io;

import dev.vitruvius.editor.core.Box;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The "Build job for Manhattan / LLM" text: every Litematica placement of a selection with what an LLM needs to write
 * the Manhattan script that builds it (name, file, pose, world box, the blocks in it), as a Manhattan script whose
 * notes are {@code //} comments and that ends with one {@code schem_build} per placement. Pure (no Minecraft).
 */
public final class BuildJobText {
    private BuildJobText() {}

    /** How many kinds of blocks a placement line lists (the rest are summed up as "other"). */
    public static final int TOP_BLOCKS = 12;

    /**
     * One placement. {@code cell} is "base" or the array cell; {@code blocks} counts the non-air blocks of the
     * schematic by block id (null when the file could not be read).
     */
    public record Entry(String placement, String object, String cell, String project, String file, String rotation,
                        String mirror, boolean enabled, Box world, Map<String, Long> blocks) {}

    public static String build(String label, List<Entry> entries) {
        List<Entry> on = new ArrayList<>();
        for (Entry e : entries) if (e.enabled()) on.add(e);
        Box all = null;
        Map<String, Long> total = new LinkedHashMap<>();
        long totalBlocks = 0;
        for (Entry e : on) {
            all = all == null ? e.world() : all.union(e.world());
            if (e.blocks() != null) {
                for (var b : e.blocks().entrySet()) {
                    total.merge(b.getKey(), b.getValue(), Long::sum);
                    totalBlocks += b.getValue();
                }
            }
        }
        StringBuilder sb = new StringBuilder();
        sb.append("// Vitruvius build job ").append(label).append(": ").append(on.size())
                .append(on.size() == 1 ? " Litematica placement" : " Litematica placements");
        if (all != null) sb.append(", world bounds ").append(range(all));
        sb.append(", ").append(totalBlocks).append(" blocks\n");
        sb.append("// Manhattan builds Litematica placements by name with schem_build(name, mode, missing):\n");
        sb.append("//   mode \"add\" (default) fills only empty spots, \"fix\" also replaces wrong blocks, \"exact\" digs the schematic's air first;\n");
        sb.append("//   missing \"skip\" (default) leaves out blocks it has no item for, \"strict\" stops at the first missing item.\n");
        sb.append("// schem_materials(name) / schem_needed(name) give the items still needed; schem_left() the blocks still missing overall.\n");
        sb.append("// Bots without Litematica read the placements from config/litematica/litematica_<server>_dim_minecraft_<dimension>.json:\n");
        sb.append("//   copy that file from the account that placed them (after leaving the world) into each bot's config/litematica/.\n");
        sb.append("// Each placement below can go to a different bot. Block counts are schematic blocks, not items (a door is 2 blocks).\n");
        int off = entries.size() - on.size();
        if (off > 0) sb.append("// ").append(off).append(off == 1 ? " placement is" : " placements are").append(" disabled in Litematica and left out.\n");
        if (!total.isEmpty()) sb.append("// all blocks: ").append(blocks(total, Integer.MAX_VALUE)).append('\n');
        for (Entry e : on) {
            sb.append("// ---\n");
            sb.append("// Placement \"").append(e.placement()).append("\": object ").append(e.object());
            if (!"base".equals(e.cell())) sb.append(", array copy ").append(e.cell());
            if (e.project() != null) sb.append(", project ").append(e.project());
            sb.append('\n');
            sb.append("// schematic ").append(e.file()).append(", rotation ").append(e.rotation()).append(", mirror ").append(e.mirror())
                    .append(", world bounds ").append(range(e.world()))
                    .append(", size ").append(e.world().size().x()).append(" x ").append(e.world().size().y()).append(" x ").append(e.world().size().z())
                    .append('\n');
            if (e.blocks() == null) {
                sb.append("// blocks: the schematic file could not be read\n");
            } else {
                long n = 0;
                for (long v : e.blocks().values()) n += v;
                sb.append("// blocks (").append(n).append("): ").append(n == 0 ? "none" : blocks(e.blocks(), TOP_BLOCKS)).append('\n');
            }
            sb.append("schem_build(\"").append(e.placement().replace("\\", "\\\\").replace("\"", "\\\"")).append("\")\n");
        }
        return sb.toString();
    }

    private static String range(Box b) {
        return "(" + b.min().x() + ", " + b.min().y() + ", " + b.min().z() + ")-(" + (b.maxExclusive().x() - 1) + ", "
                + (b.maxExclusive().y() - 1) + ", " + (b.maxExclusive().z() - 1) + ")";
    }

    private static String blocks(Map<String, Long> counts, int top) {
        List<Map.Entry<String, Long>> list = new ArrayList<>(counts.entrySet());
        list.sort(Comparator.<Map.Entry<String, Long>>comparingLong(Map.Entry::getValue).reversed().thenComparing(Map.Entry::getKey));
        StringBuilder sb = new StringBuilder();
        long rest = 0;
        int restKinds = 0;
        for (int i = 0; i < list.size(); i++) {
            if (i >= top) {
                rest += list.get(i).getValue();
                restKinds++;
                continue;
            }
            if (i > 0) sb.append(", ");
            sb.append(list.get(i).getKey()).append(' ').append(list.get(i).getValue());
        }
        if (restKinds > 0) sb.append(", other (").append(restKinds).append(" kinds) ").append(rest);
        return sb.toString();
    }
}
