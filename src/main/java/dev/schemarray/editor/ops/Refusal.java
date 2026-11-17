package dev.schemarray.editor.ops;

import java.util.Objects;

/** Why a command cannot run. Returned before anything is changed; the UI shows {@link #reason()}. */
public record Refusal(String reason) {
    public Refusal {
        Objects.requireNonNull(reason, "reason");
    }

    @Override
    public String toString() {
        return reason;
    }
}
