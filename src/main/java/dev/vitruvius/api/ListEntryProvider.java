package dev.vitruvius.api;

import java.util.Collection;

/** Supplies the rows another mod adds to Litematica's schematic placement list. Asked every time the list is rebuilt. */
public interface ListEntryProvider {
    Collection<? extends ListEntry> entries();
}
