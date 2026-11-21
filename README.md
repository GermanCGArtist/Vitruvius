# Vitruvius

A client-side Fabric mod that turns [Litematica](https://modrinth.com/mod/litematica) into a scene editor for
schematic builds. Place, move, rotate, mirror, array, trim and group schematics in a Blender-style 3D editor, share
whole projects over Syncmatica, and generate shapes from blocks, all on top of Litematica's own placements.

> **Naming.** The repository is called Vitruvius. The mod itself is still named **Vitruvius**, with the mod id
> `vitruvius` and the Java package `dev.vitruvius`. The mod id is kept on purpose so that mods built against it
> (Heavymatica) keep working.
>
> **Status.** This is the **2.0 line, currently alpha** (`2.0.0-alpha.6`, Minecraft 26.1.1). The stable 1.x line
> (arrays, folders, shape generator) lives in
> [Vitruvius](https://github.com/GermanCGArtist/Vitruvius) and is also part of 2.0.

## What it does

| Feature | Summary | Guide |
|---|---|---|
| **Editor (2.0)** | Press **F6** for an editor mode with a free camera, a scene tree, a Properties window, a move/rotate gizmo, a top-down map, undo/redo and context menus. | [Editor guide](docs/user-guide/editor.md) |
| **Projects and assemblies (2.0)** | A project is a folder with a scene graph, like a `.blend` file. Assemblies (`.lasm`) group objects and can be linked into other projects. | [Editor guide](docs/user-guide/editor.md#projects) |
| **Front order and Boolean (2.0)** | Overlapping objects are trimmed by priority. A Boolean modifier cuts one object with another. Results are real `.litematic` files, so the material list and printers see them. | [Editor guide](docs/user-guide/editor.md#overlaps-front-order-and-booleans) |
| **Arrays** | Repeat any object or assembly along X, Y and Z with a stack of Blender-style Array modifiers, with a live preview. | [Arrays, paste and folders](docs/user-guide/placement-tools.md#arrays) |
| **Placement folders** | Nested, colored folders in Litematica's placement list, with multi-select and drag and drop. | [Arrays, paste and folders](docs/user-guide/placement-tools.md#placement-folders) |
| **Shape Generator** | Builds boxes, spheres, domes, cylinders, arches, grids and more from blocks (with patterns) and saves them as schematics. | [Shape Generator](docs/user-guide/shape-generator.md) |
| **Syncmatica sharing (2.0)** | Push a project to a Syncmatica server. Other 2.0 clients can rebuild the structure from it. | [Editor guide](docs/user-guide/editor.md#sharing-with-syncmatica) |
| **API for other mods** | Add your own list entries, shape actions, and (2.0) full scene object types. | [API reference](docs/reference/api.md) |

Litematica stays the backend. Every object the editor manages ends up as an ordinary Litematica placement, so
rendering, printing, the verifier and the material list work as usual.

## Requirements

- Minecraft **26.1.1** (this branch; see [Branches](#branches)), Java **25**
- [Fabric Loader](https://fabricmc.net/) 0.19.3 or newer and Fabric API
- [Litematica](https://modrinth.com/mod/litematica) 0.27.x and malilib 0.28.x
- Optional: [Syncmatica](https://modrinth.com/mod/syncmatica) 0.3.20 for sharing projects

The ImGui UI library (`cg-uihost`) is bundled inside the jar. You do not install it separately.
The mod is client-side only.

## Install

There is no published 2.0 release yet. Build the jar yourself (see [Build](#build)) and put
`build/libs/vitruvius-2.0.0-alpha.6+mc26.1.1.jar` into your `mods` folder, next to Litematica, malilib and Fabric
API.

For the stable 1.x line, download a jar from the
[Vitruvius releases](https://github.com/GermanCGArtist/Vitruvius/releases).

> Do not run 1.x and 2.0 together. They are the same mod id.

## Quick start

1. Join a world with Litematica and load a schematic as a placement (`M` → **Load schematics**).
2. Press **F6**. The mouse is released, the editor windows appear, and the camera detaches from your player.
3. Your existing placements are listed under **Loose** in the Scene window. To keep a build, create a project
   (**New project** at the bottom of the Scene window) and drag objects into it.
4. Click an object, drag the gizmo arrows to move it, or press `G`, `X`, `5`, `Enter` to move it 5 blocks along X.
5. Select the object and add an **Array** in the Properties window to repeat it.
6. **Ctrl S** saves all open projects. **F6** leaves the editor.

Without pressing F6 you still get the classic features: an **Array...** button in Litematica's placement
configuration, folders in the placement list, and the **Shape Generator** in Litematica's main menu.

## Documentation

All documentation is in [`docs/`](docs/README.md):

- [Editor guide](docs/user-guide/editor.md): the 2.0 editor, projects, assemblies, modifiers, exports, Syncmatica
- [Arrays, paste and placement folders](docs/user-guide/placement-tools.md): the classic Litematica-screen features
- [Shape Generator](docs/user-guide/shape-generator.md)
- [Keyboard and mouse reference](docs/reference/controls.md)
- [File formats and config files](docs/reference/file-formats.md)
- [API for other mods](docs/reference/api.md) and [API v2 (scene objects)](docs/editor-2.0/API-v2.md)
- [Development guide](docs/development.md): build, architecture, tests, porting
- [Design notes](docs/editor-2.0/DECISIONS.md) and [architecture](docs/editor-2.0/EDITOR-2.0.md)

## Branches

One branch per Minecraft version is the project's convention, and `main` follows the newest one that is developed.
At the moment this repository has only `main`, which builds for Minecraft 26.1.1. Ports to other versions are planned
(milestone M7) and follow the 1.x versions:

| Minecraft | Litematica | malilib | State in this repository |
|---|---|---|---|
| 26.1.1 | 0.27.x | 0.28.x | `main` (alpha) |
| 26.1.2 | 0.27.x | 0.28.x | planned |
| 26.2 | 0.28.x | 0.29.x | planned |
| 26.3 | 0.29.x | 0.30.x | `26.3` branch (alpha; OpenGL and Vulkan) |

## Build

Needs **JDK 25**.

```bash
./gradlew build          # Linux, macOS, Git Bash
build.bat                # Windows (uses a portable JDK from tools/ if present)
./gradlew test           # unit tests only
```

The jar lands in `build/libs/`. Litematica and malilib are fetched from Modrinth's Maven and used for compiling only.
See the [development guide](docs/development.md) for details.

## Known limitations (alpha)

- Only Minecraft 26.1.1 builds from this repository so far.
- Syncmatica push and receive have unit tests but have **not** been run against a real server yet
  ([test sheet](docs/editor-2.0/testing/M6.md)).
- If a schematic file is edited on disk while Litematica holds a copy, Litematica shows the old copy until it reloads it.
- Most in-game behaviour is checked by hand with the [test sheets](docs/editor-2.0/testing/M0.md), not by automated tests.

## License

[MIT](LICENSE)
