# File formats and config files

Everything the mod stores is plain JSON (or a Java `.properties` file) and can be read, diffed and backed up. Writes go
through a temporary file and an atomic move, so a crash never leaves half a file. Fields that this version does not know
are kept and written back unchanged, so files from a newer 2.x survive being opened by an older one.

## Where things live

| Path | Content |
|---|---|
| `schematics/Projects/<name>/` | One 2.0 project (see below). |
| `schematics/exports/` | Output of the editor's exports (`.zip`, baked `.litematic`). |
| `schematics/Shapes/*.litematic` | Shapes made by the Shape generator (the folder can be changed in its window). |
| `config/vitruvius/folders.json` | Placement-list folders (global, all worlds). |
| `config/vitruvius/shapes.json` | The Shape generator's last settings and save folder. |
| `config/vitruvius/editor-session.json` | Per world: the *migrated* flag, the open project folders and the active one. |
| `config/vitruvius/editor-loose/<world>.lasm` | The **Loose** node of a world, after migration. |
| `config/vitruvius/editor-loose/.cache/derived/` | Trimmed files for Loose objects (never cleaned up automatically). |
| `config/vitruvius/editor-ui.properties` | Editor UI state: snap, pivot mode, hidden hints. Delete it to reset. |

## Project folder

```
schematics/Projects/<name>/
  project.json                 the scene graph
  files/                       the project's own files, in any subfolders
    assemblies/<name>.lasm     assemblies saved as linked files
    assemblies/<name>_files/   files a .lasm needed that were outside the project
  .cache/derived/              generated trimmed files: <objectId>-<hash>.litematic
  .trash/                      files the editor removed so that Undo can bring them back
```

`.cache` can be deleted at any time; it is rebuilt. Saving moves derived files that nothing references any more to
`.trash/derived`. Nothing in `.trash` is deleted by the mod.

Project names are checked against characters that are not allowed in file names and against reserved Windows names
(`CON`, `NUL`, `COM1` and so on).

## `project.json`

```json
{
  "format": 1,
  "id": "6f1c0d52-…",
  "name": "Castle",
  "world": "My World",
  "dim": "minecraft:overworld",
  "origin": [0, 64, 0],
  "sync": "local",
  "hidden": false,
  "nodes": [ /* nodes, see below */ ]
}
```

| Field | Meaning |
|---|---|
| `format` | File format version. This version writes and reads `1`. A higher number is refused with a clear message. |
| `id` | The project's UUID. |
| `world`, `dim` | The world the project was made in, and its dimension. |
| `origin` | The anchor, in blocks, where new objects are placed. |
| `sync` | `local` or `shared` (shared on Syncmatica). |
| `hidden` | Whether the whole project is hidden. |
| `nodes` | The child nodes. |

Run-time state such as the project's path and whether it is the active one is **not** saved.

### Nodes

Every node has an `id` (UUID). The `type` selects the rest.

**`folder`**: `name`, `hidden`, `open`, `children`.

**`object`** (a schematic):

```json
{
  "id": "…", "type": "object", "name": "Tower", "hidden": false,
  "file": "files/tower.litematic",
  "transform": { "pos": [10, 64, -3], "rot": 1, "mirror": "none" },
  "front": 2,
  "wire": false,
  "mods": [ /* modifiers */ ],
  "placementIds": { "base": "<litematica placement id>", "1,0,0": "…" }
}
```

| Field | Meaning |
|---|---|
| `file` | Path of the schematic, relative to the project folder. |
| `transform.pos` | Position of the placement origin in world blocks. |
| `transform.rot` | Rotation in 90° steps, `0` to `3`. |
| `transform.mirror` | `none`, `left_right` or `front_back` (Litematica's mirror names). |
| `front` | Front-order number. `1` is frontmost; `0` means unnumbered and sorts behind everything. |
| `wire` | `true` for a wire cutter: drawn as an outline, never placed. |
| `placementIds` | The Litematica placement for each cell. `base` is the object itself; array cells use `"i,j,k"` keys. These ids are only a cache and are never used as node ids. |

**`assembly`**:

```json
{
  "id": "…", "type": "assembly", "name": "Gate", "hidden": false,
  "transform": { "pos": [0, 0, 0], "rot": 0, "mirror": "none" },
  "front": 0,
  "mods": [],
  "source": { "type": "local" },
  "children": [ /* nodes */ ]
}
```

With `"source": { "type": "link", "path": "files/assemblies/gate.lasm" }` the assembly is **linked**: it has no
`children` in this file, because the parts are read from the linked file. `path` is relative to the folder of the linking
project and may use `..`.

**`external`** (an object of another mod, see [API v2](../editor-2.0/API-v2.md)):
`name`, `hidden`, `typeId` (for example `heavymatica:area`), `transform`, `data` (opaque JSON owned by that mod) and an
optional `mods` list that holds arrays only. If the mod that owns `typeId` is not installed, the node still loads, keeps
its `data` untouched, and is shown as a grey **MOD** node.

### Modifiers

```json
{ "type": "array", "enabled": true, "count": 5,
  "useRelative": true, "relative": [1.0, 0.0, 0.0],
  "useConstant": true, "constant": [0, -1, 0] }
```

```json
{ "type": "boolean", "enabled": true, "op": "difference", "target": "<node id>" }
```

`op` is `difference` or `intersect`. Modifiers apply in list order.

## `.lasm` assembly files

A `.lasm` is a JSON file with the **same shape as a project's node list**, without `world` or `dim`. A project's
`project.json` also parses as a `.lasm`, which is how a whole project can be linked into another one. The other fields of
such a file are kept and ignored.

When parts of a linked file are resolved, their `file` paths are relative to a *base*:

1. the linking project, if the linked file is inside it;
2. else the project that owns the linked file (`Projects/<name>`);
3. else the folder of the file itself.

The same linked file used twice gives each part an instance-scoped id, so ids never clash.

## Derived files

Files in `.cache/derived/` are named `<objectId>-<hash>.litematic`. The hash covers the source file's modification time,
the object's own transform and every contributor's kind, modification time and transform, *relative to the copy*. Copies
that are surrounded the same way therefore share one file, and moving an object together with the thing that trims it
rebuilds nothing.

## Syncmatica manifest

The manifest is a 1×1×1 air schematic named `[LA2] <project>`. Its description holds the scene graph as `la2:` followed by
JSON, or `la2z:` followed by gzip and base64 when the JSON is longer than 30,000 characters. Above 60,000 characters it is
refused, because NBT strings stop at 65,535 bytes.

## First start in a world

On the first start in a world, the placements and their `folders.json` folders are converted into Loose objects and
written to `editor-loose/<world>.lasm`. The editor never writes `folders.json` afterwards. The 1.x `arrays.json` is not
read: the copies of a 1.x array come in as separate objects.
