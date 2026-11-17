package dev.vitruvius.api;

import dev.vitruvius.shape.VoxelResult;
import net.minecraft.core.BlockPos;

/**
 * What a shape action gets when its button on the Shape Generator screen is clicked: the generated shape (one palette
 * index per cell, 0 = air), the text of the name field, and the min corner of the preview in the world.
 */
public record ShapeActionContext(VoxelResult voxels, String suggestedName, BlockPos suggestedOrigin) {}
