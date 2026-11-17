package dev.schemarray.editor.core;

import java.util.Objects;
import java.util.UUID;

/** Cuts the object with another node's blocks. {@code targetId} may be null while no target is chosen yet. */
public final class BooleanModifier implements Modifier {
    public enum Op { DIFFERENCE, INTERSECT }

    public boolean enabled = true;
    public Op op = Op.DIFFERENCE;
    public UUID targetId;

    public BooleanModifier() {}

    public BooleanModifier(Op op, UUID targetId) {
        this.op = op;
        this.targetId = targetId;
    }

    @Override
    public boolean enabled() {
        return enabled;
    }

    @Override
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    @Override
    public BooleanModifier copy() {
        BooleanModifier m = new BooleanModifier(op, targetId);
        m.enabled = enabled;
        return m;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof BooleanModifier m && enabled == m.enabled && op == m.op && Objects.equals(targetId, m.targetId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(enabled, op, targetId);
    }

    @Override
    public String toString() {
        return "BooleanModifier[" + (enabled ? "on" : "off") + " " + op + " " + targetId + "]";
    }
}
