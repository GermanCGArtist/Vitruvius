# Shape generator

The Shape generator builds a solid or hollow shape out of blocks inside the [editor](editor.md). Open it with
**Add** → **Shape generator...** or **Windows** → **Shape generator**. Each side is 1 to 256 blocks.

The shape is a real object in the scene from the start. Every change regenerates it, so you judge it in the 3D viewport
while you fly around it. Drag its handles to resize it. Select a generated shape later and the window edits it again.

Contents: [Shapes](#shapes) · [Custom 2D](#custom-2d) · [Outline and Fill](#outline-and-fill) ·
[Choosing blocks](#choosing-blocks) · [Pattern modes](#pattern-modes) · [Output and saving](#output-and-saving)

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
| **Custom 2D** | A drawing from the sketchpad, extruded **Depth** blocks along **Extrude along**. See below. |

## Custom 2D

**Open sketchpad** opens a pixel canvas. Each pixel is empty, part of the shape (coloured by the Outline and Fill
patterns), or pinned to one block.

- Tools: **Brush**, **Eraser**, **Select**, **Fill** and **Pick**.
- With Select, drag to pick pixels, **Shift**+drag for a box, and hold **Ctrl** to add to the selection.
- Middle drag pans, the wheel zooms, and a right click opens the menu for the selection.

The shape regenerates when a stroke ends, so one stroke is one undo step.

## Outline and Fill

**Outline** is the shell and **Fill** is the inside. **Thickness** is the shell width; if it is as thick as the shape,
everything is outline. Leave **Fill** empty for a hollow shape.

## Choosing blocks

Each of **Outline** and **Fill** takes blocks:

- Type `stone` or `minecraft:stone`. Add a state in brackets: `oak_log[axis=x]`.
- Mix blocks with weights: `60%stone_bricks,40%andesite`. A part without `%` weighs 1.
- **Hand** adds the block in your main hand. In **Single** mode it replaces the field.
- The multi-block modes (everything except Single) take two or more blocks separated by commas, for example
  `stone,andesite`.
- An unknown block is reported, and nothing is generated until it is fixed.

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

## Output and saving

- **Output** picks what the shape becomes: a **Schematic** (the default), or an output another mod registered (for
  example Heavymatica's). An output shows its own preview while it is selected.
- **Save the schematic in** picks a subfolder of Litematica's schematics folder (`Shapes` by default). **Browse...**
  can create one. The choice is remembered.
- **Placement list folder** picks the folder of the placement list where the new entry appears.
- **Save schematic file** writes a copy of the current shape as a `.litematic` file. The object stays in the scene.

The last settings are kept in `config/vitruvius/shapes.json` and come back when you open the window again.

A generated shape is a normal schematic object, so you can move it, rotate it, array it, or use it as a Boolean
cutter. Other mods can add outputs; see the [API](../reference/api.md#shape-outputs).
