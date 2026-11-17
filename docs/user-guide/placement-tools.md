# Arrays, paste and placement folders

These features live inside Litematica's own screens and work without the [editor](editor.md). In 2.0 they share their
data with the editor: an array is the same *Array modifier* in both places, and the placement list also shows the
editor's projects as folders.

Contents: [Arrays](#arrays) · [Pasting arrays](#pasting-arrays) · [Placement folders](#placement-folders)

## Arrays

Open `M` → **Configure placement** → **Configure**. Under the **Rotate** button there is an **Array...** button (it
reads `Array (N copies)...` when an array is active). It opens the array screen, a clone of Blender's Array modifier: a
stack of modifiers, applied from top to bottom.

```
Array 1   [On] [^] [v] [Remove]
Count: [ 5 ][+-]
[x] Relative Offset  X [1.0] Y [0.0] Z [0.0]
[x] Constant Offset  X [0  ][+-] Y [-1 ][+-] Z [0  ][+-]
[Add Array]  Total: 4 copies                         [Done]
```

| Control | Meaning |
|---|---|
| **Count** | Number of copies of the stack so far, *including the original*. `1` means this modifier adds no copies. |
| **Relative Offset** | The step as a fraction of the bounding box built so far. `1` is exactly one box, `0.5` is half, a negative value goes the other way. |
| **Constant Offset** | The step in blocks (negative goes the other way). Added to the relative offset when both are on. |
| **On / Off** | Switches the modifier without deleting it. |
| **^ / v** | Moves the modifier up or down the stack. |
| **Remove** | Deletes the modifier. |
| **Add Array** | Adds another modifier to the stack. |

With several modifiers the copies form a grid (count₁ × count₂ × …). Each modifier measures the box that the modifiers
above it have built, as in Blender.

The **+-** buttons work like Litematica's own: left click +1, right click −1, **Shift** ×8, **Alt** ×4. The mouse wheel
scrolls when there are more modifiers than fit on the screen. Every edit applies live.

**Example, a staircase:** Count 5, Relative X 1.0, Constant Y −1. Each copy sits one box further along X and one block lower.

### How arrays behave

- The axes are the schematic's own, so rotating or mirroring the placement turns the whole array with it.
- The copies are ordinary Litematica placements of the same schematic, so they render, print and verify like any placement.
- They follow the original live (move, nudge, rotate, mirror, enable or disable, rendering, sub-regions on or off), are
  hidden from Litematica's placement list, and are removed together with the original.
- Arrays are kept across restarts. In 1.x they are in `config/vitruvius/arrays.json`; once an object is managed by the
  2.0 editor, its array is part of that object in the project instead (see [File formats](../reference/file-formats.md)).
- Arrays saved by older versions (counts and gaps per axis) are converted on first load.

### Limits

- Up to **1024 copies** per array.
- The material list of the original counts **one** copy.
- Sub-regions that you moved inside the original are not copied over; only their on/off state is.

## Pasting arrays

In Litematica's **Paste schematic** mode, pasting the original placement pastes the **whole array** (the original and all
its enabled copies) as one task. Pasting a copy pastes only that copy.

Litematica's **Grid Paste** mode does nothing on its own. With this mod it behaves like Paste mode, and so also pastes
the whole array.

## Placement folders

Litematica's placement list (`M` → **Schematic placements**) can be sorted into folders. Folders are global (all worlds
and servers) and are kept in `config/vitruvius/folders.json`.

**New folder** (bottom left) makes a top-level folder. A folder row shows `v` or `>` (open or closed), its name, and how
many placements are inside it (subfolders included). A click anywhere on the row opens or closes it.

| Button | Effect |
|---|---|
| **Visible / Hidden** | *Hidden* switches the rendering of everything in the folder and its subfolders off. Only rendering is switched, never **Enabled**, and placements you had switched off yourself stay off when you show the folder again. |
| **+** | Creates a subfolder. |
| **Rename** | Renames the folder. |
| **Delete** | Needs **Shift** held. The folder goes; its placements and subfolders move up one level. **No placement is ever removed.** |

Every placement row has a **Move** button, left of Configure. Pick `(top level)` or any folder.

Folders come first (by name), then placements (by name). While the search bar has text the list is flat. Array copies stay
hidden from the list and are never put in a folder.

### Selecting several rows

Select like in a file manager:

| Gesture | Effect |
|---|---|
| Click | Selects one row. |
| **Ctrl+click** | Adds or removes a row. |
| **Shift+click** | Selects the range from the last clicked row. **Ctrl+Shift** adds that range. |

**Move** moves the whole selection when its row is selected.

### Drag and drop

Drag the selection (or a single placement or folder) with the left mouse button:

- onto a **folder row** to put it in there,
- onto a **placement row** to put it next to that placement,
- onto **empty space** to move it to the top level.

**Esc**, or letting go outside the list, cancels. A folder cannot be dropped into itself.

### Colors

**Right-click** a folder or placement row to pick one of Minecraft's 16 chat colors, or no color. A folder row is tinted
and its name colored. A placement gets a colored bar: its own color, or else the nearest colored folder's. With several
placements selected, right-clicking one of them colors them all.

### Folders and the 2.0 editor

The placement list also mirrors the editor's open projects as folders, so people who never press F6 still see them. The
editor's Scene window and this list are two views of the same placements.
