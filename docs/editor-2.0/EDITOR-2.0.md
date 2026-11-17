# Vitruvius 2.0: editor architecture and work packages

Source of truth for implementation. Product decisions are in `DECISIONS.md`. The visual spec is `design/DESIGN.md` (from the design agent); where the two disagree on looks, DESIGN.md wins, and on behaviour this file wins. Base branch: `2.0-dev` (off `26.1.1`).

## 1. Shape of the system

```
            ImGui windows (Scene, Properties, Top view, Toolbar, Status, Menus)
                  │ read model / send commands
                  ▼
 Input ──► EditorController ──► Commands (undoable) ──► Model (projects, nodes)
 (keys,        │ camera, tools, gizmo drag                  │
  mouse)       ▼                                            ▼ observed by
          WorldOverlay (gizmo, outlines, overlaps)     Reconciler (every client tick)
                                                            │ creates/moves/removes
                                                            ▼
                         Litematica placements  ◄── DerivedFiles (async .litematic writer)
                                 │
                                 ▼ unchanged Litematica: render, print, material list, verifier, Syncmatica
```

- **The model is the source of truth for everything the editor manages.** Litematica placements are its output.
- **The Reconciler** generalises today's `ArraySync`. Each tick it makes the set of Litematica placements match the model: one placement per visible schematic object, per array cell, using the derived file when the object is trimmed or Booleaned.
- **It also reads back.** If a managed placement was moved outside the editor (Litematica's own hotkeys or GUI, or a Syncmatica peer), it writes that move back into the model as an undoable "external change" instead of fighting it.
- **Pure logic has no Minecraft imports:** model, transforms, arrays, overlaps, the cycle check and serialisation. It lives in `dev.vitruvius.editor.core` and is unit-tested like the shape generator (`src/test`).

## 2. Packages (new code)

| Package | Contents | MC imports? |
|---|---|---|
| `editor.core` | `Node` hierarchy, `Transform` (pos, rot 0–3, mirrorX), `Box`, `ArrayStack` (reuses `ArrayStore.Mod` semantics), `Overlaps`, `FrontOrder`, `CycleCheck`, `Ids` | no |
| `editor.io` | `ProjectIo` (project.json), `AssemblyIo` (.lasm), `ZipExport`, `Migration1x` (folders.json + arrays.json → Loose) | no (Gson + NIO only) |
| `editor.ops` | `Command` interface, `UndoStack`, all edit commands (Move, Rotate, Mirror, Duplicate, LinkedDuplicate, Realise, BreakUp, MakeLocal, SetFront, AddModifier, ApplyBoolean, Reparent, Rename, Hide, Delete, CreateAssembly, LinkProject), plus file-side effects with undo (create/delete files under the project) | no |
| `editor.mc` | `Reconciler`, `LitematicaAdapter` (the **only** class that touches Litematica placement APIs), `DerivedFiles` (async mask + write), `SchematicInfo` (size, block counts, cached), `Picking` (ray vs schematic world + boxes) | yes |
| `editor.cam` | `EditorCamera` (orbit + fly state), camera mixin, spectator follow | yes |
| `editor.view` | ImGui windows: `SceneWindow`, `PropertiesWindow`, `TopViewWindow`, `Toolbar`, `StatusBar`, `ContextMenus`, `Hud`, `Toasts`, `EditorTheme` | ImGui only |
| `editor.world` | `WorldOverlay`: gizmo, selection/ghost/overlap/area/wire outlines, name tags, box-select rect (modelled on Heavymatica's `WireframeRenderer` for 26.1.1) | yes |
| `editor.api` | new object-type API for Heavymatica (see §7) | no |
| `uihost` (separate Gradle subproject `ui-host/`, its own mod id `cg-uihost`, jar-in-jar) | ImGui context, natives for all platforms, GLFW input routing, font atlas, frame hook, `UiHost.register(UiLayer)` | yes |

The old malilib list mixins stay. The placement list keeps working for people who never press F6, and it also shows projects as folders.

## 3. Data model

```
Node { id (uuid), name, hidden, children? }
 ├─ ProjectNode   { path, world, dim, origin, active, sync: local|shared }    (top level only)
 ├─ FolderNode    { open }                                                   (no transform)
 ├─ LooseNode                                                                (top level, one, not saved in a project)
 ├─ ObjectNode    { file (relative to project), transform, front, mods[], wire, placementIds{cell→hash} }
 ├─ AssemblyNode  { transform, front, mods[], source: local | link(path) }   children = parts
 └─ ExternalNode  { typeId, transform, data(JsonObject) }                    (Heavymatica areas and others via API)
Modifier = Array { enabled, count, relative[3], useRelative, constant[3], useConstant }
         | Boolean { enabled, op: DIFFERENCE|INTERSECT, targetId }
```
- **Transforms compose from the root down.** Folders and projects have none. Rotation is about the node's origin; the editor keeps the pivot visually centred by adjusting `pos` when it rotates.
- **`front`** is a global integer per world (1 = front), shared by all open projects. Ties break by node id, so the result is always the same.
- **Linked assemblies:** the `children` of a linked AssemblyNode are loaded from the `.lasm` and not saved in the project; they are read-only. Make Local copies them in.
- **Files on disk:** `schematics/Projects/<name>/project.json`, `files/**`, `.cache/derived/<objectId>-<hash>.litematic`. A `.lasm` has the same JSON shape as a project's node list, with no world/dim.
- **IDs:** every node has a UUID. Litematica placement hash ids are kept in `placementIds` and are never used as model ids.

## 4. Reconciler rules
1. Walk the model; for every visible ObjectNode, compute its world cells (base + array cells) and the file each cell uses (original, or derived when an enabled Boolean or a lost overlap applies).
2. For each cell, find its placement by stored hash id. If it's missing or uses the wrong file, create it with `SchematicPlacement.createFor`; set origin, rotation and mirror (same transform rule as `ArraySync.transform`).
3. Remove managed placements that are no longer wanted. Never touch placements that aren't managed, except Loose ones.
4. **Drift:** if a managed base placement's origin, rotation or mirror differs from the model, and the editor didn't cause it this tick, record an `ExternalChange` command (undoable) and update the model.
5. **Budget:** at most 64 placement creations per tick, and rebuilds batch with `markChunksForRebuild`, like `ArraySync`'s delayed rebuild.

`ArraySync` keeps running only for 1.x arrays that haven't migrated yet. Migration moves them into ObjectNodes under Loose, and from then on the Reconciler owns them.

## 5. Derived files
- **Trigger:** a model change that affects an object's mask (its Boolean target, its front order, or the transform of anything overlapping it). It's debounced, and while a gizmo drag is active it waits for the release.
- **Job (worker thread):** load the source `LitematicaSchematic`, copy each sub-region's block container, and set masked positions to air.
  - Difference: the target's non-air blocks.
  - Intersect: everything outside them.
  - Trims: blocks inside the volumes of objects with a better front value, counting only their non-air blocks.
  - Then write `.cache/derived/<id>-<maskHash>.litematic`.
  - Writing the same mask hash again is a no-op (it's a cache).
- **Swap:** the Reconciler moves the cells to the new file on the client thread when the job finishes. The status bar shows the queue.
- **Apply Boolean:** copy the current derived file to `files/<name>_bool.litematic`, point the object at it, and remove the modifier (undoable: the file is moved to `.trash/` on undo, never deleted).
- **Cache cleanup:** on project save, delete derived files that no node references.

## 6. Editor mode, camera, input
- **F6 toggles editor mode** (rebindable). On enter: release the mouse, start the editor camera at the player's eye, and set the ImGui windows visible. On exit: restore the camera and grab the mouse again.
- **Camera:** a mixin on `Camera.setup` (or the 26.1.1 equivalent) replaces position and rotation while the editor is active.
  - Orbit: target, distance, yaw and pitch.
  - Fly: position, yaw and pitch, WASD/QE, Shift ×3, wheel speed.
  - Hold the right mouse button to fly, with the mouse grabbed while held. A right click under 4 px of movement opens the context menu.
- **Spectator:** each tick, send the player to the camera position (client-side movement is accepted in spectator), so chunks load. In other game modes the player stays put.
- **Input priority:** ImGui windows (when hovered or focused) > typed-transform modal > gizmo > viewport tools > camera. Vanilla keybinds are blocked while the editor is active, except chat and F-keys.
- **Picking:**
  1. Raycast against Litematica's schematic world. Map the hit block to the cells whose box contains it, and choose by front order.
  2. Fall back to ray-vs-box.
  3. Alt cycles through every owner hit along the ray.

## 7. Heavymatica object API (`editor.api`)
```java
public interface SceneObjectType {
  String id();                                       // "heavymatica:area"
  String displayName();
  JsonObject create();                               // new default data
  Box bounds(JsonObject data);                       // local bounds, block units
  void renderOutline(JsonObject data, Transform world, OverlayDrawer d, boolean selected);
  void drawProperties(JsonObject data, PropertyUi ui); // ImGui section; returns via ui.changed()
  default boolean affectsBlocks() { return false; }  // Heavymatica: false (never trims, never Booleans)
}
VitruviusApi.registerObjectType(SceneObjectType)
VitruviusApi.objectsOfType(String typeId) -> List<SceneObjectView> // world transform + data, for Manhattan export
```
The `API_VERSION` goes to 2. The 1.5 list-entry API keeps working: its entries show under Loose as read-only rows.

## 8. Syncmatica (M6)
- **Detection:** reflection only, so there's no compile dependency. If Syncmatica isn't present, the Push button is hidden.
- **Push changes:**
  - Each visible cell is shared as a placement, using the derived file where one applies.
  - A manifest placement is shared too: a 1×1×1 air schematic named `[LA2] <project>` whose description holds the project's node tree (gzip+base64 when it's over 30 kB).
  - Placements this project pushed earlier that no longer exist are removed.
- **Receive:** a manifest seen in Syncmatica's list is offered as "Open shared project". The placements that come with it are adopted as the cells of that project.

## 9. Work packages

**Rules for every package:**
- Branch `2.0-dev`. One package per agent, and only the files listed.
- Build with `cmd /c "F:\workflow\Vitruvius\build.bat --console=plain -q"`, and `gradlew test` for `core`/`io`/`ops`.
- Never start, stop or kill Minecraft, and never copy jars into a profile. The architect installs.
- Commit with the noreply email `344753632+GermanCGArtist@users.noreply.github.com`, one commit per package, and don't push.
- Before calling a Litematica API you haven't used before, check its descriptor with `javap` on the 0.27.14 jar in the Gradle cache.

**Agent column:** S = Sonnet, G = Gemini (Antigravity CLI, Hydrogen), H = Haiku, A = architect review only.

| WP | Milestone | Agent | Owns | Done when |
|---|---|---|---|---|
| 0.1 | M0 | S | `ui-host/**`, root `settings.gradle`, `build.gradle` include | A subproject builds a `cg-uihost` jar with imgui-java 1.90 binding + lwjgl3 backend + natives for win/linux/mac x64+arm64. `UiHost.register(UiLayer)`, frame hook via a `RenderSystem.flipFrame` mixin, keyboard/mouse mixins that ask registered layers `wantsInput()`, a `UiHost.theme()` copy of Manhattan's UiTheme tokens + fonts. Vitruvius nests it with `include`. A demo layer (Ctrl+F6) shows ImGui's demo window. |
| 0.2 | M0 | S | `editor/cam/**`, camera mixin, `EditorMode` | F6 toggles editor mode. Orbit, pan, zoom, fly and frame work. Spectator follow. Vanilla movement is blocked in editor mode. HUD placeholder text drawn via uihost. |
| 0.3 | M0 | H | `editor/core/Transform`, `Box`, tests | Pure transform/box maths matching `ArraySync.transform`, 100% unit-tested (rot × mirror × offsets). |
| 1.1 | M1 | S | `editor/core/**` (except 0.3), `editor/io/**`, tests | Node model, project.json/.lasm round-trip, Migration1x from real 1.6 configs (fixtures in `src/test/resources`), cycle check, front order. Tests green. |
| 1.2 | M1 | S | `editor/ops/**`, tests | UndoStack + all commands in §2 as pure model ops (file effects through a `FileSystemPort` interface, faked in tests). |
| 1.3 | M1 | S | `editor/mc/LitematicaAdapter`, `Reconciler`, `SchematicInfo` | §4 implemented. Opening a project shows its placements; closing removes them; drift is adopted. |
| 1.4 | M1 | G or S | `editor/view/**` (Scene, Properties, ContextMenus, Toolbar, Status, Toasts) | Built from `design/DESIGN.md`: tree with badges, rename, drag-reparent, filter, multi-select; properties for each node type; every menu entry wired to a command (disabled when not available). |
| 1.5 | M1 | S | `editor/mc/Picking`, `editor/world/WorldOverlay` (selection only) | Click/Shift/Alt selection in the world, selection outlines, name tags. |
| 2.1 | M2 | S | `editor/world/Gizmo`, drag logic, snapping | Gizmo per DESIGN.md, ghost while dragging, commit on release, face snapping, multi-select pivot modes. |
| 2.2 | M2 | H | typed-transform modal + HUD | G/R + axis + number + Enter/Esc, with live preview. |
| 2.3 | M2 | H | duplicate/linked duplicate/hide/local view keys | Shortcuts wired to existing commands; local view filters the Reconciler's visibility. |
| 3.1 | M3 | S | arrays on Object/Assembly nodes in the Reconciler; Realise wiring | Arrays of assemblies work, Realise produces a folder of objects, and they can be arrayed again. |
| 4.1 | M4 | S | `.lasm` link/append/make local/break up/realise, project-as-assembly, exports (`ZipExport`, bake) | Per §3/§5; Bake writes one multi-region `.litematic`. |
| 5.1 | M5 | S | `editor/mc/DerivedFiles`, overlap + Boolean masks, Apply | §5; the material list reflects trims (verified on a fixture). |
| 6.1 | M6 | G or S | `TopViewWindow` | Per DESIGN.md: pan/zoom, select, move camera, overlaps shown. |
| 6.2 | M6 | S | Syncmatica bridge (§8) | Push/receive against a local test server with Syncmatica. |
| 6.3 | M6 | S | `editor/api/**` + Heavymatica branch `2.0-api` | Heavymatica areas are scene objects; Manhattan export still works. |
| 6.4 | M6 | S | Manhattan: move to `cg-uihost` (separate worktree off Manhattan `main`, **after** the user's `feat/spatial-chunking` lands) | Manhattan registers its Shell as a UiLayer; both mods run together. |
| 7.x | M7 | S/H | ports to 26.1.2 / 26.2 / 26.3, releases | Per the 1.x porting notes (scancode keys on 26.3, `mc.gui.screen()` on 26.2+). |

**Known risk:** until WP 6.4 lands, Manhattan's own ImGui and `cg-uihost` both claim the GLFW callbacks when both mods are installed. During development we test Vitruvius 2.0 without Manhattan, or the host stays off while Manhattan's workspace is open. WP 6.4 removes the conflict.

## 10. Notes from implementation
- **(WP 1.1) Base placement key:** `ObjectNode.BASE_CELL = "base"` is the key of the object's own placement in `placementIds`. Array cells keep their 1.x "i,j,k" keys.
- **(WP 1.1) Front order:** `front = 0` means unnumbered and sorts behind everything; ties break by id.
- **(WP 1.1) Session state:** `ProjectNode.path` and `active` are not saved.
- **(WP 1.1) Linked assemblies:** children are not saved. Whoever resolves links (WP 1.3/4.1) loads the `.lasm` and must give each linked child an **instance-scoped id**, `Ids.derived(instanceId + ":" + childId)`, because the same `.lasm` linked twice would otherwise give duplicate ids.
- **(WP 1.3) Structure:** `ReconcileCore` (planning and writing, no Minecraft) talks to a `PlacementPort`; `LitematicaAdapter` is the real port and `Reconciler` the per-tick wrapper (join sequence, world key, ownership queries for `ArraySync`). `ProjectManager` (no Minecraft) owns the `Scene`.
- **(WP 1.3) Drift rule:** a base placement counts as moved outside the editor when it differs from the transform the reconciler last wrote or saw, so a change of the model is never drift. On the first sight after a join the model wins in a project and the placement wins in Loose. `ExternalChange(node, before, after)` is emitted after the model was updated; WP 1.2 hooks in with `Reconciler.setDriftListener`.
- **(WP 1.3) Placement ids:** the stored hash id is reused as the UUID when a placement has to be created again (hide/show, close/open, wrong file), so project.json does not churn. Hidden nodes keep their ids.
- **(WP 1.3) Loose and session:** after the migration Loose lives in `config/vitruvius/editor-loose/<world>.lasm`; `config/vitruvius/editor-session.json` holds per world the `migrated` flag, the open project folders and the active one. A Loose object whose placement is not in Litematica is never deleted or recreated: it gets the runtime flag `ObjectNode.missing()` (not saved; the UI greys it out with a "placement not found" hint), is skipped, and the flag clears when the placement is back. Only an explicit Delete removes it. Migrated nodes are never hidden: 1.x folder hiding stays with `Folders.tick`, which switches rendering.
- **(WP 1.3) Ownership bridge:** once an array is owned by an ObjectNode, `ArraySync` skips it, and its helpers (`isCopy`, `original`, `mods`, `setMods`, `copyCount`, `copyIds`) read and write the node's modifiers. `arrays.json` and `folders.json` are never written by the editor.
- **(WP 1.3) Holder:** the editor never removes anything from Litematica's `SchematicHolder`. Placements load files through `getOrLoad`; `SchematicInfo` uses a private copy (`LitematicaSchematic.createFromFile`, cached by path and mtime). Known limitation: if an original is edited while Litematica holds a copy, Litematica shows the old copy until it reloads it.
- **(WP 1.3) Not yet:** assembly-level arrays (WP 3.1), derived files (WP 5.1), adopting placements the player makes in Litematica after the migration.
- **(WP 2.1) Gizmo split:** the maths is in `editor/gizmo` (`AxisProjection`, `FaceSnap`, `Pivots`, `TypedTransform`, `BoxSelect`, `GizmoLayout`, `TurnMath`; no Minecraft or ImGui types, all unit-tested). `editor/world/Gizmo` is the glue: it takes mouse and key events from `EditorInputLayer`, draws arrows, ring, snap label and box rectangle with ImGui's background draw list, and hands a `Preview` to `WorldOverlay`, which draws the dashed x-ray ghost, tether and snap face with Minecraft gizmos. A gesture never moves the placement; release or Enter commits one `Move`/`Rotate`/`Duplicate` inside one undo transaction.
- **(WP 2.1) Pivot:** always a block: the block holding the centre (an even size takes the lower middle block). `EditorActions` asks the host for it, so Rotate, Mirror and the Properties rotation buttons follow the toolbar's pivot mode too. Snap and pivot mode are saved in `editor-ui.properties`.
- **(WP 2.1) Snapping:** within 1.5 blocks along the drag axis, other axes within 1.5 blocks; butting beats flush on a tie; targets are the owners (object or top assembly) that are not part of the selection. A plane drag snaps X, then Z.
- **(WP 2.2) Typed transform:** G/R/Shift D/Alt D start it; the key handling lives in `Gizmo.onKey`, checked before the other shortcuts (DESIGN 12.4). Shift D/Alt D run one `Duplicate`/`LinkedDuplicate` with the typed delta on Enter (not a duplicate at once, then a move), so Esc leaves no stray copy and one Ctrl Z undoes it.
- **(WP 2.3) Local view:** a runtime filter in `ReconcileCore.setLocalView(owners)`: owners in projects that are not listed are treated as hidden for the pass (their placements are removed and come back when it is cleared). Nothing is saved and Litematica's render toggles are untouched. Loose objects are exempt (removing their placement would mark them missing). `EditorSession.boxes()` applies the same filter, so picking and the gizmo only see what is visible. It switches off when the world changes.
- **(WP 2.3) Block count:** `SchematicInfo.Info.blockCount()` now returns -1 until a single daemon worker thread has counted the (privately loaded) schematic; the Properties window shows an ellipsis meanwhile.
- **(WP 3.1) Assembly arrays:** `core/AssemblyBounds` (pure) measures an assembly: its size is the union of its parts' boxes in its own frame (nested arrays and linked parts included, hidden parts count so the size does not change when one is hidden); `ArrayCells` then gives the cells. `ReconcileCore` repeats every part for each cell at `Transform(aw.apply(offset), aw.rot, aw.mirror)`. **Placement ids of cells are runtime only** (the map keyed `Ids.derived(instanceId:cell:partId)`, the same one linked parts use); the saved `placementIds` of the base cell are untouched. Known limitation: after a restart the copies are created again and a copy's placement that Litematica saved with the world is left behind as an unmanaged placement. `WorldBoxes` takes an `AssemblyBounds.Env` (the session's) and then measures linked assemblies and assemblies with an array anywhere inside; `cellBoxes(id)` gives the per-cell boxes the overlay draws. Realise is wired for objects and assemblies (menu, Properties card, the card's menu); an assembly array realises into a folder of assembly instances, which take an array again. The size passed to `RealiseArray`/`BreakUp` for an assembly is `EditorSession.assemblySize` (the frame size, not the world box, which would include the array and the rotation).
- **(WP 4.1) Link paths:** a link path is relative to the folder of the linking project and may use `..` (`../Depot/project.json`, `../../places/x.lasm`). What the parts' `file` paths of a linked file are relative to (its **base**): the linking project when the file is inside it, else the project that owns the file (`Projects/<name>`), else the file's own folder (`mc/LinkPaths`, mirrored on the port level in `ops/LinkTargets`). Links inside a `.lasm` use that base too, and Make Local rewrites them for the new project. A link to a project is a link to its `project.json` (it parses as a `.lasm`: `AssemblyIo` keeps the other fields in `extra`), so the linked project updates when it is saved (`LasmCache` mtime). The cycle identity is the normalised absolute path; a `project.json` is identified by its folder (`LinkPaths.identity`), and `EditContext.links` is `LinkPaths::linksOf`.
- **(WP 4.1) Save as linked:** `ops/SaveAsLinked` writes `files/assemblies/<name>.lasm` (parts keep their ids), then turns the same node into the linked instance, so selection, id, transform, front and modifiers stay. A part file that is not in the project but found relative to the schematics folder is copied to `files/assemblies/<name>_files/`. "Create linked assembly" on a selection is `CreateAssembly` + `SaveAsLinked` in one transaction. Ctrl G still makes a local assembly.
- **(WP 4.1) Break up / Realise of a linked assembly:** one transaction of `MakeLocal` + `BreakUp` (the files are copied by Make Local, so the objects are independent; `BreakUp` with `copyFiles = true` is only used for a local assembly). Reload clears both `LasmCache`s; Open source opens the project of a project link, otherwise toasts the path.
- **(WP 4.1) Exports:** all go to `schematics/exports/` under a free name and toast the path; the file work runs on a daemon worker (`EditorExports`). `io/ZipExport` plans first (`Plan` of entries: unit-tested path rewriting, one stored copy per source file, nested `.lasm` under `files/assemblies/`, missing files are warnings) and writes second (never replaces a zip). Object export is a plain file copy (TODO M5: the derived/trimmed variant). A project zip is the folder without `.cache`/`.trash`; links to other projects inside it keep pointing at `../Other` and need that project next to it.
- **(WP 4.1) Bake:** `core/BakeParts` (pure) lists every placed copy (arrays, nested and linked assemblies) with its world transform, `io/BakeLayout` (pure) places one sub-region per source sub-region relative to the minimum corner of everything, `mc/Bake` fills a new `LitematicaSchematic` made with `createEmptySchematic`. Blocks move with Litematica's own `PositionUtils.getTransformedBlockPos(pos, mirror, rotation)` and their states with `state.mirror(m).rotate(r)` (the order `placeBlocksToWorld` uses); nothing is written by hand except the loop. Block entities are copied to their moved position (NBT not rotated); entities and scheduled ticks are not baked. Runs on a worker thread with a private `createFromFile` copy of each schematic. Not covered by unit tests (needs the game).
- **(WP 5.1) Masks:** `core/MaskPlan` (pure) plans one project object: trims from owners with a better front whose boxes overlap, plus enabled Booleans; `Occupancy.solid(x,y,z)` in world coordinates comes from `mc/PlacedGrid` (inverse transform to schematic-local) over a privately loaded schematic. The hash is sha-256 over source mtime, own canonical transform and each contributor's kind, mtimes and canonical transforms. Wire objects, hidden nodes, Loose objects and Heavymatica/External nodes never take part.
- **(WP 5.1) DerivedFiles:** planned every 5 ticks on the client thread, a plan has to stay unchanged for 10 ticks and no gizmo drag or open transaction may run; one daemon worker loads, masks (per sub-region, block entities of removed blocks dropped) and writes `<hash>_part.litematic`, then moves it to the final name. `ReconcileCore` asks a `DerivedPort` for the base cell's file, so the swap uses the existing wrong-file path. Only the base cell is derived; array cells keep the original file. Apply builds a plan with only that Boolean (`Filter.onlyBoolean`). Cleanup on project save moves unused files to `.trash/derived`.
- **(WP 5.1) Wire cutters:** `ops/SetWire`; the reconciler hides the placement of a wire object (and shows it again on un-wire only if it hid it).
- **(M6) Per-cell trims:** `ReconcileCore` records a `CellRef` for every visible placed copy (slot, object, base folder, transform, front owner, project); `DerivedFiles` plans each one with `CellMasks` (pure) and keys its files `<objectId>-<hash>`. The hash is now relative to the copy (contributors at transforms relative to its own), so copies surrounded the same way share one file and moving an object together with what trims it does not rebuild anything. At most `CellMasks.MAX_FILES` = 256 distinct files per object per pass; the copies beyond that keep the original and the status bar warns. Covered: base and array cells of project objects, parts of linked assemblies and their array cells, and Loose objects (cache in `config/vitruvius/editor-loose/.cache/derived`, never cleaned; an unnumbered winner never trims a Loose object). Booleans now cut every copy of an object. `DerivedPort.fileFor(UUID slot)`: the slot is the object id for a base cell, else `Ids.derived(id#cell)`.
- **(M6) Top view:** `ui/TopViewMath` (pure, tested) and `view/TopViewWindow`. Not done: box select on empty map (a click there moves the camera target as asked) and snapping of the drag (whole blocks only).
- **(M6) Object types:** `api/SceneObjectType`, `OverlayDrawer`, `PropertyUi` (no ImGui types), `SceneObjectView`, `ObjectTypes` (registry + views, pure). `ExternalNode` gained an array-only `mods` list (saved as `"mods"`); `ModifierCommand` refuses a Boolean on it. Unregistered types: grey node, `MOD` badge, data kept. 1.x list entries show under Loose as read-only rows (`SceneWindow.drawLegacyRows`). Details for mod authors: `API-v2.md`.
- **(M6) Syncmatica:** `editor/sync`: `SyncManifest` (name `[LA2] <project>`, description `la2:` + JSON, or `la2z:` + gzip/base64 above 30 000 characters, refused above 60 000 because NBT strings stop at 65 535 bytes), `SyncPlan` (diff of wanted cells against `extra.syncmatica`, pending count), `SyncmaticaBridge` (reflection only, written against the 0.3.20 jar for 26.1.x: `LitematicManager.getActiveContext`, `syncmaticFromSchematic`, `new ShareLitematicExchange(placement, server, context, serverPlacement)` + `startExchange`, `REMOVE_SYNCMATIC` packet, `SyncmaticManager.getAll`, `CommunicationManager.download` + `IFileStorage.getLocalLitematic`), `ManifestFile` (the 1x1x1 air litematic), `SyncService` (push and receive state machines on the client tick). Cell key = the copy's slot id; the manifest maps slot to Syncmatica placement id. A Litematica placement Syncmatica already tracks cannot be shared again (it hands back the old id), so a changed cell's placement is dropped once and recreated by the reconciler (same hash id) before it is shared. Receive copies the downloaded base file of every object the manifest lists into a new project; linked assemblies (their `.lasm` is not shared) and parts of them are not adopted. Never driven against a real server in this milestone: see `testing/M6.md`.
