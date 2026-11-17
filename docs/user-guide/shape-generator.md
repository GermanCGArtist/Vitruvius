# Shape Generator

`M` → **Shape Generator** (at the end of the right-hand column of Litematica's main menu) builds a solid or hollow shape
out of blocks and saves it as a schematic. Each side is 1 to 256 blocks.

Contents: [Shapes](#shapes) · [Outline and Fill](#outline-and-fill) · [Choosing blocks](#choosing-blocks) ·
[Pattern modes](#pattern-modes) · [The preview](#the-preview) · [Saving](#saving) · [Presets](#presets)

## Shapes

| Shape | Description |
|---|---|
| **Box** | Solid. |
| **Plane** | Flat, 1 thick, normal to **Axis**. |
| **Frame** | The 12 edges of the box. **Thickness** is the edge width. |
| **Ellipsoid** | Fills the box. Equal sides make a sphere. |
| **Dome** | The upper half of an ellipsoid along **Axis**. The size along the axis is the height. |
| **Cylinder** | Along **Axis**, filling the box. |
| **Cone** | Along **Axis**, base at the low end. **Top diameter** `0` gives a sharp tip. |
| **Pyramid** | Base at the low end. **Top size** `0` gives a point. |
| **Torus** | A ring lying normal to **Axis**. **Tube diameter** sets the thickness. |
| **Arch** | A half ring. **Arch width** is the ring width. |
| **Tube** | A hollow cylinder along **Axis**, open at the ends. **Wall width** sets the wall. |
| **Grid** | Lines every **Spacing** blocks, **Line width** wide. Size 1 along **Axis** gives a flat grid, otherwise a 3D lattice. |

## Outline and Fill

**Outline** is the shell and **Fill** is the inside. **Thickness** is the shell width; if it is as thick as the shape,
everything is outline. Leave **Fill** empty for a hollow shape.

## Choosing blocks

Each of **Outline** and **Fill** takes blocks:

- Type `stone` or `minecraft:stone`. Add a state in brackets: `oak_log[axis=x]`.
- Mix blocks with weights: `60%stone_bricks,40%andesite`. A part without `%` weighs 1.
- **Hand** adds the block in your main hand. In **Single** mode it replaces the field.
- The multi-block modes (everything except Single) take two or more blocks separated by commas, for example
  `stone,andesite`. The field shows the syntax.
- Ids are suggested while you type. **Tab** takes the first suggestion, or click one.
- An unknown block turns red and disables **Generate**.

## Pattern modes

| Mode | Behavior |
|---|---|
| **Single** | Always the first block. |
| **Mix** | Random by weight. **Seed** fixes it and **Dice** rolls a new one. |
| **Checker** | Alternates in cells of **Cell X/Y/Z** blocks. |
| **Stripes** | Alternates bands of **Width** along **Axis**. |
| **Gradient** | Blocks in order along **Axis**, in equal bands. **Dither** blends the edges. |
| **Diagonal** | Bands of **Width** along x + z (meaningful with **Axis** Y). |
| **Brick** | Bricks of **Cell X/Y/Z**, every other row shifted by half. A second block is the mortar. |

## The preview

- The shape shows as a **ghost in the world** and updates with every change. Its minimum corner sits at your position,
  or its center with **Anchor: Center**.
- **Ghost: Off** removes the ghost from the world (the 3D view in the screen keeps updating). The choice is remembered.
- Move the ghost with Litematica's tools. The screen keeps that spot until you toggle the anchor.
- Drag the 3D view to orbit, scroll to zoom, **Reset view** to start over.
- **Peek** (hold the button, or hold `G` with no text field focused) hides the panel so you can see the ghost.

## Saving

- **Folder** / **Browse...** picks the save folder: any subfolder of Litematica's schematics folder (**New folder**
  creates one). The choice is remembered.
- **Generate** writes `<name>.litematic` into that folder, turns the ghost into a normal placement and closes the screen.
  If the file already exists, Generate is blocked. Hold **Shift** to overwrite; this also removes placements that used
  the old file.
- **Cancel** or **Esc** closes the screen and removes the ghost.

## Presets

**Load** a preset, or type a name and **Save** the current settings. **Delete** needs **Shift**.

Presets and your last settings are kept in `config/vitruvius/shapes.json`. The last settings return when you reopen the
screen.

## Using shapes in the editor

A generated shape is a normal schematic, so you can add it to a project, array it, or use it as a Boolean cutter. Other
mods can add their own buttons to the screen; see the [API](../reference/api.md#shape-actions).
