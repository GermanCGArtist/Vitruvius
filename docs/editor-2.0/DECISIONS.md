# Litematica Array 2.0: Blender-style editor, decisions

Settled with the user on 2026-11-15. The full architecture (EDITOR-2.0.md) builds on these. Mockup: `mockup.html` (published as an artifact).

## Scope and home
- Lives in Litematica Array as **2.0**. The mod id stays `schemarray`, so Heavymatica keeps working.
- Litematica stays the backend (storage, rendering, printing, material list). The editor is its own layer: its own windows and its own data model, touching Litematica through one thin adapter.
- Built on **26.1.1** first, then ported to 26.1.2 → 26.2 → 26.3. One 2.0 release per branch at the end.

## UI
- **Dear ImGui** (imgui-java), the same look as Manhattan (UiTheme tokens: Inter, JetBrains Mono, Barlow Condensed).
- Natives for **all platforms** bundled. **One shared ImGui host:** whichever mod loads first owns the context, and the other one only registers its panels (Manhattan gets refactored to do this).
- **F6** toggles editor mode. The game fills the screen; the windows float and can be docked: Scene graph, Properties, Top view, toolbar, status bar, plus a right-click menu.
- **Navigation:** middle mouse orbit, Shift+middle mouse pan, wheel zoom, numpad . / F frame. Hold the right mouse button to fly with WASD+QE (Shift faster, wheel changes speed). A right click without dragging opens the context menu. In spectator mode the real player moves, so chunks load.
- **Top view:** a second viewport as a 2D map window (not a second 3D render).

## Editing
- **Gizmo:** move along X/Y/Z, and rotate around Y only in **90° steps** (Litematica limit); mirror X. While dragging only the outline is drawn; the real placement moves on release.
- **Essential Blender features:**
  - undo/redo (covering the file operations the editor did itself)
  - typed G/R + X/Y/Z + number
  - multi-select with box select and a shared pivot
  - snapping to other objects' faces
  - Shift+D duplicate and Alt+D linked duplicate
  - H, Alt+H and / (local view)
- **Selection:** click picks the nearest object, Alt+click cycles through overlapping objects, Shift+click adds to the selection.
- **Join was dropped:** an assembly covers it.
- **Boolean modifier** (Difference/Intersect) is essential. **Apply** writes a separate `.litematic` that can be exported.
- **Front order (priority):** a per-object number, separate from tree order. Overlaps are trimmed from the object behind. The right-click menu has front/forward/backward/back; overlaps are shown in red.
- **Live Booleans and trims use derived files:** generated `.litematic` copies in the project's `.cache/`, regenerated off-thread when the gizmo is released. Litematica places the derived file, so printers, the material list, the verifier and Syncmatica all see the trimmed result. No live-masking mixins.
- **Arrays:** an array stack (1.6 modifiers) on any object or assembly instance. **Realise** turns the copies into separate objects in a `<name> (realised)` subfolder that replaces the array; realised objects can be arrayed again.

## Projects and assemblies
- **A project is like a .blend file:** the folder `schematics/Projects/<name>/` holds
  - `project.json` (scene graph, world/dim, origin),
  - `files/` (its own schematics and assemblies, in subfolders),
  - `.cache/` (derived files).
- **Several projects can be open and edited at once.** One is the active project, where new objects go. Front order also works across projects.
- Placements outside any project show under **"Loose"**, unchanged. 1.x folders and arrays migrate there. "Convert to project" turns a folder into a project.
- **Assembly file:** `.lasm` (JSON). Children are objects (pointing at `.litematic` files) and sub-assemblies, each with a local transform, a priority, Booleans and arrays.
- **Link (default) and Append.** A linked instance is read-only and updates when the source is saved; Make Local turns it into a copy. **Cycles are blocked.**
- **A project can be linked into another project as an assembly**, like Append/Link in Blender.
- **Break up in place:** the instance becomes a folder of objects. **Realise:** the same, with independent file copies.
- **Exports:**
  - assembly → self-contained `.zip`
  - folder → `.zip`
  - project → `.zip`
  - "Bake to one .litematic" for people without the mod

## Compatibility
- **Syncmatica (2.0):** "Push changes" shares every visible object as a flat placement (derived files where trimmed), plus one manifest placement: a 1-air-block `.litematic` whose description holds the project's scene graph, so peers with 2.0 rebuild the structure. Uploads happen only on push, not on every drag.
- **Server companion mod:** for **2.1**. A Fabric server mod with its own channel, files sent once by content hash, edit locks, partial updates, and server-side project storage.
- **Heavymatica:** areas become **full scene objects** through a new object-type API (transform, bounds, outline render, save/load into project and `.lasm`). They live in projects and assemblies and can be moved, arrayed and undone. They never affect schematic blocks, Booleans or the front order.

## Milestones (dev branch `2.0-dev` off `26.1.1`, test jar after each)
- **M0** ImGui host (all platforms, shared with Manhattan), F6 mode, camera
- **M1** Object/project model, multiple projects, Loose + migration, picking, Scene + Properties windows, right-click menu, undo/redo
- **M2** Gizmo, typed transforms, snapping, multi-select pivot, duplicate/linked duplicate, hide/local view
- **M3** Arrays on any object, Realise
- **M4** Assemblies: .lasm, link/append/make local, break up, realise, nesting, cycle check, project-as-assembly, exports
- **M5** Derived files, front order, Boolean, Apply
- **M6** Top view, Syncmatica push + manifest, Heavymatica object API (+ Heavymatica update)
- **M7** Ports to 26.1.2/26.2/26.3, releases

## Roles
- **Claude (Opus):** architect. Writes EDITOR-2.0.md and the work-package briefs, reviews commits.
- **A separate Opus agent:** the visual design, starting from `mockup.html`. Gemini via the Antigravity CLI is optional.
- **Sonnet or Gemini agents:** implementation. Haiku handles small, fully specified pieces.
