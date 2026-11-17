package dev.schemarray.editor.mc;

import dev.schemarray.editor.core.ObjectNode;
import dev.schemarray.editor.core.Transform;

/**
 * A managed base placement was moved, turned or mirrored outside the editor (Litematica's own tools, or a Syncmatica
 * peer). The model has already been updated: {@code node.transform()} is {@code after}. Undoing it means setting
 * {@code before} again.
 */
public record ExternalChange(ObjectNode node, Transform before, Transform after) {}
