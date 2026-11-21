# Development guide

How the project is built, how the code is laid out, and how to work on it. For the design reasoning, read the
[decisions](editor-2.0/DECISIONS.md) first and then the [architecture](editor-2.0/EDITOR-2.0.md), which also has the
detailed implementation notes.

## Build

Requirements: **JDK 25**. The Gradle wrapper is included, so you do not need Gradle installed.

```bash
./gradlew build      # compile, test, produce the jar
./gradlew test       # unit tests only
```

On Windows, `build.bat` does the same. It also looks for a portable JDK in `tools\jdk-25*` (or `..\Manhattan\tools\jdk-25*`),
falls back to `JAVA_HOME`, and keeps Gradle's temporary files in `tmp\` inside the repository.

The jar is written to `build/libs/vitruvius-<mod_version>+mc<minecraft_version>.jar`.

### Versions

Everything version-specific is in [`gradle.properties`](../gradle.properties):

| Property | Meaning |
|---|---|
| `minecraft_version`, `minecraft_dep` | The Minecraft version, and the range written into `fabric.mod.json`. |
| `loader_version`, `loom_version`, `fabric_api_version` | Fabric toolchain. |
| `litematica_version`, `malilib_version` | Modrinth version numbers of the mods compiled against. |
| `mod_version` | The mod's own version. |

Litematica and malilib are `compileOnly` dependencies fetched from Modrinth's Maven. Minecraft 26.1 and newer is
unobfuscated, so no mappings are used.

### Subprojects

| Project | Mod id | Role |
|---|---|---|
| root | `vitruvius` | The mod. |
| `ui-host/` | `cg-uihost` | The shared Dear ImGui host: ImGui context, natives for Windows, Linux and macOS (x64 and arm64), GLFW input routing, the font atlas and a frame hook. It is nested into the main jar (jar-in-jar), so players install one file. |

Other mods register UI with `UiHost.register(UiLayer)`. Only one mod can own the ImGui context in a game; the host exists so
that two mods can share it.

## Source layout

```
src/main/java/dev/vitruvius/
  VitruviusMod.java        client entry point
  Folders, DragDrop, WidgetFolderRow, MoveScreen, ColorMenu, ...   placement-list folders and colours
  api/                      public API for other mods (v1 and v2)
  shape/                    Shape generator engine: pure core (no Minecraft) + mc/ (the window is editor/view/ShapeWindow)
  mixin/                    mixins into Litematica and malilib screens
  editor/                   the 2.0 editor (below)
src/main/resources/         fabric.mod.json, mixin configs, lang
src/test/                   JUnit tests and fixtures
ui-host/                    the cg-uihost subproject
docs/                       documentation
```

### The editor, `dev.vitruvius.editor`

| Package | Contents | Needs Minecraft? |
|---|---|---|
| `core` | The model: nodes (`ObjectNode`, `AssemblyNode`, `FolderNode`, `ProjectNode`, `ExternalNode`, `LooseNode`), `Transform`, `Box`, modifiers, `ArrayCells`, `Overlaps`, `FrontOrder`, `MaskPlan`, `CellMasks`, `CycleCheck`, `Ids`. | No |
| `io` | `ProjectIo` (`project.json`), `AssemblyIo` (`.lasm`), `ZipExport`, `BakeLayout`, `Migration1x`. | No |
| `ops` | Every undoable edit as a command (`Move`, `Rotate`, `Duplicate`, `Reparent`, `SetFront`, `ModifierCommand`, `RealiseArray`, `SaveAsLinked`, ...), the `UndoStack`, and file work through a `FileSystemPort`. | No |
| `gizmo`, `pick`, `ui` | Math and models behind the gizmo, picking, the menus, the tree and the Top view. | No |
| `sync` | Syncmatica manifest, plan and bridge (the bridge uses reflection only). | Bridge only |
| `mc` | The bridge to the game: `Reconciler`, `LitematicaAdapter`, `DerivedFiles`, `Bake`, `SchematicInfo`, `Picking`. | Yes |
| `cam`, `mixin`, `world` | The editor camera, its mixins, and the in-world overlay and gizmo. | Yes |
| `view` | ImGui windows: Scene, Properties, Top view, toolbar, menus, toasts. | ImGui |

**The rule that shapes the code:** everything that can be pure *is* pure and has no Minecraft imports, so it is unit
tested like the shape generator. `LitematicaAdapter` is the only class that touches Litematica's placement API.

### How the editor works

```
ImGui windows ──► commands (undoable) ──► model (projects, nodes)
                                              │ observed every client tick
                                              ▼
                                        Reconciler ──► Litematica placements
                                              ▲                 │
                                              └── drift ◄───────┘
                              DerivedFiles (background thread) writes trimmed .litematic files
```

- **The model is the source of truth** for everything the editor manages. Litematica placements are its output.
- The **Reconciler** makes the set of placements match the model each tick: one placement per visible object and per array
  cell, using a derived file where trims or Booleans apply. At most 64 are created per tick.
- It also **reads back**. If a managed placement was moved outside the editor, the move becomes an undoable
  *external change* instead of a fight.
- **Derived files** are generated off the client thread, debounced, and never while a gizmo drag or a transaction is open.
- **Undo** is a stack of commands; a gesture is one transaction.

## Tests

```bash
./gradlew test
```

The suite uses JUnit 5 and covers the pure layers: transforms, arrays, overlaps, front order, masks, the cycle check,
project and assembly I/O, migration (fixtures in `src/test/resources/migration`), exports, gizmo math, picking,
Syncmatica manifests, the object-type API and the shape generator.

What the tests do **not** cover is anything that needs a running game: the reconciler against real Litematica, bake,
derived files, the camera and the ImGui windows. Check those by hand in a running game before a release, on a clean
profile with the target Minecraft and Litematica versions. Branches have their own two-player sheet in
[BRANCHES.md](BRANCHES.md).

## Conventions

- **Keep logic pure.** If a new rule does not need a Minecraft type, put it in `core`, `ops` or `gizmo` and test it.
- **Check a Litematica API before you use it for the first time**, for example with `javap` on the Litematica jar in the
  Gradle cache. Its signatures change between Minecraft versions.
- **Never delete the user's files.** Move them to `.trash/` so Undo can restore them.
- **Placement ids are a cache.** Model ids are UUIDs that the editor owns; Litematica placement ids are stored beside them
  and never used as identity.
- **Unknown JSON survives.** Preserve fields you do not understand when you read and write.
- Do not call the public API from other threads, and keep per-frame callbacks (`renderOutline`) cheap.

## Porting to another Minecraft version

The convention is one branch per Minecraft version, with the version-specific values in `gradle.properties`.

1. Create the branch from the last ported one and change `minecraft_version`, `minecraft_dep`, `fabric_api_version`,
   `litematica_version` and `malilib_version`.
2. Build. Fix what the newer Litematica and Minecraft broke. The places that have needed changes before:
   - **Minecraft 26.2 and later:** the current screen is read with `mc.gui.screen()`.
   - **Minecraft 26.3:** key handling uses the scancode (`input()` and `isEscape`), not `key()`.
   - `Compat` and `ThumbnailCompat` gather the version-specific screen and mouse-button access in one place per version.
3. Run the unit tests, then the manual sheets.
4. Release: tag as `v<mod_version>+mc<minecraft_version>`, and attach the jar.

26.3 is the developed line (`main`); `26.1.1` keeps the last 26.1.1 build (alpha.17).

## Releasing

Versions follow `<mod_version>+mc<minecraft_version>`; `2.0.0-alpha.N` marks the alpha stage. Tags use the same string
with a `v` prefix (for example `v1.6.1+mc26.3` in the 1.x repository).
