package dev.vitruvius.shape;

/**
 * One block of a slot: a block state string as typed ("minecraft:oak_log[axis=x]", namespace optional) and its
 * weight for MIX (ignored by the other modes, >= 1). The core never parses the string; the mc layer does.
 */
public record BlockEntry(String blockState, int weight) {
    public BlockEntry {
        blockState = blockState == null ? "" : blockState.trim();
        weight = Math.max(1, weight);
    }
}
