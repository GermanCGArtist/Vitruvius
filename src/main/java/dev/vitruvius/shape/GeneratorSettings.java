package dev.vitruvius.shape;

/** Everything the screen edits; what presets and "last settings" store (Gson serializes it as is). */
public record GeneratorSettings(ShapeSpec spec, BlockSlot outline, BlockSlot fill, String name) {
    public static GeneratorSettings defaults() {
        return new GeneratorSettings(ShapeSpec.defaults(), BlockSlot.single("minecraft:stone"), BlockSlot.air(), "shape");
    }
}
