package dev.schemarray.editor.core;

import java.util.UUID;

/** A node that has a front-order number (ObjectNode, AssemblyNode). 1 = front; 0 = not numbered yet (behind all). */
public interface HasFront {
    UUID id();

    int front();

    void setFront(int front);
}
