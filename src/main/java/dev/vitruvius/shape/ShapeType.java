package dev.vitruvius.shape;

/**
 * The shapes the generator knows. Each type says which of the generic ShapeSpec fields it uses, so the screen
 * only shows those (label null = field hidden).
 *
 * <ul>
 *   <li>BOX: solid cuboid sizeX x sizeY x sizeZ.</li>
 *   <li>PLANE: a 1 thick box; axis = the normal (Y = floor). The size along the axis is ignored (always 1).</li>
 *   <li>FRAME: only the 12 edges of the box, edge width = thickness.</li>
 *   <li>ELLIPSOID: fits the box (equal sizes = sphere).</li>
 *   <li>DOME: upper half of an ellipsoid along +axis; sizeAxis = dome height (radius).</li>
 *   <li>CYLINDER: elliptic cross section filling the box, along axis.</li>
 *   <li>CONE: base = ellipse of the box cross section at the low end of axis, tip at the high end;
 *       param1 = top diameter (0 = sharp tip).</li>
 *   <li>PYRAMID: rectangular base at the low end of axis, apex at the high end; param1 = top size (0 = point).</li>
 *   <li>TORUS: ring lying in the plane normal to axis, outer size = the box; param1 = tube diameter.</li>
 *   <li>ARCH: half ring (upper half of a torus standing on axis = its depth direction); box = arch outer size,
 *       param1 = arch thickness (ring width) in blocks.</li>
 *   <li>TUBE: cylinder along axis with a hole; param1 = wall width. Ends are open (no caps).</li>
 *   <li>GRID: lattice of lines; param1 = spacing (distance between line starts), param2 = line width.
 *       Lines run along the two axes normal to `axis` in a 1-thick layer if sizeAxis == 1, otherwise a 3D lattice
 *       of beams along all three axes.</li>
 * </ul>
 */
public enum ShapeType {
    BOX("Box", null, null),
    PLANE("Plane", null, null),
    FRAME("Frame", null, null),
    ELLIPSOID("Ellipsoid", null, null),
    DOME("Dome", null, null),
    CYLINDER("Cylinder", null, null),
    CONE("Cone", "Top diameter", null),
    PYRAMID("Pyramid", "Top size", null),
    TORUS("Torus", "Tube diameter", null),
    ARCH("Arch", "Arch width", null),
    TUBE("Tube", "Wall width", null),
    GRID("Grid", "Spacing", "Line width");

    public final String displayName;
    /** Label of ShapeSpec.param1 for this type, null = not used. */
    public final String param1Label;
    /** Label of ShapeSpec.param2 for this type, null = not used. */
    public final String param2Label;

    ShapeType(String displayName, String param1Label, String param2Label) {
        this.displayName = displayName;
        this.param1Label = param1Label;
        this.param2Label = param2Label;
    }

    /** Whether the axis selector means anything for this type. */
    public boolean usesAxis() {
        return switch (this) {
            case BOX, FRAME, ELLIPSOID -> false;
            default -> true;
        };
    }

    /** Whether the Outline thickness means anything (FRAME uses it as edge width, all others as shell). */
    public boolean usesThickness() {
        return true;
    }
}
