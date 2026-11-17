# API for other mods

The package `dev.schemarray.api` (mod id `schemarray`) lets another mod extend the mod. `SchematicArrayApi.API_VERSION`
is `2`. Call everything from the **client thread**.

| API | Version | For |
|---|---|---|
| [List entries](#list-entries) | 1 | Rows of your own in Litematica's placement list. |
| [Shape actions](#shape-actions) | 1 | A button in the Shape Generator. |
| [Folder actions](#folder-actions) | 1 | An action on folders (`registerFolderAction`, `FolderActionContext`). |
| [Scene object types](../editor-2.0/API-v2.md) | 2 | Full objects in the 2.0 editor (areas, markers). |

API 1 is unchanged in 2.0. API 2 only adds to it. Your 1.x list entries keep working; in the editor they show under **Loose**
as read-only rows.

## Depending on it

Compile against the mod's jar and declare a dependency on the mod id `schemarray` in your `fabric.mod.json`. Check at
runtime that it is there before you touch the API:

```java
if (FabricLoader.getInstance().isModLoaded("schemarray")) {
    SchematicArrayApi.registerListEntries(new MyProvider());
}
```

If you need a feature of API 2, compare `SchematicArrayApi.API_VERSION` with `2` first.

## List entries

`SchematicArrayApi.registerListEntries(ListEntryProvider)`: your `ListEntry` rows appear in Litematica's placement list,
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

Folder data for entries is stored under `id()` in `config/schemarray/folders.json`, next to the placements'. Read it with:

| Call | Returns |
|---|---|
| `folderOf(id)` | The folder the entry is in. |
| `colorOf(id)` | ARGB color; `0` means none. |
| `selectedEntryIds()` | The currently selected entry ids. |
| `refreshList()` | Rebuilds the list (call it after your entries changed). |

## Shape actions

`SchematicArrayApi.registerShapeAction(label, hover, action)` adds a button to the Shape Generator screen. The action
receives a `ShapeActionContext(voxels, suggestedName, suggestedOrigin)`: the generated `VoxelResult`, the text of the name
field and the origin (`BlockPos`) of the preview.

## Folder actions

`SchematicArrayApi.registerFolderAction(buttonLabel, hoverText, action)` adds a button to every folder row in the
placement list. The action receives a `FolderActionContext(folderId, folderName, entryIds)`: the folder and the ids of all
entries inside it.

## Scene object types

For things that have a position and a size (areas, markers) and should be full citizens of the editor, see
[API v2](../editor-2.0/API-v2.md): register a `SceneObjectType` and read your objects back with
`SchematicArrayApi.objectsOfType(typeId)`.

## Rules

- Do not call the API from other threads.
- Do not keep `Transform`s across ticks; ask again.
- A type id or entry id is forever. Renaming it orphans what was saved under it.
