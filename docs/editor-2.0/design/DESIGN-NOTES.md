# Design notes (v2)

Companion to `DESIGN.md` (the spec) and `mockup-v2.html` (the refined mockup). Short on purpose.

## What changed from `mockup.html`, and why

**Structure kept:** floating windows (Scene, Properties, Top view), top toolbar, bottom status bar, right-click menu,
gizmo, typed-transform/fly HUD, the example scene.

| Change | Why |
|---|---|
| Window title bars: no icons, no fake "⋮⋮" grip, real close × + toolbar **Windows** menu, **Tab** hides all | ImGui title bars hold text and a close button; closing must be reversible |
| Context menu rebuilt with **submenus** (Add ▸, Transform ▸, Modifiers ▸, Front order (P2) ▸, Assembly ▸, Move to ▸, Export ▸), keyboard navigation, per-context menus (empty, object, several, linked/local assembly, locked part, folder, project, Loose) | the flat 18-item menu was ~520 px tall; disabled vs hidden rule keeps menus short |
| **Badges only with information**: P# only when the object overlaps; Array + Boolean merged into one chip; sync dot only when shared; lock replaces the eye inside linked assemblies | rows were losing their names to badges |
| **Issues** filter chip (only when there are any) = flat list of overlapping objects across projects, sorted by front order | fixing priorities happens in one place |
| **Inline rename** (F2, double-click name), double-click type icon frames | Blender outliner convention |
| **Drag-and-drop** in the tree: before/after line, into highlight, invalid reason in red, auto-expand, auto-scroll; moving into another project says the file is copied | reparenting was missing |
| **Box select** (B, or drag on empty space; Shift add, Ctrl subtract) | decisions require it |
| **Click vs drag**: 5 px threshold; RMB + WASD starts fly even without moving the mouse | holding RMB and pressing W used to open the menu |
| **Ghost while dragging**: the placement stays, a dashed ghost moves, tether line, snap face highlighted with label | the decision "placement moves on release" was not visible in v1 (it moved the real box) |
| **XZ plane handle**, axis halos, constraint line, other axes hidden while dragging, foreshortened axes fade, disabled (locked) gizmo | from Gemini; readability on terrain |
| Rotate ring: no centre text banner; ↻/↺ glyph on hover; **drag** rotates in 90° steps with a sector fill | the banner covered the object |
| **Overlap labels only when hovered/selected**; volumes always, with a hatched top face | label pile-ups; hatch is the colour-blind cue |
| Properties: Location as one row per axis with **− / +** steppers (Shift = 16, hold repeats, one undo step), scrub on the axis tag; Rotation segmented; single "Mirror X" toggle (no dead Z button) | from Gemini; nudging by a block is the common case |
| **Multi-selection batch view** (pivot location, shared steppers, ↺/↻, front order, create assembly, list of the selection) | v1 edited only `sel[0]` while claiming to move the group |
| **Locked part view** (banner + read-only fields) instead of a toast | dead-end toasts |
| Parts of **local** assemblies selectable from the tree (assembly row gets an owner tint) | otherwise local assemblies can't be edited without breaking them up |
| Modifier cards: grip reorder, collapse with summary, **enable switch**, ⋯ menu (Apply/Realise, move, duplicate, remove), Array Relative/Blocks mode with the resulting block offset, Boolean eyedropper and live status ("cutter doesn't touch") | decisions require reorder/enable/apply; offsets must be whole blocks |
| "**From Litematica?**" card in the empty Properties state; Loose explains what converting unlocks; **?** quick guide | from Gemini; onboarding for Litematica users |
| Toolbar: **+ Add**, Box button, Windows, ?, undo tooltips name the step, labels → key caps → overflow "⋯" menu at narrow widths (never wraps) | discoverability without covering more of the game |
| Status bar: hints per mode (idle, selection, hover axis/plane/ring, drag, typed, fly, box, rename, tree drag), selection with size and position, Syncmatica only when something is shared | v1 showed the same 9 hints always |
| Toasts: levels, optional **Undo** action, pause on hover | delete/realise/apply should be undoable from where you look |
| Map: hatched overlaps, dashed areas, dotted cutters, legend fixed, fit button, scale text, double-click moves the camera | legend didn't match drawing |
| Linked assemblies use their own colour `LINK #7fc8f0` instead of a second violet | v1's `#d6a8ff` was too close to Boolean `#b48cf0` |

Not built in the mockup (spec only): Top-view drag-to-move and box select, Ctrl-click range in the tree works but
Shift-range does not exist (by design), crosshair cursor colours, enter/exit animation, failed-rebuild states.

## Gemini proposal (`gemini/PROPOSAL.md`): taken and rejected

**Taken**
- Context menu with submenus, including "Front order (P2) ▸" showing the current number, and Mirror Z as "X + 180°" in Transform ▸.
- **XZ ground-plane handle** on the move gizmo (spec 11.8), dark halo behind gizmo arms, no text in the ring, cardinal ticks, hover glyphs.
- **− / +** steppers with button repeat and Shift = 16 (a chunk). I put each axis on its own row so steppers fit at 260 px.
- **Multi-select batch inspector** with relative nudge.
- **"From Litematica?"** translation card, Loose "Convert to project" call to action, **?** quick guide.
- **Box select (B)** in the toolbar; **+ Add** button in the toolbar.
- Combined modifier chip in the tree; an overlap-focused view (as the "Issues" chip, flat and sorted by P).
- Overlap labels only when involved objects are hovered/selected.
- HUD axis chips (clickable), fly speed bar and "loading chunks", drag → typed handoff (typing digits during a gizmo drag).
- Modifier card collapse/enable/reorder, Relative/Blocks array mode with totals, Boolean eyedropper.
- Opaque panels when docked (no blur), contextual 3-part status bar with selection bounds.

**Rejected or changed**
- **Fixed 3-column `BeginTable` rows with a 40 px P# rail**: reserves width even when empty; a `selectable` row with
  draw-list badges is simpler and works with drag-and-drop. Badges appear only when meaningful instead.
- **Custom splitter "Inspector rail"**: ImGui docking already gives split nodes, tabs and resizable separators; a
  hand-made splitter would duplicate it. Docking + Windows menu + Tab covers "focus mode".
- **Toolbar wrapping into two rows**: covers more of the game; I use labels → key caps → overflow menu.
- **Clicking a part of a linked assembly selects the assembly**: I select the part read-only (Properties shows a
  banner with "Select assembly" / "Make assembly local"), so you can still inspect its file and position.
- **WindowRounding 0 everywhere**: kept 8 for floating windows (they float over the game); docked windows follow
  ImGui (square).
- **Barlow numerals in the HUD**: kept JetBrains Mono so digits line up with the field values.
- **Hover "+" on project/folder rows**: right-click › Add and the toolbar Add already cover it; hover-only controls shift
  the row layout.
- "Snap to nearest face" as a menu command: snapping is a drag behaviour here; a one-shot command needs a target
  choice we haven't designed.

## cg-uihost

The spec references `dev.cg.uihost` (`UiHost.register`, `UiHost.theme()`, `UiHost.scale()`, `Theme` tokens and
fonts, `UiLayer.wantsKeyboard/wantsMouse`). It needs small host changes: **DockingEnable + ConfigDockingWithShift**,
a way for a layer to set a GLFW cursor (crosshair) and capture the cursor for orbit/fly, and two fonts (`monoS`,
optional `uiBold`). Manhattan's `Widgets`, `Icons`, `MapView`, `Overlay` toasts aren't in the host yet; I'd move
the generic ones (`label`, `button`, `fit`, `wrapped`, `bar`, `Icons.draw`, the toast drawer) into cg-uihost so both
mods share them.

## Open questions for the user

1. **Gizmo and labels drawn by ImGui?** The brief says gizmo and name tags are world-rendered. I recommend drawing the
   gizmo, ring, tags, snap label and box rectangle on ImGui's background draw list with projected points (crisp
   Inter text, exact px, same hit-test space as the mouse), and keeping only depth-tested boxes in Minecraft
   rendering. OK?
2. **Editing parts of local assemblies**: the spec allows it from the tree. Should double-click on an assembly in the
   world also "enter" it (Figma-style), or is the tree enough?
3. **Esc with nothing to cancel** opens the pause menu (editor stays on behind it). Alternative: Esc does nothing and
   only F6 leaves. Preference?
4. **F2** is vanilla's screenshot key; in editor mode it renames. Fine, or use Ctrl+R / double-click only?
5. **Tree multi-select keys**: Shift+click toggles (same as the viewport), Ctrl+click selects a range. Most file
   trees use the opposite. Keep Blender-viewport consistency?
6. **Moving an object into another project copies its file** into that project's `files/`. Should it move the file
   instead when nothing else uses it?
7. **"From Litematica?" card**: always in the empty Properties state until hidden, or only for the first sessions?
8. Bundle **Inter SemiBold** (≈300 KB) for titles and project rows, or stay with one weight?

## Where the decisions are hard to show in the UI

- **Front order vs tree order**: two orderings of the same objects confuse people. The UI hides P# unless an overlap
  exists and offers the Issues view, but users may still drag rows expecting it to change priority. A tooltip on
  the badge and the status hint during tree drags say so; it may still need a one-time toast.
- **"Placement moves on release" + derived files rebuilding off-thread**: there is a visible gap between release and
  the real blocks appearing. The spec keeps a pulsing ghost until Litematica shows the moved placement; if rebuilds
  are slow on big schematics this will still feel laggy.
- **Linked vs local assemblies vs linked duplicates**: three "link" ideas share one colour and icon. Linked duplicate
  shares a file; linked assembly shares a `.lasm`. The UI shows the source path to tell them apart, which is subtle.
- **Booleans on an assembly instance vs Booleans inside the `.lasm`**: Properties only shows the instance's stack;
  the inner ones are visible only by opening the source. Users may not find them.
- **Cutter objects** are ordinary objects that become wires because another object references them. Removing the
  Boolean silently turns the cutter back into a printed object; the spec doesn't warn yet (suggest a toast with Undo).
- **Syncmatica manifest placement** (1-air-block `.litematic`) has no UI; peers without 2.0 will see a stray tiny
  placement. Worth a line in the Push tooltip.
- **Heavymatica areas never affect blocks**, but they can be arrayed and sit in assemblies; the amber note in
  Properties is the only reminder.

## Resolved (user, 2026-11-15)
mockup-v2 is the design. The open questions are settled as:
1. The gizmo, ring, name tags, snap label and box-select rectangle are drawn with ImGui's background draw list (projected points). Only the depth-tested boxes and outlines use Minecraft rendering.
2. There's no "enter assembly" in 2.0. Parts are selected from the tree.
3. Esc with nothing left to cancel does nothing. Only F6 leaves editor mode.
4. F2 renames in editor mode, since vanilla keys are blocked there.
5. In the tree, Shift+click toggles (the same as in the viewport) and Ctrl+click selects a range.
6. Moving an object to another project moves its file when nothing else references it, and copies it otherwise.
7. The "From Litematica?" card shows until the user hides it.
8. Inter SemiBold is bundled in cg-uihost.
