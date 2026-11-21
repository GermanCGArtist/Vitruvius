# Placement folders

Litematica's placement list (`M` → **Schematic placements**) can be sorted into nested, coloured folders. This works
without the [editor](editor.md), and the list also shows the editor's open projects as folders.

Folders are global (all worlds and servers) and are kept in `config/vitruvius/folders.json`.

Contents: [Folders](#folders) · [Selecting several rows](#selecting-several-rows) · [Drag and drop](#drag-and-drop) ·
[Colors](#colors) · [Folders and the editor](#folders-and-the-editor)

## Folders

**New folder** (bottom left) makes a top-level folder. A folder row shows `v` or `>` (open or closed), its name, and how
many placements are inside it (subfolders included). A click anywhere on the row opens or closes it.

| Button | Effect |
|---|---|
| **Visible / Hidden** | *Hidden* switches the rendering of everything in the folder and its subfolders off. Only rendering is switched, never **Enabled**, and placements you had switched off yourself stay off when you show the folder again. |
| **+** | Creates a subfolder. |
| **Rename** | Renames the folder. |
| **Delete** | Needs **Shift** held. The folder goes; its placements and subfolders move up one level. **No placement is ever removed.** |

Every placement row has a **Move** button, left of Configure. Pick `(top level)` or any folder.

Folders come first (by name), then placements (by name). While the search bar has text the list is flat. The copies of
an editor array are hidden from the list and are never put in a folder.

## Selecting several rows

Select like in a file manager:

| Gesture | Effect |
|---|---|
| Click | Selects one row. |
| **Ctrl+click** | Adds or removes a row. |
| **Shift+click** | Selects the range from the last clicked row. **Ctrl+Shift** adds that range. |

**Move** moves the whole selection when its row is selected.

## Drag and drop

Drag the selection (or a single placement or folder) with the left mouse button:

- onto a **folder row** to put it in there,
- onto a **placement row** to put it next to that placement,
- onto **empty space** to move it to the top level.

**Esc**, or letting go outside the list, cancels. A folder cannot be dropped into itself.

## Colors

**Right-click** a folder or placement row to pick one of Minecraft's 16 chat colors, or no color. A folder row is tinted
and its name colored. A placement gets a colored bar: its own color, or else the nearest colored folder's. With several
placements selected, right-clicking one of them colors them all.

## Folders and the editor

The placement list mirrors the editor's open projects as folders, so people who never press F6 still see them. The
editor's Scene window and this list are two views of the same placements. The first time you enter a world, the editor
builds its **Loose** list from these folders and placements.

Arrays are made in the editor: select an object and add an **Array** modifier in the Properties window (see the
[editor guide](editor.md)).
