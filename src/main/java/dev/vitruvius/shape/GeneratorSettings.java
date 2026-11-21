package dev.vitruvius.shape;

/**
 * Everything the screen edits; what presets and "last settings" store. {@code sketch} is the drawing of a CUSTOM
 * shape (null for every other type).
 */
public record GeneratorSettings(ShapeSpec spec, BlockSlot outline, BlockSlot fill, String name, Sketch sketch) {
    public GeneratorSettings(ShapeSpec spec, BlockSlot outline, BlockSlot fill, String name) {
        this(spec, outline, fill, name, null);
    }

    public static GeneratorSettings defaults() {
        return new GeneratorSettings(ShapeSpec.defaults(), BlockSlot.single("minecraft:stone"), BlockSlot.air(), "shape");
    }

    /** For a CUSTOM shape: the same settings with the spec size set to what the drawing extrudes to. */
    public GeneratorSettings withSketchSize() {
        if (spec.type() != ShapeType.CUSTOM) return sketch == null ? this : new GeneratorSettings(spec, outline, fill, name, null);
        Sketch sk = sketch == null ? Sketch.EMPTY : sketch;
        ShapeSpec sp = spec.clamped();
        int[] z = sk.size(sp.axis());
        ShapeSpec ns = new ShapeSpec(sp.type(), z[0], z[1], z[2], sp.axis(), sp.thickness(), sp.param1(), sp.param2(), sp.anchorCenter());
        return new GeneratorSettings(ns, outline, fill, name, sk);
    }
}
