# Editor guide (2.0)

The editor is a second way to work with Litematica placements: a free camera, a scene tree, a gizmo and undo, in the
style of Blender. It does not replace Litematica. Every object in the editor becomes an ordinary Litematica placement,
so rendering, printing, the verifier and the material list keep working.

Contents: [Concepts](#concepts) · [Opening the editor](#opening-the-editor) · [The windows](#the-windows) ·
[Moving around](#moving-around) · [Selecting](#selecting) · [Transforming](#transforming) ·
[Projects](#projects) · [Assemblies](#assemblies) · [Arrays](#arrays) ·
[Overlaps, front order and Booleans](#overlaps-front-order-and-booleans) · [Hiding and local view](#hiding-and-local-view) ·
[Exports](#exports) · [Sharing on a server](#sharing-on-a-server) · [Undo and saving](#undo-and-saving) ·
[Troubleshooting](#troubleshooting)

## Concepts

| Term | Meaning |
|---|---|
| **Object** | One schematic (`.litematic`) with a position, a rotation and an optional mirror. |
| **Folder** | A plain group in the tree. It has no transform of its own. |
| **Assembly** | A group that *does* have a transform. Moving it moves all its parts. It can have its own arrays and can be saved as a `.lasm` file and linked into other projects. |
| **Project** | A folder on disk that holds a scene graph and its own copies of the schematics, like a `.blend` file. Several projects can be open at once; one is the *active* project, where new objects go. |
| **Loose** | Everything that is not in a project: your existing Litematica placements, and the 1.x folders and arrays, which are migrated here the first time. |
| **Modifier** | An **Array** or a **Boolean** on an object or assembly. |
| **Front order** | A priority number (`P1` is in front). Where two objects overlap, the one further back loses the shared blocks. |

Rotation is limited to steps of 90° around the Y axis, because that is what Litematica supports. There is also a
**Mirror X**; mirroring in Z is Mirror X plus a 180° turn.

## Opening the editor

Press **F6** (rebindable under *Controls → Key Binds → Vitruvius → Toggle editor mode*). On entering:

- the mouse is released and the crosshair and hand disappear,
- the camera starts exactly where your eyes are, then moves independently of your player,
- the Scene, Properties and Top view windows, the toolbar and the status bar appear.

Press **F6** again, or click the **EDITOR F6** chip in the toolbar, to leave. **Esc** does nothing in editor mode (no
pause menu), so you cannot leave by accident.

In **spectator** mode the real player follows the camera, so chunks load around the camera. A server refuses moves of
10 blocks or more at once, so the player follows at up to 8 blocks per tick (160 blocks per second). After a long jump of
the camera, the fly pill shows *player catching up* until the player has arrived. In other game modes the player stays
where they are; the camera can only see what the client has already loaded.

Vanilla controls (movement, inventory, attack) are blocked while the editor is open. The F-keys, chat and commands still
work.

## The windows

The game stays visible between the windows and the mouse there belongs to the 3D view. Drag a window's title to float
it, or hold **Shift** while dragging to dock it to an edge. **Tab** hides or shows all windows (the toolbar and status
bar stay). *Windows → Reset layout* in the toolbar puts them back.

### Scene window

The tree of everything in the editor, open projects first (the active one has an **ACTIVE** badge) and **Loose** last.

- **Click** selects a row, **Shift+click** selects a range, **Ctrl+click** adds or removes one row. The arrow keys move the selection when
  the window has focus; Left and Right collapse and expand, **Space** toggles visibility, **Enter** frames the object.
- **F2**, or a double-click on the name, renames. **Enter** confirms and **Esc** cancels.
- **Drag** a row to reorder it (a teal line shows where), onto a folder to move it in, or onto another project to move
  the object there. A red row means the drop is not allowed, and the reason is shown.
- The **eye** icon hides an object. Hidden objects have their placements removed from the world.
- The filter field (**Ctrl F**) narrows the tree. Chips restrict it to schematics, assemblies or areas.
- **Issues (n)** appears when objects overlap or a placement is missing, and lists them.
- Badges: `xN` an array of N copies, a blade icon a Boolean, `P1`/`P2` overlap priorities, `!` a placement that
  Litematica no longer has (the row is greyed out; it is never deleted automatically).
- **New project** and **Open project...** are at the bottom.

### Properties window

Shows the selection. For an object:

- the **name** and file path (click the path to copy it) and the block count,
- **Transform**: Location X/Y/Z (drag, double-click to type, or use the − / + steppers; Shift steps by 16),
  Rotation (0, 90, 180, 270) and Mirror X,
- **Front order**,
- **Modifiers**: **+ Array** and **+ Boolean**.

For several objects it shows a batch view with a shared pivot. Folders, projects and Loose have their own sections, for
example **Make active** and **Convert to project...**.

### Top view

A 2D map of the build from above (a map, not a second 3D render). Objects are filled rectangles, array copies are
fainter, the selection is teal, overlaps are red, other mods' areas have an amber dashed outline, and the camera and
orbit target are marked.

- Middle or right drag pans, the wheel zooms around the cursor. **Fit** frames everything, **Follow** keeps the camera centred.
- Click an object to select it (Shift adds). Click empty map to move the camera target there.
- Drag a selected object to move it in whole blocks; one drag is one undo step, and Esc cancels.

### Toolbar and status bar

The toolbar has the mode chip, **Add**, the tools, Mirror, **Snap**, **Pivot**, **Local view**, the Windows menu and,
with a project shared on a server, **Push**. On a narrow window the labels collapse first, then the key hints, then the later
groups move into a **...** menu. The status bar shows context hints, the derived-file rebuild queue and unpushed changes.

## Moving around

| Action | How |
|---|---|
| Orbit | Middle mouse drag |
| Pan | Shift + middle mouse drag |
| Zoom | Mouse wheel (zooms toward the cursor) |
| Fly | Hold the right mouse button, then `W` `A` `S` `D`, `Q` down, `E` up. `Shift` is about three times faster; the wheel changes the speed. |
| Frame the selection | `F` or numpad `.` |

A right click without moving the mouse (under about 4 px) opens the context menu instead of flying.

## Selecting

- **Click** an object in the 3D view. Where two objects overlap, the one in front (lower `P` number) is picked.
- **Shift+click** adds or removes. **Alt+click** cycles through every object under the cursor.
- **B** starts a box select; drag a rectangle.
- **A** selects all, **Alt+A** selects none.

Selected objects get a teal outline and a name tag. An object with an array also gets fainter outlines around its copies.

## Transforming

Dragging the **gizmo** moves along an axis (or a plane) and its ring rotates in 90° steps. While you drag, only a dashed
ghost moves; the real placement moves when you release. A single undo step covers the whole gesture. A right click or
**Esc** cancels it.

- **Snap** (toolbar) pulls the ghost to the faces of other objects when it is within 1.5 blocks. Hold **Ctrl** to invert
  it for one drag.
- **Pivot** decides where the gizmo sits and what rotate and mirror turn about: **Median**, **Active object** or
  **Bounding box**. The pivot is always a whole block.

### Typed transforms

Like Blender, you can type exact values:

| Keys | Result |
|---|---|
| `G`, then optionally `X`/`Y`/`Z`, a number, `Enter` | Move. `G X 5 Enter` moves 5 blocks along X. |
| `R`, then a number, `Enter` | Rotate in 90° steps. |
| `Shift D`, then the move | Duplicate with a typed offset. One `Ctrl Z` undoes it. |
| `Alt D`, then the move | Linked duplicate with a typed offset. |

`-` negates, `Backspace` deletes a digit, `,`/`Tab`/`Space` go to the next field, **Esc** cancels and leaves nothing behind.
A heads-up display shows the value while you type.

### Duplicates

- **Duplicate** (`Shift D`) copies the object and its file.
- **Linked duplicate** (`Alt D`) places another instance of the same file, so they stay in sync.

## Projects

A project is a folder under `schematics/Projects/<name>/`:

```
Projects/<name>/
  project.json     the scene graph, world and dimension, origin
  files/           the project's own schematics and assemblies, in subfolders
  .cache/derived/  generated trimmed files (safe to delete)
  .trash/          files the editor removed and could still undo
```

See [File formats](../reference/file-formats.md) for details.

- **New project** creates one and makes it active. **Open project...** lists the folders in `schematics/Projects`.
- **Make active** changes where new objects go. Front order works across all open projects.
- **Add** (toolbar, `Shift A`, or the right-click menu) offers **Schematic...**, **Shape generator...**, **Assembly (.lasm)...**,
  **Link project as assembly...**, any object type another mod registered, and **Folder**.
- **Convert to project...** (on Loose or on a folder) turns existing placements into a new project.
- **Close project** removes its placements from the world; the files stay on disk.
- Open projects are remembered per world and open again when you rejoin it. A project records the world and dimension it was made in.

**Shape generator** (Add menu, or Windows) opens the shape builder as a window in the editor: pick a shape, sizes and block patterns, and the result shows as a ghost at the camera target while you fly around it. The shape is an ordinary object from the start, saved in `schematics/Shapes`, so you can move, rotate, array and cut it. See the [Shape generator guide](shape-generator.md).

When you add a schematic, its file is **copied into the project's `files/` folder**, so the project is self-contained.

### The first start in a world

The first time the editor starts in a world, your placements and their [placement folders](placement-folders.md)
(`folders.json`) become Loose objects. The editor never writes `folders.json`. Folder hiding of these nodes remains
handled by the placement list. Arrays made with 1.x are not converted: their copies come in as separate objects.

## Assemblies

An assembly groups objects (and other assemblies) under one transform.

- **Create assembly from selection** (`Ctrl G`) makes a local assembly in the project.
- **Create linked assembly (.lasm)** saves the group as `files/assemblies/<name>.lasm` and replaces it with a
  linked instance in one step.
- **Link project as assembly...** links a whole other project, like Link in Blender.

### Link, Append, Make local

A **linked** instance reads its parts from the `.lasm` (or project) file. It is read-only, shows a lock instead of an
eye, and **updates when the source is saved**. Use **Reload from source** to force it and **Open source** to edit it.
**Make local** copies the parts and their files into your project so you can edit them.

Link paths are stored relative to the linking project and may use `..`. Linking a file that would contain itself, directly
or through other links, is refused (cycles are blocked).

### Break up and Realise

| Action | Result |
|---|---|
| **Break up in place** | The assembly becomes a folder of its parts. |
| **Realise (independent copies)** | The same, with independent copies of the files. |

For a linked assembly both first make it local, as one undo step.

## Arrays

Any object or assembly can carry an **Array** modifier (**+ Array** in Properties, or *Modifiers → Add Array* in the
menu). It works like Blender's: **Count** copies, a **Relative offset** (a fraction of the box built so far) and a
**Constant offset** (in blocks), added together when both are on. With several modifiers the copies form a grid, and each modifier measures the box
the ones above it have built. The axes are the object's own, so rotating or mirroring it turns the whole array. The
editor adds:

- arrays of **assemblies**, which repeat every part;
- **Realise array**, which turns the copies into separate objects in a `<name> (realised)` folder that replaces the
  array; realised objects can be arrayed again;
- copies show as fainter outlines and appear in the Top view.

The limit is 1024 copies per array.

## Overlaps, front order and Booleans

Where two objects occupy the same blocks, Litematica would show both. The editor resolves it by priority instead.

### Front order

Every object has a front number; `P1` is in front. Overlaps are shown in red and in the Scene's **Issues** list.

| Action | Keys |
|---|---|
| Bring to front | `Shift PgUp` |
| Bring forward | `PgUp` |
| Send backward | `PgDn` |
| Send to back | `Shift PgDn` |

The object further back loses the blocks it shares with the one in front. Only blocks that are not air count: an
air gap in front does not cut anything out of the object behind it. An unnumbered object sorts behind all numbered
ones, and ties are broken by id so the result is always the same.

### Boolean modifier

**+ Boolean** (or *Modifiers → Add Boolean*) cuts the object with another one chosen in the card:

- **Difference** removes the blocks the target occupies,
- **Intersect** keeps only those.

**Apply Boolean → .litematic** bakes the cut into a new file `files/<name>_bool.litematic`, points the object at it and
removes the modifier. Undo restores everything, and the file is moved to `.trash/`, never deleted.

### How it is done

Trims and Booleans are not drawn by a trick. The editor writes real, trimmed `.litematic` files into
`.cache/derived/` on a background thread and tells Litematica to place those instead. That is why the material list,
printers, the verifier and Syncmatica all see the trimmed result. While you drag with the gizmo nothing is rebuilt; the
rebuild starts after you release. The status bar shows "Rebuilding n derived files...".

Limits: an object can have at most 256 distinct trimmed variants per pass; copies beyond that keep the original file
and the status bar warns you. A wire object, a hidden object, an area from another mod and a Loose object that has no
front number never trim anything.

### Wire objects

*Draw as wire cutter* switches an object to a dotted outline: its own placement is hidden and it is never counted or
trimmed, nor does it trim anything. It is meant for objects that only serve as a cutting shape. A wire object cannot have
a Boolean itself; *Show cutter blocks* turns it back into a normal object.

## Hiding and local view

- **H** hides the selection, **Alt+H** shows everything again. A folder hides what is inside it.
- **/** enters *local view*: only the selection is shown, everything else is temporarily taken out of the world.
  Press **/** again to leave. It is never saved and ends when you change worlds.

## Exports

All exports go to `schematics/exports/` under a free name, and a toast tells you the path. They run on a background
thread.

| Export | Produces |
|---|---|
| Object → **Schematic (.litematic)** | A copy of the file. |
| Object → **Baked with modifiers (.litematic)** | One schematic with arrays and trims applied. |
| Assembly → **Assembly (.zip)** | Self-contained: the `.lasm` and every file it needs. |
| Folder → **Export folder (.zip)** | The folder's objects. |
| Project → **Project (.zip)** | The project folder without `.cache` and `.trash`. Links to *other* projects stay as `../Other` and need that project beside it. |
| **Bake to one .litematic** | One multi-region schematic of everything, for people who do not have the mod. |

Bake moves blocks with Litematica's own transform code. Block entities are copied to their new position (their NBT is
not rotated). Entities and scheduled ticks are not baked.

## Sharing on a server

On a server that runs **Liskov**, a project can be shared and edited by several players at once. Every player needs
Liskov and Vitruvius; the server needs Liskov.

1. *Project menu → Share on this server* (or **Share** in Properties). The project is copied to the server's drive
   (`Projects/<name>/` in Liskov's Explorer, **F8**) and pushed; from then on you edit that copy. Your local folder stays
   as it was.
2. With **Auto** ticked (the default) your edits are pushed two seconds after you stop changing things. Without it,
   the toolbar's **Push** button shows how many changes are not on the server yet. **Ctrl S** pushes a named version.
   A push sends the project file and the files of its objects (`files/`, linked `.lasm` too), and only the files the
   server does not have yet.
3. Another player opens it under *Open project... → Shared projects (server drive)*. From then on every version someone
   pushes is merged into the open project as it arrives ("Merged changes of Alex into ..."). The merge is per object and
   is made against the version the other player started from, so two players editing different objects both keep their
   work, even when they push at the same moment. If both edit the same object, one version wins (the same one on both
   computers); an edit beats a delete. Merged changes are not an undo step: undo only covers your own edits.
4. **Who may change it** is Liskov's access list: whoever shared the project is its admin, everyone else can view it
   until given **EDIT** (Explorer, *Sharing*, or `/liskov acl set` as an operator). A push the server refuses is tried
   again every ten seconds, and a toast says why. Locks (`/liskov lock`) work too.
5. **History**: select the project's `project.vproj` on the server drive in Liskov's Explorer to see every version and
   who pushed it, and to restore one; the restored version arrives in the editor like any other.
6. **Stop sharing (edit a local copy)** copies the shared project into your own `Projects/` and switches to that copy.
   The shared one stays on the server for the others.

Limits:
- Branches and per-player drafts (an experiment in the Syncmatica alphas) are gone: there is one shared line of versions.
- Players without Vitruvius see nothing of a shared project; place it for them from an export.
- Projects shared with Syncmatica in earlier alphas are not moved: share them again on the server.
- Visibility (hide/show) and the world a project is bound to stay per player.

## Undo and saving

- **Ctrl Z** undoes, **Ctrl Shift Z** or **Ctrl Y** redoes. Undo also covers file operations that the editor did.
- **Ctrl S** saves all open projects. Saving also moves derived files that nothing uses any more to `.trash/derived`.
- If you move a managed placement with Litematica's own tools (or a Syncmatica peer does), the editor adopts the move
  as an undoable *external change* instead of fighting it.

## Troubleshooting

| Problem | Try |
|---|---|
| Nothing happens on F6 | Check *Controls → Key Binds → Vitruvius*. Make sure a world is loaded. |
| An object is greyed out with `!` | Its Litematica placement was deleted. Right-click → **Delete**, or re-create the placement. |
| The server log says *moved too quickly* | Update to 2.0.0-alpha.34 or newer. Older versions moved the spectator player to the camera in one jump. |
| A shape or object looks cut off | It is being trimmed by something in front of it. Check front order and Booleans, and the Issues list. |
| A Boolean or trim does not show yet | The derived file is still being written. Watch the status bar. |
| A node has a **MOD** badge and is grey | It belongs to another mod that is not installed. It keeps its data and can still be moved, hidden and deleted. |
| "Placement not found" | See above. |
| The editor UI vanished after an error | The log has a line starting with `[vitruvius] editor UI failed`. Restart the game and report it with that log. |
| The old copy shows after editing a file on disk | Litematica caches loaded schematics. Reload it in Litematica. |
