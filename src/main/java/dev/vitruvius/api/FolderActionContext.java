package dev.vitruvius.api;

import java.util.List;

/**
 * What a folder action gets when its button on a folder row is clicked: the folder and the ids of all entries (of
 * other mods, not placements) in it and in its subfolders, in list order.
 */
public record FolderActionContext(String folderId, String folderName, List<String> entryIds) {}
