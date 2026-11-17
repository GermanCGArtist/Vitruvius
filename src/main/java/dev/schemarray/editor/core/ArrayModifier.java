package dev.schemarray.editor.core;

import java.util.Arrays;
import java.util.Objects;

/** One Blender-style array modifier (the fields of {@code ArrayStore.Mod}). Offsets are along the object's own axes. */
public final class ArrayModifier implements Modifier {
    public boolean enabled = true;
    /** At least 1; 1 = no copies from this modifier. */
    public int count = 2;
    public boolean useRelative = true;
    /** Fraction of the bounding box size so far, per axis. */
    public double[] relative = {1, 0, 0};
    public boolean useConstant = false;
    /** Blocks, per axis. */
    public int[] constant = {0, 0, 0};

    @Override
    public boolean enabled() {
        return enabled;
    }

    @Override
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    @Override
    public ArrayModifier copy() {
        ArrayModifier m = new ArrayModifier();
        m.enabled = enabled;
        m.count = count;
        m.useRelative = useRelative;
        m.relative = relative.clone();
        m.useConstant = useConstant;
        m.constant = constant.clone();
        return m;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof ArrayModifier m && enabled == m.enabled && count == m.count && useRelative == m.useRelative
                && useConstant == m.useConstant && Arrays.equals(relative, m.relative) && Arrays.equals(constant, m.constant);
    }

    @Override
    public int hashCode() {
        return Objects.hash(enabled, count, useRelative, useConstant, Arrays.hashCode(relative), Arrays.hashCode(constant));
    }

    @Override
    public String toString() {
        return "ArrayModifier[" + (enabled ? "on" : "off") + " x" + count + " rel=" + (useRelative ? Arrays.toString(relative) : "-")
                + " const=" + (useConstant ? Arrays.toString(constant) : "-") + "]";
    }
}
