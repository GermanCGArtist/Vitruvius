# API for other mods

The package `dev.vitruvius.api` (mod id `vitruvius`) lets another mod extend the mod. `VitruviusApi.API_VERSION`
is `2`. Call everything from the **client thread**.

| API | Version | For |
|---|---|---|
| [List entries](#list-entries) | 1 | Rows of your own in Litematica's placement list. |
| [Shape outputs](#shape-outputs) | 2 | Another output in the editor's Shape generator (with a live preview). |
| [Folder actions](#folder-actions) | 1 | An action on folders (`registerFolderAction`, `FolderActionContext`). |
| [Scene object types](../editor-2.0/API-v2.md) | 2 | Full objects in the 2.0 editor (areas, markers). |

API 1 is unchanged in 2.0. API 2 only adds to it. Your 1.x list entries keep working; in the editor they show under **Loose**
as read-only rows.

## Depending on it

Compile against the mod's jar and declare a dependency on the mod id `vitruvius` in your `fabric.mod.json`. Check at
runtime that it is there before you touch the API:

```java
if (FabricLoader.getInstance().isModLoaded("vitruvius")) {
    VitruviusApi.registerListEntries(new MyProvider());
}
```

If you need a feature of API 2, compare `VitruviusApi.API_VERSION` with `2` first.

## List entries

`VitruviusApi.registerListEntries(ListEntryProvider)`: your `ListEntry` rows appear in Litematica's placement list,
mixed with the placements and sorted by name. They can sit in folders, be colored, multi-selected and dragged like
placement rows.

A `ListEntry` has:

| Method | Meaning |
|---|---|
| `id()` | A stable id, for example `"modid:something"`. Folder data is stored under it. |
| `name()` | The name shown and sorted by. |
| `createWidget(...)` | Builds the row widget. The `x` and `width` you receive already include the folder indent. |
| `searchStrings()` | Text matched by the search bar. |
| `isRenderingEnabled()` / `setRenderingEnabled()` | Used when a *hidden* folder switches its contents off. |

Array copies, the configuration screen and Litematica's own row buttons stay placement-only.

Folder data for entries is stored under `id()` in `config/vitruvius/folders.json`, next to the placements'. Read it with:

| Call | Returns |
|---|---|
| `folderOf(id)` | The folder the entry is in. |
| `colorOf(id)` | ARGB color; `0` means none. |
| `selectedEntryIds()` | The currently selected entry ids. |
| `refreshList()` | Rebuilds the list (call it after your entries changed). |

## Shape outputs

`VitruviusApi.registerShapeOutput(output)` adds an entry to the **Output** drop-down of the editor's
[Shape generator](../user-guide/shape-generator.md). A `ShapeOutput` has a `label()`, the `options()` of the drop-down next
to it, a `preview(ctx, option)` that the window calls whenever the shape, its position or the option changes (`ctx` null
removes your preview), and a `create(ctx, option, ...)` for the create button. Override `usesListFolders()` or
`usesSchematicFolder()` to get the window's folder pickers. `ctx` is a `ShapeActionContext(voxels, suggestedName,
suggestedOrigin)`: the generated `VoxelResult`, the name field and the origin (`BlockPos`).

`registerShapeAction` from API 1 still compiles and loads, but its buttons are not shown: the Shape Generator screen
it added them to is gone. Use a shape output instead.

## Folder actions

`VitruviusApi.registerFolderAction(buttonLabel, hoverText, action)` adds a button to every folder row in the
placement list. The action receives a `FolderActionContext(folderId, folderName, entryIds)`: the folder and the ids of all
entries inside it.

## Scene object types

For things that have a position and a size (areas, markers) and should be full citizens of the editor, see
[API v2](../editor-2.0/API-v2.md): register a `SceneObjectType` and read your objects back with
`VitruviusApi.objectsOfType(typeId)`.

## Rules

- Do not call the API from other threads.
- Do not keep `Transform`s across ticks; ask again.
- A type id or entry id is forever. Renaming it orphans what was saved under it.
