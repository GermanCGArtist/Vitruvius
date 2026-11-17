package dev.vitruvius.api;

import dev.vitruvius.editor.core.Box;

/** What an object type may draw in the world. All coordinates are world blocks; colors are 0xRRGGBB. */
public interface OverlayDrawer {
    /** The edges of a block range (the box occupies [min, maxExclusive) on every axis). */
    void box(Box box, int rgb, float alpha, boolean dashed);

    /** One line between two world points. */
    void line(double x1, double y1, double z1, double x2, double y2, double z2, int rgb, float alpha, boolean dashed);

    /** Translucent faces of a block range, drawn with depth off. */
    void fill(Box box, int rgb, float alpha);
}
