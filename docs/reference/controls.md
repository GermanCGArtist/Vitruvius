# Keyboard and mouse reference

Everything here applies in **editor mode** (F6) unless noted. Shortcuts do not fire while you type in a text field, while a
menu is open, or while you fly (right mouse held). `Ctrl` also means `Cmd` on macOS. The editor toggle can be rebound
under *Controls → Key Binds → Vitruvius*; the other keys are fixed.

## Mode

| Key | Action |
|---|---|
| `F6` | Enter or leave editor mode. |
| `Tab` | Hide or show all editor windows. |
| `Esc` | Nothing (it never opens the pause menu in editor mode). Cancels a running gesture. |

## Camera

| Input | Action |
|---|---|
| Middle drag | Orbit. |
| `Shift` + middle drag | Pan. |
| Wheel | Zoom toward the cursor. |
| Hold right mouse | Fly. The cursor is hidden. |
| `W` `A` `S` `D` | Move while flying. |
| `E` / `Q` | Up / down while flying. |
| `Shift` while flying | About 3× faster. |
| Wheel while flying | Change fly speed. |
| `F` or numpad `.` | Frame the selection. |
| Right click, no drag | Context menu. |

## Selection

| Input | Action |
|---|---|
| Left click | Select the object under the cursor. Click empty space to deselect. |
| `Shift` + click | Add to or remove from the selection. |
| `Alt` + click | Cycle through objects under the cursor. |
| `B` | Box select (then drag a rectangle). Plain replaces the selection, `Shift` adds, `Ctrl` subtracts. |
| `A` | Select all. |
| `Alt` `A` | Select none. |
| `Shift` `A` | Open the Add menu. |

## Transform

| Input | Action |
|---|---|
| Drag the gizmo arrows | Move along an axis or plane. |
| Drag the gizmo ring | Rotate in 90° steps. |
| `G` | Start a typed move. |
| `R` | Start a typed rotation. |
| `M` | Mirror X. |
| `W` | Select tool. |
| `Ctrl` while dragging | Invert Snap for this drag. |
| A digit while dragging | Hands over to a typed move on that axis. |
| `Shift` `D` | Duplicate (with a typed offset). |
| `Alt` `D` | Linked duplicate (with a typed offset). |

While a typed transform is running:

| Key | Action |
|---|---|
| `X` `Y` `Z` | Choose the axis. Holding `Shift` with the axis key changes the axis choice (as in Blender); the HUD shows the result. |
| `0`–`9`, numpad | Type the value. |
| `-` | Negate. |
| `Backspace` | Delete the last digit. |
| `,` `Tab` `Space` | Next field. |
| `Enter` | Commit. |
| `Esc` or right click | Cancel. |

## Editing

| Key | Action |
|---|---|
| `Ctrl` `Z` | Undo. |
| `Ctrl` `Shift` `Z` or `Ctrl` `Y` | Redo. |
| `Ctrl` `S` | Save all open projects. |
| `Ctrl` `G` | Create assembly from selection. |
| `X` or `Delete` | Delete the selection. |
| `F2` | Rename. |
| `H` | Hide or show the selection. |
| `Alt` `H` | Show everything hidden. |
| `/` | Enter or leave local view. |
| `PgUp` / `PgDn` | Bring forward / send backward (front order). |
| `Shift` `PgUp` / `Shift` `PgDn` | Bring to front / send to back. |

## Scene window

| Key | Action |
|---|---|
| `Ctrl` `F` | Focus the filter field. |
| Arrow keys | Move the selection (the window must have focus). |
| Left / Right | Collapse / expand a folder. |
| `Space` | Toggle visibility. |
| `Enter` | Frame the object. |
| Double-click a name | Rename. |

## Litematica screens (no editor needed)

| Input | Where | Action |
|---|---|---|
| Left / right click on **+-** | Array screen | +1 / −1. |
| `Shift` / `Alt` with **+-** | Array screen | ×8 / ×4. |
| `Ctrl` / `Shift` click | Placement list | Multi-select / range. |
| `Shift` + **Delete** | Placement list folder | Delete a folder. |
| Right click | Placement list | Pick a color. |
| `Tab` | Shape Generator block field | Take the first suggestion. |
| `G` held | Shape Generator | Peek at the ghost. |
| `Shift` + **Generate** | Shape Generator | Overwrite an existing file. |
