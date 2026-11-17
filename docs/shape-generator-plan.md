# Shape Generator — implementation plan (Vitruvius 1.4.0, MC 26.1.1 only)

## What the user gets

`M` (Litematica main menu) → new button **Shape Generator** → a screen that builds simple shapes as schematics:

- **Shapes**: Box, Plane, Frame (edges only), Ellipsoid/Sphere, Dome, Cylinder, Cone, Pyramid, Torus, Arch, Tube,
  Grid (lattice of lines). Size up to 256 per side.
- **Two block slots**: *Outline* (the shell, with a thickness) and *Fill* (inside; empty = hollow / air).
- **Pattern per slot**: Single, Mix (weighted random, seeded), Checker (cell size X/Y/Z, 2+ blocks cycled),
  Stripes (axis, width, N blocks), Gradient (block list along an axis, optional dithering), Diagonal, Brick.
- **Block input**: text field with block-state syntax (`oak_log[axis=x]`) + autocomplete, a "from hand" button,
  and the field also takes a weighted mix `60%stone_bricks,30%cracked_stone_bricks,10%mossy_stone_bricks`.
- **Preview, both kinds**: (1) live ghost in the world = a temporary Litematica placement at the player's feet
  (anchor min-corner or center, switchable), updated ~250 ms after the last change; (2) a rotatable 3D thumbnail
  in the screen (drag = orbit, scroll = zoom). A **Peek** button/hold-key hides the panel so you see the ghost.
- **Generate**: writes `<name>.litematic` into Litematica's schematics folder, then replaces the temporary
  placement by a normal placement of that file at the same spot. **Cancel**/Esc removes the ghost.
- **Presets**: last settings remembered; named presets in `config/vitruvius/shapes.json` (save / load / delete).

## Architecture

```
dev.vitruvius.shape            (pure Java, NO Minecraft imports, unit tested)
  ShapeType        enum, with per-type parameter labels/visibility
  Axis             enum X, Y, Z
  ShapeSpec        record: type, sizeX/Y/Z, axis, thickness, param1, param2 (torus minor radius, grid
                   spacing, line width, cone top size, …), anchorCenter
  Shapes           static boolean inside(ShapeSpec, x, y, z) per type
  PatternMode      enum SINGLE, MIX, CHECKER, STRIPES, GRADIENT, DIAGONAL, BRICK
  BlockEntry       record: String blockState, int weight
  BlockSlot        record: PatternMode mode, List<BlockEntry> entries, int cellX/Y/Z, Axis axis,
                   int width, long seed, boolean dither        (entries empty = air)
  Patterns         static int pick(BlockSlot, x, y, z, sizeX, sizeY, sizeZ) -> index into entries
  VoxelResult      record: sizeX/Y/Z, short[] cells (0 = air, k = palette[k-1]), List<String> palette,
                   int blockCount;  index = (y * sizeZ + z) * sizeX + x
  ShapeGenerator   static VoxelResult generate(ShapeSpec, BlockSlot outline, BlockSlot fill, BooleanSupplier cancelled)
                   inside mask -> outline = inside cells within `thickness` of an outside cell
                   (6-neighbour erosion, cells outside the box count as outside) -> fill = the rest
  GeneratorSettings record: ShapeSpec, outline, fill, name     (what presets store, Gson friendly)

dev.vitruvius.shape.mc        (Minecraft / Litematica glue)
  BlockParsing     String -> BlockState (BlockStateParser), validation message, block id list for autocomplete,
                   main-hand item -> block state string, "a%b,c%d" mix syntax -> List<BlockEntry>
  ShapeSchematics  VoxelResult -> LitematicaSchematic: createEmptySchematic(AreaSelection with one Box named
                   "Shape", author = player name), getSubRegionContainer("Shape").set(x, y, z, state)
  ShapePreview     holds ONE temporary placement (SchematicPlacement.createTemporary) in the
                   SchematicPlacementManager; update(VoxelResult, origin) replaces it; clear(); also cleared on
                   world leave. Must be invisible to Folders/ArraySync/placement list (check by identity).
  ShapeSaver       writeToFile(schematics dir, name, override?) -> load file -> SchematicPlacement.createFor(...)
                   at the preview's origin -> add to manager -> ShapePreview.clear()
  ShapePresets     Gson load/save of config/vitruvius/shapes.json: { "last": settings, "presets": {name: settings} }
  PreviewWorker    single-thread executor; debounce 250 ms; cancels the running job when a new one starts;
                   hands the VoxelResult to the render thread (Minecraft.execute)

dev.vitruvius.shape.gui
  ShapeGeneratorScreen  malilib GuiBase. Left column: shape dropdown, size fields (± buttons like Litematica's),
                   axis, thickness, shape-specific params (only those the type uses), anchor, Outline + Fill slot
                   editors, name field, preset row, Generate / Cancel / Peek. Right: the 3D thumbnail. Status line
                   shows block count, size and parse errors (Generate disabled while a block is invalid).
  BlockSlotEditor  widget group: mode dropdown, block field(s) with weight, "+ block", "from hand", mode params
  ShapeThumbnail   3D view of the VoxelResult: only surface cells (any air neighbour), textured with each block's
                   model sprite per face, orbit/zoom, face cap ~150k (above it: map-colour cubes). Rebuilt on a
                   new result, not every frame.

mixin/GuiMainMenuMixin   inject at TAIL of GuiMainMenu.initGui -> add "Shape Generator" button below the
                         existing column (read the button positions from the screen, don't hardcode)
```

Rules for everyone:
- Version-specific Minecraft calls go into `Compat` (or a clearly marked `ShapeCompat`) — the addon is ported
  to 3 more MC versions later.
- Check Litematica/malilib signatures with `javap -cp libs/litematica-fabric-*.jar <class>` before using them.
- Build with `build.bat` (or `gradlew build`). **Never start or kill Minecraft**; the user tests in game.
- Code style: match the existing files (short Javadoc on each class saying why it exists, no dead code).

## Work packages

Base branch `26.1.1`. Phase 0 (architect) commits the contract: all `dev.vitruvius.shape` records/enums with
signatures and `UnsupportedOperationException` stubs, the JUnit test dependency, `mod_version=1.4.0`, on branch
`shapes`. Then each agent works in its own git worktree on its own branch off `shapes`, touching only its files.

| WP | Agent | Files | Done when |
|----|-------|-------|-----------|
| A Core | Sonnet | `shape/*` (Shapes, Patterns, ShapeGenerator) + `src/test/...` | all 12 shapes + 7 patterns implemented, unit tests for sizes 1, odd/even, thickness, hollow fill, cancel; 256³ box generates < 2 s |
| B Litematica glue | Sonnet | `shape/mc/*`, `GuiMainMenuMixin`, mixins json, `VitruviusMod` | button in `M` opens a placeholder screen; preview/save/presets/parsing work through a tiny debug command or called from the placeholder; builds |
| C Screen | Sonnet | `shape/gui/ShapeGeneratorScreen`, `BlockSlotEditor` | full screen wired to B's API and A's generator, thumbnail area left as a box |
| D Thumbnail | Sonnet | `shape/gui/ShapeThumbnail` (+ Compat bits) | textured surface render, orbit/zoom, fallback above the face cap |
| E Docs | Haiku | `README.md` section "Shape Generator" | written from the merged code |

A, B, C, D run in parallel (contract is fixed). Merge order A → B → D → C, architect resolves conflicts, builds,
reviews, then E. User tests in game on 26.1.1; ports to 26.1.2 / 26.2 / 26.3 and releases come later.
