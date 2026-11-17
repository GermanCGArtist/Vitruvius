package dev.schemarray.editor.io;

import java.io.IOException;

/** A project or assembly file that cannot be read: not JSON, a field of the wrong shape, or a newer format. */
public class FormatException extends IOException {
    public FormatException(String message) {
        super(message);
    }

    public FormatException(String message, Throwable cause) {
        super(message, cause);
    }
}
