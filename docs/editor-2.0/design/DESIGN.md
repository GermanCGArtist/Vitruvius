# Litematica Array 2.0 editor: design spec

Status: design for M0–M6. Product decisions are in `../DECISIONS.md` and are not reopened here.
Reference mockup: `mockup-v2.html` (this folder). Where the mockup and this file disagree, **this file wins**.

All sizes are **px at UI scale 1.0**. Multiply every number by `s = UiHost.scale()` (the shared `cg-uihost`
scale), exactly as Manhattan does (`14f * s`). The mockup is drawn at about 0.87× of these sizes (13 px body text
instead of 15 px) so that it fits a browser window; read proportions from it, numbers from here.

Contents
1. Principles
2. Tokens (colour, type)
3. Metrics
4. Layout and windows (shared rules)
5. Toolbar
6. Status bar and HUD
7. Scene graph window
8. Properties window
9. Top view window
10. Right-click menus
11. World-space visuals
12. Interaction details (mode switch, mouse, keyboard, focus, toasts)
13. Icons
14. Accessibility and readability
15. Implementation map (what exists in cg-uihost / Manhattan, what is new, what needs custom draw code)

---

## 1. Principles

- **The game is the canvas.** Windows float over it, are translucent only where it helps (96 % panel alpha), and
  can be closed. Nothing permanent covers the centre of the screen except the toolbar (top centre) and the status bar
  (bottom edge).
- **Calm by default, loud only for problems.** Neutral greys for structure, teal accent for selection and the
  thing you are doing, red only for overlaps/trims/errors, amber only for Heavymatica areas and pending work.
- **Badges only when they say something.** No "P5" on every row; a front-order badge appears only when the object
  actually overlaps another one. A row with nothing special has no badges.
- **One colour, one meaning, plus a second cue.** Every colour that carries meaning also has a letter, an icon, a
  line pattern or text (section 14).
- **Blender muscle memory, Minecraft limits.** Keys follow Blender where the decisions say so; rotation is Y only in
  90° steps, mirror is X, moves are whole blocks. The UI never offers a value it can't apply (no 45°, no 0.5 blocks).

---

## 2. Tokens

### 2.1 Reused from `dev.cg.uihost.Theme` (a copy of Manhattan's `UiTheme`, same values, unchanged)

| Token | Hex | Use in the editor |
|---|---|---|
| `BG` | `#131518` | behind the toolbar/status bar when opaque |
| `PANEL` | `#191c20` | window background (alpha 0.96) |
| `PANEL2` | `#1e2126` | inputs, cards, popups, title bars |
| `LINE` | `#2a2e35` | window/card/popup borders |
| `LINE2` | `#22262b` | inner dividers (section rules, header/body split) |
| `FG` | `#e2e5e9` | primary text |
| `DIM` | `#858c96` | secondary text, inactive icons |
| `GUTTER` | `#4f565f` | twisties, disabled icons, decorations (never text you must read) |
| `ACCENT` | `#5ec4b6` | selection, active tool, primary button, focus ring |
| `ON_ACCENT` | `#06221e` | text on accent fills |
| `OK` | `#8ad27e` | "up to date" dots, Y axis |
| `WARN` | `#f2b35e` | pending pushes, Heavymatica areas |
| `ERR` | `#f07470` | overlaps, trims, failures, Delete |
| `BTN` | `#23272d` | button and frame fill |
| `CHIP` | `#262a31` | badge/kbd fill, hover fill |
| `TRACK` | `#2a2f36` | scrollbar grab |
| `SLOT` | `#1c1f24` | Top view map background (as `MapView`) |
| `SEL` | `#21403b` | selected row fill |
| `0x7fc8f0` | `#7fc8f0` | already used by Manhattan for Baritone log text; promoted to the token `LINK` below |

### 2.2 New tokens (add to `UiTheme` or an `EditorTheme` next to it)

| Token | Hex | Meaning | Second cue |
|---|---|---|---|
| `AX_X` | `#f0605e` | X axis (gizmo, X field tag, map grid line x=0) | letter **X** |
| `AX_Y` | `#8ad27e` (= `OK`) | Y axis, Y ring | letter **Y** |
| `AX_Z` | `#5b9cf0` | Z axis | letter **Z** |
| `OVERLAP` | `#f07470` (= `ERR`) | front-order overlap volume, trimmed-block counts | hatch pattern and "−n" text |
| `BOOL` | `#b48cf0` | Boolean modifier, cutter objects | dotted wire, ∩ icon |
| `AREA` | `#f2b35e` (= `WARN`) | Heavymatica areas | long-dash outline, dashed-square icon |
| `LINK` | `#7fc8f0` | linked assemblies (link badge, tree icon, Properties source line) | chain icon |
| `T_FOLDER` | `#c9b27a` | folder type icon | folder shape |
| `T_OBJECT` | `#9fb4c9` | schematic/object type icon, local assembly icon | cube shape |
| `DIM_ON_SEL` | `#a8afb8` | secondary text/icons on a `SEL` row (DIM on SEL is only 3.3:1) | — |
| `HOVER_LINE` | `#e2e5e9` @ 45 % | hover outline in the world | thinner than selection |
| `SHADE` | `#000000` | gizmo halo, tag/HUD backgrounds (`0x14161a` @ 0.82 like Manhattan's `Overlay` pill) | — |

Alpha variants are written `TOKEN@a` (ImGui: `u32(TOKEN, a)`).

### 2.3 Type

Fonts are the host's atlas (`Theme.loadFonts`, read through `UiHost.theme().ui()` etc.):

| Role | Font | Size | Use |
|---|---|---|---|
| `ui` | Inter | 15 | all body text, rows, menu items, buttons |
| `small` | Inter | 12.5 | section labels (via `Widgets.label`, upper-case, `DIM`), status bar, notes, legend |
| `mono` | JetBrains Mono | 14 | file paths, number fields, HUD |
| `numM` | Barlow Condensed SemiBold | 28 | block counts in Properties |
| **new** `monoS` | JetBrains Mono | 12 | badges, key caps, map labels |
| **new, optional** `uiBold` | Inter SemiBold | 15 | window titles, project rows, primary buttons, toast titles |

ImGui has no synthetic bold or italic. If `uiBold` is not added, use `ui` in `FG` where this spec says bold, and
never rely on italics (the mockup's italic "locked" names are replaced by `DIM` text plus a lock glyph).

---

## 3. Metrics (scale 1.0)

### 3.1 Global style for editor windows (push around each editor `Begin`)

| Style var | Value | Note |
|---|---|---|
| `WindowRounding` | 8 | Manhattan uses 0 for its fixed shell; editor windows float, so push 8 |
| `WindowPadding` | 10, 8 | Manhattan tiles use 14, 12; editor windows are denser |
| `WindowBorderSize` | 1 (`LINE`) | |
| `WindowMinSize` | per window, see 4.3 | |
| `FramePadding` | 9, 5 → frame height **25** | Manhattan's value; inputs, buttons, combos |
| title bar | 28 high | push `FramePadding` 9, 6.5 just for `Begin`, pop right after |
| `ItemSpacing` | 8, 6 | (Manhattan 8, 7) |
| `ItemInnerSpacing` | 6, 5 | Manhattan |
| `IndentSpacing` | 14 | tree only |
| `FrameRounding` | 5 | Manhattan |
| `PopupRounding` | 7 | (Manhattan 6) |
| `PopupPadding` (WindowPadding inside popups) | 4, 4 | |
| `ScrollbarSize` | 10 | Manhattan |
| `GrabRounding` | 4 | Manhattan |

### 3.2 Component sizes

| Component | Size |
|---|---|
| Scene row | 24 high; project/Loose rows 28 high with 4 px gap above |
| Tree indent per level | 14 |
| Twisty hit box / glyph | 16 × 16 / chevron 10 |
| Type icon | 14 (`r = 7`) |
| Badge | 17 high, pad-x 5, rounding 4, gap 4, icon 11, `monoS` text |
| Eye / lock toggle | 20 × 20 hit box, icon 14 |
| Search field | 25 high (frame) |
| Filter chip | 22 high, pad-x 9, rounding 11 (pill), gap 4 |
| Section | top rule 1 px `LINE2`, 9 px above label, 8 px below the last control |
| Field row | label column 80, then controls; XYZ = 3 equal fields, gap 4 |
| Axis tag inside a number field | 18 wide, fill `AX_*@0.13`, letter `monoS` in `AX_*` |
| Button | 25 high (frame), pad-x 10 |
| Modifier card | rounding 6, border `LINE`, fill `PANEL2`, header 30, left stripe 2 px, body pad 8, gap 6 |
| Context menu | min width 240, item 26 high, icon column 14 at x 8, label at x 30, shortcut right-aligned, 8 px right pad |
| Toolbar | 36 high (28 buttons + 4 padding), rounding 8, button pad-x 8, gap 4, separator 1 × 18 |
| Key cap (`kbd`) | 18 high, pad-x 5, rounding 4, `CHIP` fill, `LINE` border, `monoS` `DIM` |
| Status bar | 24 high (Manhattan `statusH`), pad-x 10, group gap 14 |
| HUD pill | 30 high, pad-x 14, fully rounded, `0x14161a@0.82`, border white@0.10 (Manhattan `Overlay` pill) |
| Toast | max width 380, pad 12 × 8, rounding 12, left bar 3 × (h − 18) (Manhattan `Overlay.drawToasts`) |
| Status dot | 7 × 7 circle |

---

## 4. Layout and windows

### 4.1 Docking model

- Enable `ImGuiConfigFlags.DockingEnable` in the shared host with `io.ConfigDockingWithShift = true`, so dragging a
  window moves it freely and **Shift-drag** docks it. This keeps the "floating over the game" default.
- Each frame in editor mode call `DockSpaceOverViewport` with `PassthruCentralNode | NoDockingInCentralNode`. The
  central node stays empty and transparent, so the game shows through and mouse input there goes to the viewport
  (`WantCaptureMouse` is false over it). Windows can be docked to the left/right/bottom edges or tabbed together.
- The toolbar and status bar are **not** dockable (`NoDocking | NoMove | NoResize | NoTitleBar | NoSavedSettings`).
- Layout (positions, sizes, docking, open/closed) persists in the shared host's ini under the `schemarray` prefix.
  A **Reset layout** item in the toolbar's Windows menu restores the defaults below.

### 4.2 Default layout (game window W × H, scale 1.0)

| Window | Position | Size |
|---|---|---|
| Toolbar | centred, y = 10 | content width |
| Scene | x 12, y 56 | 300 × (H − 56 − 24 − 12) |
| Properties | right 12, y 56 | 320 × 0.58 (H − 92) |
| Top view | right 12, under Properties with 8 gap | 320 × rest (≥ 220) |
| Status bar | full width at the bottom | W × 24 |

If `W < 1100`: Properties and Top view start **tabbed into one right window** (Properties tab active).
If `H < 620`: Top view starts closed.

### 4.3 Shared window chrome

- Title bar 28 high, `PANEL2`, title `uiBold` (or `ui` `FG`) at x 10. Right side: ImGui's close button (×), 22 × 22,
  `DIM` → `FG` on hover. No collapse arrow (`NoCollapse`). No icons in title bars (ImGui title bars can't hold them
  without custom drawing; the mockup v1 icons and "⋮⋮" grip are removed).
- Closing a window only hides it. Reopen from the toolbar **Windows** menu (section 5).
- Background: `PANEL` at alpha 0.96 while floating, 1.0 when docked (no blur: ImGui can't, and the game behind a
  docked panel is noise).
- Body: `WindowPadding` 10, 8. Body scrolls vertically; never horizontally.
- Footer (Scene only): 1 px `LINE2` rule, 8 padding, buttons fill the width.
- Minimum sizes: Scene 220 × 160, Properties 260 × 160, Top view 200 × 180.
- Focused window: border `LINE` → `GUTTER`; no other change (calm).

---

## 5. Toolbar

One row, top centre, `PANEL@0.94`, border `LINE`, rounding 8, shadow none (ImGui has no shadow; use a 1 px
`#000@0.35` rect 1 px outside the border if wanted).

Contents, left to right (groups separated by 1 × 18 `LINE` rules):

1. **Mode chip**: "EDITOR" (`small`, upper-case, letter-spacing via 0.5 px char gap if available, else plain) +
   key cap `F6`. Fill `SEL`, text `ACCENT`. Click = leave editor (same as F6). Tooltip "Leave the editor (F6)".
2. **Add** (`Shift A`): plus icon + "Add", opens the same Add menu as 10.1 under the button. Creating things never
   depends on discovering the empty-space right-click.
3. **Tools** (radio): Select `W` (cursor icon), Move `G` (move icon), Rotate `R` (rotate icon), then **Box** `B`
   (dashed-rect icon, momentary: arms box select once, see 11.11). Each: icon 15 +
   label `ui` + key cap. Active: `BTN` fill, `ACCENT` icon and label, 1 px inset `ACCENT@0.33`
   border. Hover: `BTN` fill, `FG`.
4. **Mirror X** (`M`): icon-only button. Disabled (`GUTTER`, no hover) when nothing selected or the selection is locked.
5. **Snap** toggle (magnet + "Snap"); on = active look. **Pivot** combo (Median / Active object / Bounding box),
   25 high, width 128. **Local view** (`/`) icon toggle; on while local view is active.
6. **Undo / Redo** icon buttons. Disabled look (`GUTTER`) when the stack is empty. Tooltip names the step:
   "Undo Move tunnel_segment (Ctrl Z)".
7. **Windows** menu button (layout icon): popup with checkable items Scene, Properties, Top view, "Hide all
   windows  Tab", separator, Reset layout.
8. **Push** (Syncmatica), see 5.1.
9. **?** Quick guide: a popover (one screen) with mouse navigation, the key list of 12.5 and "non-destructive vs
   Apply/Bake/Realise" in three short paragraphs.

Narrow behaviour (toolbar width needed > available W − 24):
- step 1: hide text labels of tools and Snap (icons + key caps remain);
- step 2: hide key caps;
- step 3: move groups 5–7 and 9 into a "⋯" overflow popup at the end.
The toolbar never wraps to a second row (that would cover more of the game).

### 5.1 Syncmatica push indicator

Button: sync icon + "Push" + count pill (`monoS`, 17 high, pill radius).

| State | Look |
|---|---|
| No shared project open | button disabled; tooltip "No project is shared on Syncmatica. Share one from its right-click menu." |
| Up to date | pill `CHIP` / `DIM`, text "0"; tooltip "Everything is pushed" |
| Pending n changes | pill `WARN` fill, text `#2a1a02`; tooltip "n changes in shared projects aren't pushed yet" |
| Pushing | spinner (10 px ring, 2 px, `WARN`, 270° arc rotating 0.7 s/turn) replaces the icon, label "Pushing 3/12" |
| Failed | pill `ERR` fill, text "!"; click opens the reason toast with **Retry** |

The same state appears in the status bar (6.2). Pushing never happens automatically.

---

## 6. Status bar and HUD

### 6.1 Layout

Full width, 24 high, `PANEL2` (opaque, like Manhattan), top rule `LINE`. `small` font, `DIM`.
Left: **hints** for the current mode (6.3), each as key cap(s) + verb, 10 gap. Right: **selection summary** (`FG`),
**derived files** state, **Syncmatica** state, 14 gap. Hints are dropped from the end first when space is short; the
right block never truncates except the selection summary (ellipsis via `Widgets.fit`).

### 6.2 Right-side indicators

- Selection: "Nothing selected" / "tunnel_segment · 8×6×7 at 12, 64, −3 · array ×6" / "3 objects · 40×16×22"
  (bounds of the union).
- Derived files:
  - idle: 7 px `OK` dot + "Derived files up to date";
  - rebuilding: spinner `WARN` + "Rebuilding 2 derived files…";
  - failed: `ERR` dot + "1 derived file failed" + text button "Retry" (`ACCENT`), tooltip with the error.
- Syncmatica: `OK` dot "Syncmatica up to date" / `WARN` dot "3 changes not pushed" / hidden when no project is shared.

### 6.3 Hints per mode

| Mode | Hints |
|---|---|
| Idle, nothing selected | `LMB` select · `drag` box select · `MMB` orbit · `Shift MMB` pan · `Wheel` zoom · `hold RMB` fly · `RMB` menu · `Shift A` add |
| Idle, something selected | `G` move · `R` rotate · `M` mirror · `Shift D` duplicate · `H` hide · `X` delete · `F` frame · `RMB` menu |
| Hover gizmo axis | `drag` move along X · `Ctrl` hold to turn snapping off |
| Hover Y ring | `click` +90° · `Shift click` −90° · `drag` rotate in 90° steps |
| Gizmo drag (move) | `Ctrl` snap off · `RMB`/`Esc` cancel · `release` place |
| Ring drag | `RMB`/`Esc` cancel · `release` apply |
| Typed transform | `0–9` number · `X Y Z` axis · `−` negate · `Backspace` · `Enter` confirm · `Esc` cancel |
| Fly | `W A S D` move · `Q E` down/up · `Shift` fast · `Wheel` speed · `release RMB` stop |
| Box select (armed or dragging) | `drag` select · `Shift` add · `Ctrl` subtract · `Esc` cancel |
| Inline rename | `Enter` rename · `Esc` cancel |
| Tree drag | drop **between** rows to reorder · drop **on** a folder/project/local assembly to move in · `Esc` cancel |
| Local view | (prepend) "Local view" chip · `/` leave |

### 6.4 HUD (typed transform, gizmo drag, fly)

A pill centred under the toolbar (y = toolbar bottom + 10), `mono` 14, never takes input.

- Typed move: `Move` + three axis chips `X` `Y` `Z` (18 × 18, rounding 4; the active one filled `AX_*@0.25` with its
  letter in `AX_*`, the others `CHIP`/`DIM`) + `  +12` (`ACCENT`) + blinking caret (2 × 14 `FG`, 1 s steps) + `blocks`
  + dim hint. Shift+Y (or Shift+X/Z) locks a plane: chips X and Z lit, "XZ". The chips are clickable (the HUD is a
  small `NoDecoration | NoFocusOnAppearing` window while a typed transform runs).
- Typed rotate: `Rotate Y  180°` + "snaps to 90°".
- Gizmo drag: `Move X  +4 blocks` (plane drag: `Move XZ  +4, −2`) + `snapped to vent_shaft_cut` in `ACCENT` when
  snapped. Typing a digit during a gizmo drag hands over to a typed transform on that axis (Blender behaviour); the
  mouse stops moving the object until Enter/Esc.
- Ring drag: `Rotate Y  +90°`.
- Fly: `Fly` + a 60 × 5 speed bar (`Widgets.bar` look, `ACCENT`, log scale 0.2×–8×) + `1.5×`; in spectator mode,
  when chunks around the camera are still loading, append `DIM` "loading chunks…".
Numbers use `font-variant` tabular digits (JetBrains Mono is tabular already).

---

## 7. Scene graph window

### 7.1 Structure (top to bottom)

1. **Search**: `inputTextWithHint("Filter  (Ctrl F)")`, full width, search icon drawn at x 8 inside the frame (pad-left
   26). An × clear button appears at the right when not empty.
2. **Filter chips**: All · Schematics · Assemblies · Areas · **Issues (n)**. "Issues" only appears when n > 0
   (overlaps that trim blocks, failed derived files, missing files). Single choice. **Issues is a flat view**: no
   hierarchy, every object involved in an overlap across all open projects, sorted by front order (P1 first), each
   row with its project name in `DIM` after the name, so priority conflicts can be fixed with PgUp/PgDn in one place. Below width 260 the chips become
   one combo "Show: All ▾".
3. **Tree** (child region, scrolls).
4. **Footer**: New project · Open project…

### 7.2 Row anatomy (left → right)

```
| indent | twisty 16 | type icon 14 | name (flex, ellipsis) | badges… | eye/lock 20 |
         ^gap 2       ^gap 6         ^gap 6                 ^gap 4    ^4 right pad
```

- **Twisty**: chevron, `GUTTER`, rotates 90° when open (no animation in ImGui, just the two glyphs). Empty containers
  keep the 16 px slot blank so names align.
- **Type icon** colours: project `ACCENT`, folder `T_FOLDER`, object `T_OBJECT`, local assembly `T_OBJECT`,
  linked assembly `LINK`, Heavymatica area `AREA`, cutter object `BOOL` (∩ icon instead of cube), Loose `GUTTER`.
- **Name**: `ui` `FG`. Project and Loose rows use `uiBold`.
- **Badges, fixed order** (only the ones that apply):
  1. `ACTIVE` (project only): `ACCENT` fill, `ON_ACCENT` text, `small` upper-case.
  2. sync dot (project only): 7 px, `OK` = shared on Syncmatica, none when local only (no dot, not a grey dot).
  3. link: chain icon in `LINK` (linked assemblies and linked duplicates). Tooltip: source path.
  4–5. modifiers, **one combined chip**: array icon + "×6" in `ACCENT`, then (if any) the ∩ icon in `BOOL`, in the
     same 17 px chip. A disabled modifier's part of the chip is `GUTTER`. Tooltip lists the stack in order.
  6. trim: "−1,240" in `OVERLAP` (blocks this object loses to objects in front).
  7. front order: "P2" in `FG`, **only when the object overlaps another object** (that's when the number matters).
  8. state: spinner while its derived file rebuilds, `ERR` "!" when it failed or the file is missing.
- **Eye**: `GUTTER` when visible (quiet), `DIM` eye-off when hidden; `FG` on hover. Loose has no eye.
  Rows inside a linked assembly show a **lock** glyph (`GUTTER`) in this slot instead; it isn't clickable.

Badge overflow: if the name would get narrower than 56 px, badges 7, 3, 4–5 are dropped in that order and replaced
by one `+n` chip whose tooltip lists them. Trims (6) and state (8) are never dropped.

### 7.3 Row states

| State | Look |
|---|---|
| normal | transparent |
| hover | `#ffffff@0.03` fill, rounding 4 |
| selected (active, the one Properties shows) | `SEL` fill, name `#ffffff`, secondary text/icons `DIM_ON_SEL`, 2 × 14 `ACCENT` bar at x 0 |
| selected (not active) | `SEL@0.6` fill, no bar |
| owner of a selected part | `SEL@0.35` fill (the assembly row when one of its parts is selected) |
| hidden (self or parent) | name and type icon at 45 % alpha, eye-off icon `DIM` |
| locked (part of linked assembly) | name `DIM`, type icon `GUTTER`, lock in the eye slot; selectable for reading, not editable |
| filtered ancestor (shown only to reach a match) | name `DIM` |
| search match | the matching substring drawn in `ACCENT` (custom: draw the name in up to 3 `addText` runs) |
| focused for keyboard (no mouse) | 1 px `ACCENT@0.6` inset outline (ImGui nav highlight) |
| renaming | the name is replaced by an `inputText` (frame height 22, `PANEL2`, border `ACCENT`), text pre-selected |
| drop target, see 7.5 | |

### 7.4 Selection in the tree

- Click: select the row (replaces). For objects inside a **local** assembly, the part itself is selected (its
  assembly row gets the "owner" tint). For parts of a **linked** assembly the row is selected for reading only:
  Properties shows the read-only view (8.9) and the gizmo is disabled.
- Shift+click: add/remove (same as the viewport). Ctrl+click: range from the active row. Arrow keys move, Left/Right
  collapse/expand, **F2** renames, **Enter** frames (like F), Space toggles visibility.
- Double-click on the name: inline rename. Double-click on the type icon: frame it in the viewport.
- Selecting containers (project, folder, Loose) selects the container only, not its content. "Select contents" is in
  the menu.

### 7.5 Drag and drop (reorder and reparent)

Implementation: `beginDragDropSource` on the row (after a 6 px drag), `beginDragDropTarget` on each row, payload
`"LA_NODES"` = the selected ids (dragging an unselected row drags just that row).

- **Preview** (tooltip under the cursor): type icon + name, or "3 objects", `ui`, `PANEL2` popup.
- **Drop zones** in each row: top 25 % = before, bottom 25 % = after, middle 50 % = into. Rows that can't contain
  (objects, areas, linked assemblies) only have before/after halves.
- **Reorder indicator**: 2 px `ACCENT` line across the row width at the gap, starting at the target indent, with a
  6 px hollow circle at its left end.
- **Into indicator**: target row gets `SEL@0.5` fill and a 1 px `ACCENT` outline (rounding 4).
- **Invalid** (cycle, into a linked assembly, Loose into a project row's sibling level, a project into a folder):
  target row `ERR@0.10` fill, cursor `NotAllowed`, tooltip line in `ERR` with the reason
  ("Can't put Station A inside itself").
- Hovering a collapsed container for 600 ms while dragging opens it. Within 24 px of the top/bottom edge the tree
  auto-scrolls (8 px per frame).
- Dropping into another project moves the object there; its file is copied into that project's `files/` (toast says
  so, Ctrl Z undoes). Dropping into a local assembly adds a part (writes the `.lasm`).
- Tree order is only organisation. Tooltip on the front-order badge reminds: "Front order, not tree order, decides
  which object keeps overlapping blocks."

### 7.6 Empty states

- No projects open and no loose placements: centred in the tree area, `DIM`:
  "No projects open." + buttons **New project** (primary) / **Open project…**.
- Filter with no matches: "Nothing matches “abc”." + text button "Clear filter".
- Empty project or folder (open): one `DIM` `small` row at child indent: "Empty. Right-click › Add, or drag
  objects here."

---

## 8. Properties window

Shows the **active** item (last clicked). Body sections are separated by a 1 px `LINE2` rule; each starts with a
`Widgets.label` heading (upper-case `small` `DIM`). Controls are ImGui built-ins (`inputInt`, `button`, combos).

### 8.1 Header (all types)

- Row 1: type icon 16 (type colour) + name `inputText` (fill width, `uiBold` if available). Enter or focus-out commits
  (one undo step). Loose: read-only text "Loose placements". Multi-selection: "3 objects" text, no input.
- Row 2: source line in `mono` 12 (`monoS`) `DIM`, ellipsized in the middle (`files/…/tunnel_segment.litematic`);
  hover shows the full path, click copies it (toast "Path copied"). For linked items the line is `LINK` and starts
  with the chain icon.

### 8.2 Transform section (objects, assemblies, areas, cutters)

- **Location**: one row per axis (label column shows "Location" on the X row only):
  `[X tag 18][DragInt, flex][− 25][+ 25]`. Dragging on the field scrubs (1 block per 4 px), double-click or Ctrl+click
  types (ImGui `DragInt` behaviour), Enter/focus-out commits. The − / + buttons step 1 block, **Shift = 16 blocks
  (a chunk)**, and repeat while held (`pushButtonRepeat(true)`); a held repeat is one undo step. Values are world
  block coordinates of the object's min corner. Three rows instead of three columns leave room for the steppers
  at 260 px and keep numbers like −12 345 readable.
- **Rotation Y**: segmented control 0° · 90° · 180° · 270° (four `button`s in a `PANEL2` frame, active = `BTN` fill +
  `LINE` inset, `FG`). One click = one undo step.
- **Mirror**: one toggle "Mirror X" (same segmented look), tooltip "Mirror Z is Mirror X + 180°; it's in the
  Transform menu". No disabled Z button.
- **Multi-selection** (a batch view, never `sel[0]`'s values): header "3 objects · 12,480 blocks", the pivot mode
  (text, links to the toolbar combo), Location rows show the **pivot** position; typing moves the whole group so the
  pivot lands there, and the − / + steppers nudge every object by the same delta. Rotation shows no active segment
  when mixed and has two buttons "↺ 90°" "↻ 90°" that rotate every object around the pivot. Below: Mirror X, Front
  order buttons (keep relative order), **Create assembly** (Ctrl G), **Hide**, **Delete**. Per-object sections
  (Blocks, Modifiers) aren't shown; a list of the selected names (click = make active) is.

### 8.3 Project

1. Stats row: `numM` numbers with `small` captions: objects · blocks after trims.
2. **Project**: World (text), Origin (XYZ fields), Syncmatica (shared / local only, with "Share" / "Stop sharing"
   text button).
3. Buttons: **Make active** (or a passive "Active project" label in `ACCENT`), **Push** (when shared and pending),
   **Export ▸** (popup: Project .zip, Bake to one .litematic).
4. Note (`small` `DIM`): "New objects go into the active project. Linking this project into another one places it
   there as an assembly."

### 8.4 Folder

Items count; buttons **Make assembly** (Ctrl G), **Convert to project**, **Export .zip**. Note: "Folders only
organise. Front order decides which blocks count."

### 8.5 Loose

Items count; note "Placements that belong to no project, as in plain Litematica. Drag them into a project, or
convert this into one."; button **Convert to project…**.

### 8.6 Object (schematic)

1. Header, Transform.
2. **Blocks**: `numM` "counted" + (if trimmed) `numM` in `OVERLAP` "−n trimmed by front objects". While its derived
   file rebuilds the numbers are `DIM` with a spinner after the label.
3. **Front order**: label row with the "P2" badge right-aligned. Buttons: `Front` · `↑` · `↓` · `Back` (one row,
   the arrows are square 25 × 25). Then one line per overlap: `ERR` "Loses 312 blocks to **roof** (P1)" /
   `DIM` "Wins 80 blocks over **platform**". Each name is a link that selects that object. No overlaps:
   `DIM` "No overlaps. When objects overlap, the one in front (P1) keeps the blocks."
4. **Modifiers**: the stack (8.10), then two buttons **+ Array** **+ Boolean** (Array disabled when one exists).
5. Linked duplicate: a line under the header: chain icon + "Shares its file with 2 other objects".

### 8.7 Assembly instance

Header (chain icon line for linked), Transform, then:
- **Assembly**: Parts n; Source (linked: path in `LINK` + "Reload"; local: "Saved in this project").
  Buttons: **Make local** (linked only), **Break up**, **Realise** (independent copies), **Open source** (opens the
  source project, linked only).
- Blocks, Front order, Modifiers (Array and Boolean both allowed on an instance).

### 8.8 Heavymatica area

Header (area icon `AREA`), Transform plus **Size** XYZ (integer, ≥ 1), then:
- **Area**: Volume "4,608 blocks"; owner mod "Heavymatica"; button **Open in Heavymatica**.
- Modifiers: Array only (Boolean is hidden, not disabled, since it never applies).
- No Blocks or Front order sections.
- Note in `AREA`: "Areas never change schematic blocks and take no part in Booleans or the front order."

### 8.9 Cutter object (target of a Boolean)

Header uses the ∩ icon in `BOOL`. Transform, then:
- **Cutter**: "Cuts **tunnel_segment** (Difference)" one line per user, names are links. "Its own blocks aren't
  counted, printed or pushed."
- Modifiers: Array allowed (repeating holes), Boolean hidden.

### 8.10 Locked (part of a linked assembly)

A banner at the top: `PANEL2` card, 1 px `LINK@0.5` border, lock icon + "Part of linked assembly **Station A**
(`station_a.lasm`). Read-only here." Buttons **Select assembly**, **Make assembly local**. All fields below are
shown inside `beginDisabled()` (ImGui draws them at 50 % alpha).

### 8.11 Modifier stack

Cards stack top to bottom in **evaluation order** (top runs first). Gap 6 between cards.

Card header (30 high):
```
| ⋮⋮ grip 10 | ▾ twisty | type icon 15 | "Array" | ×6 summary (DIM) | … flex … | enable toggle | ⋯ menu |
```
- Left stripe 2 px over the full card height: `ACCENT` (Array) / `BOOL` (Boolean); `GUTTER` when disabled.
- **Grip** (`GUTTER`, `FG` on hover, cursor `ResizeNS`): drag to reorder (ImGui drag-drop inside the stack; drop line
  2 px `ACCENT` between cards).
- **Twisty** collapses the body; the summary ("×6 along X", "Difference · vent_shaft_cut") shows only when collapsed.
- **Enable toggle**: a 26 × 14 switch (custom draw: pill `TRACK`, knob 10 px `FG`; on = pill `ACCENT`, knob
  `ON_ACCENT`). Off: header text `DIM`, stripe `GUTTER`, body stays editable, world copies/cut disappear, the tree
  badge turns `GUTTER`. Tooltip "Show this modifier in the world".
- **⋯ menu**: Apply (Boolean) / Realise (Array) · Move up · Move down · Duplicate · separator · Remove (`ERR`).

Array body:
- Count: integer 1–1024 (drag scrub on label).
- Offset mode: segmented **Relative** (× object size) / **Blocks**.
- Offset: XYZ fields with axis tags (Relative accepts halves, 0.5 step; Blocks integers).
- Result line `DIM` `small`: "= 8, 0, 0 blocks per copy · 48 × 6 × 7 total".
- Button **Realise into 6 objects** (normal button, realise icon).

Boolean body:
- Operation: segmented **Difference** / **Intersect**.
- Object: combo of objects in the same project (excluding itself and its own parents) + an eyedropper icon button:
  click it, then click an object in the world (cursor `Hand`, status hint "Click the cutter object · Esc cancel").
- Status line: `OK` dot "Live · derived file up to date" / spinner "Rebuilding…" / `ERR` "Rebuild failed · Retry" /
  `WARN` "The cutter doesn't touch this object: nothing is removed."
- Button **Apply → new .litematic** (primary). Tooltip: "Writes tunnel_segment_bool.litematic and replaces this
  object's file with it. Ctrl Z undoes."

### 8.12 Empty and small states

- Nothing selected: centred `DIM`: "Click an object in the world or in Scene." + one line of key caps
  "`Shift` add · `Alt` cycle overlapping · `B` box select". Under it the **"From Litematica?"** card (`PANEL2`,
  `LINE` border, rounding 6, `small` text, four rows `term → term`, left term `DIM`, right term `FG`):
  - Placement → **Object** (G move, R rotate 90°)
  - Overlapping placements → **Front order** (P1 keeps the blocks; the material list and printer see the result)
  - Repeated copies → **Array modifier** (stays editable until Realise)
  - Camera → **MMB orbit · hold RMB + WASD fly**
  A "Hide this" text button turns the card off for good (config flag); the ? guide in the toolbar keeps the content.
- Width < 300: field rows stack (label on its own line above the controls, label column 0). XYZ stays 3 columns
  down to 220 px content width; below that the fields stack.
- Long content scrolls; the header (8.1) stays pinned (draw it outside the scrolling child).

---

## 9. Top view window

A 2D map drawn with the window draw list, like Manhattan's `MapView` (reuse its grid, scale and clip code).

- Canvas: fills the body, min height 140, `SLOT` fill, rounding 6. North up, X right.
- Grid every 8/16/32 blocks by zoom (`LINE@0.7` like `MapView`); the x = 0 and z = 0 lines in `AX_Z@0.35` /
  `AX_X@0.35` (world axes, same as the ground grid in the viewport).
- Objects: filled rects in `T_OBJECT@0.55` with 1 px `#000@0.5` edges, drawn lowest top-Y first; array copies at
  70 % of that alpha. Selected: `ACCENT` fill (copies `ACCENT@0.6`). Hover: 1 px `FG@0.6` outline.
- Overlaps: `OVERLAP@0.35` fill plus 45° hatch lines every 4 px in `OVERLAP` (custom draw: clipped lines).
- Heavymatica areas: `AREA` long-dash outline (4 on, 3 off) with `AREA@0.06` fill. Cutters: `BOOL` short-dash (2/2).
- Camera: white wedge (`FG@0.13`) with a 3.5 px `FG` dot at the camera, a hollow 3 px circle at the orbit target.
  Player (when different from the camera): 3 px `ACCENT` dot.
- Header extras (right of the title, inside the body's first row): **Fit** (frame icon), **Follow camera** toggle,
  zoom text "1 px = 0.5 blocks" in `small` `DIM`.
- Legend row under the map (`small`): selected · object · overlap (hatched swatch) · area (dashed swatch) ·
  cutter (dotted swatch). Hidden below 240 px width.
- Interaction: click selects (Shift adds), drag on a selected object moves it on X/Z in whole blocks (same ghost and
  release rules as the gizmo, snapping included), drag on empty = box select, middle or right drag pans, wheel zooms
  around the cursor, double-click on empty moves the camera target there.
- Empty: "Nothing placed yet." centred `DIM`.

---

## 10. Right-click menus

Built with `beginPopup` + `menuItem(label, shortcut, selected, enabled)` + `beginMenu` for submenus.

Rules
- **Header**: first line, `Widgets.label` style, the item name or "3 objects". Not clickable.
- **Icons**: 14 px in `DIM` at x 8 (custom: draw the icon with `Icons.draw` at the item's rect before the label;
  labels get 22 px of leading space). Danger items: icon and label `ERR`.
- **Shortcuts**: ImGui draws them right-aligned in `TextDisabled` (`DIM`); use `monoS` if possible.
- **Disabled vs hidden**: show disabled (`GUTTER`, 50 %) when the action exists for this kind of item but isn't
  possible right now (Make active on the active project). **Hide** it when it never applies to this kind of item
  (Boolean on an area, Make local on a local assembly).
- **Submenus**: "▸" chevron at the right, opens on hover after 100 ms or Right arrow; flips left near the screen edge
  (ImGui does this).
- Keyboard: Up/Down, Right opens submenu, Left closes it, Enter activates, Esc closes one level.
- Opening position: at the cursor, clamped 8 px inside the screen.

### 10.1 Viewport, empty space

```
ADD TO METRO LINE 2
Add ▸                         Shift A
   Schematic…
   Assembly (.lasm)…
   Link project as assembly…
   Heavymatica area
   Folder
──
Select all                     A
Select none                    Alt A
Box select                     B
──
Frame all                      F
Show hidden                    Alt H       (disabled when nothing is hidden)
Leave local view               /           (only in local view)
──
Undo Move tunnel_segment       Ctrl Z      (disabled when empty)
Redo                           Ctrl Shift Z
```

### 10.2 One object (schematic, cutter)

```
TUNNEL_SEGMENT
Duplicate                      Shift D
Linked duplicate               Alt D
Rename                         F2
──
Transform ▸
   Move                        G
   Rotate +90°                 (R starts typed rotate)
   Rotate −90°
   Mirror X                    M
   Mirror Z (= X + 180°)
Modifiers ▸
   Add Array                   (disabled if it has one)
   Add Boolean
   ──
   Realise array (6 objects)   (only with an array)
   Apply Boolean → .litematic  (only with a Boolean)
Front order (P2) ▸
   Bring to front              Shift PgUp
   Bring forward               PgUp
   Send backward               PgDn
   Send to back                Shift PgDn
Create assembly from selection Ctrl G
Move to ▸                      (projects and their folders; current one disabled)
──
Hide                           H
Local view                     /
Frame selected                 F
──
Export ▸
   Schematic (.litematic)…
   Baked with modifiers (.litematic)…
──
Delete                         X        (ERR)
```
Cutter objects hide Front order and "Apply Boolean"; areas hide Boolean, Front order and Export (an area exports
from Heavymatica). The direct items (Duplicate, Hide, Frame, Delete) stay at the top level because they are the most
used; transforms go into Transform ▸ because they have keys anyway. The root menu is about 15 rows.

### 10.3 Several objects

Header "3 OBJECTS". Same as 10.2 minus Rename, Realise and Apply; Modifiers ▸ adds to each; Front order keeps the
objects' order among themselves; Export ▸ has "Selection (.zip)" and "Bake into one .litematic".

### 10.4 Assembly instance

Linked (header "STATION A · LINKED"):
```
Duplicate / Linked duplicate / Rename
──  Transform ▸  ·  Modifiers ▸  ·  Front order (P1) ▸
Assembly ▸
   Make local (copy files)
   Break up in place
   Realise (independent copies)
   Reload from source
   Open source project
──  Hide / Local view / Frame selected
──  Export ▸  Assembly (.zip)… · Bake to one .litematic…
──  Delete
```
Local: the Assembly ▸ submenu has "Break up in place", "Realise", "Save as linked .lasm…"; Make local, Reload and
Open source are hidden.

### 10.5 Part of a linked assembly (read-only)

```
ROOF · IN STATION A (LINKED)
Select assembly
Make assembly local
──
Frame selected                 F
```

### 10.6 Folder

```
TUNNELS
New folder
Rename                         F2
Select contents
Hide / Show                    H
──
Create assembly from folder    Ctrl G
Convert to project
──
Export folder (.zip)…
──
Delete folder…                 X     (ERR; asks "Delete 2 objects too?" in a modal)
```

### 10.7 Project

```
METRO LINE 2
Make active project                    (disabled when active)
Rename                         F2
New folder
Add ▸                                  (same as 10.1, adds into this project)
Link into active project as assembly   (hidden for the active project)
──
Push changes to Syncmatica             (only when shared; disabled when nothing pending)
Share on Syncmatica / Stop sharing
──
Export ▸   Project (.zip)… · Bake to one .litematic…
Show in file explorer
──
Close project                          (asks when there are unsaved changes)
```

### 10.8 Loose

```
LOOSE PLACEMENTS
Select contents
Show hidden                    Alt H
──
Convert to project…
```

---

## 11. World-space visuals

### 11.1 What draws where

| Element | Drawn with | Depth test |
|---|---|---|
| Selection/hover outlines, array copy outlines, ghost outline, overlap volumes, cutter wires, Heavymatica areas, snap face | Minecraft world rendering (`RenderType.lines()` / debug quads) after Litematica's schematic pass | see each |
| Gizmo arrows, Y ring, name tags, snap label, box-select rectangle, HUD | **recommended:** ImGui *background* draw list with world points projected to the screen each frame (Inter text, exact px widths, same hit-test space as the mouse). Fallback: world lines scaled by distance + Minecraft billboard text | none (always on top) |

Minecraft's line render type expands lines into screen-space quads, so the px widths below hold on every platform
(no reliance on `glLineWidth`, which macOS core profile caps at 1). Dash patterns in the world are in **blocks**
(computed once per box), in screen space they are in **px**.

Screen-constant world sizes: `worldLen = px * s * dist * 2 * tan(fovY / 2) / viewportHeightPx`, where `dist` is the
distance from the camera to the pivot. Recompute every frame.

### 11.2 Outlines

All outlines are drawn on the object's block-aligned bounding box, inflated by 0.02 blocks to avoid z-fighting.

| Element | Colour | Width | Pattern | Depth |
|---|---|---|---|---|
| Hover (not selected) | `FG@0.45` | 1.5 | solid | on |
| Selected, active | `ACCENT` | 2 | solid | on; plus an x-ray pass `ACCENT@0.30`, 1 px, depth off |
| Selected, not active | `ACCENT@0.75` | 1.5 | solid | on; x-ray `ACCENT@0.20` |
| Array copies of a selected object | `ACCENT@0.50` | 1 | solid | on |
| Array copies, not selected | none (Litematica renders them) | | | |
| Assembly instance selected | `ACCENT` box around the whole instance; parts get no outline | | | |
| Selected part inside a local assembly | part `ACCENT` 2; its assembly box `ACCENT@0.35` 1 px, dash 1/0.5 blocks | | | |
| Linked assembly hovered | `LINK@0.6` 1.5 (tells "this is linked" before you click) | | | |
| Disabled/locked selection | `GUTTER` 1.5 solid | | | on |

### 11.3 Drag ghost (move, rotate, top-view drag)

The placement doesn't move until release (decision), so during a drag:
- The **original** keeps its solid selection outline at `ACCENT@0.35`, 1.5 px.
- The **ghost** is the box at the new position: `ACCENT` 2 px, dash 0.5 block on / 0.35 off, **depth off**, faces
  filled `ACCENT@0.06` (depth off). Array copies follow as 1 px `ACCENT@0.4` dashed.
- A 1 px `ACCENT@0.5` line joins the two pivots; the HUD shows the delta.
- After release the ghost disappears, the real placement jumps, and derived files rebuild (status bar spinner).
  If a rebuild takes longer than 150 ms, the ghost stays drawn at the new position with a slow 1.2 s alpha pulse
  (0.35 ↔ 0.8) until Litematica shows the moved placement, so nothing seems to "snap back".

### 11.4 Overlap volumes (front order)

- Faces: `OVERLAP@0.18`, depth **off** (visible through walls; they are the problem you need to see).
- Edges: `OVERLAP@0.85`, 1.5 px, depth off.
- Second cue: 45° hatch on the top face, lines every 1 block in `OVERLAP@0.5`, 1 px.
- Label (screen space, 11.7): "−312 from platform" at the top-face centre, `OVERLAP` text, **only while the
  overlap or one of its two objects is hovered or selected** (volumes are always drawn; labels would pile up).
- Shown always while in editor mode; the Issues chip and tree trims mirror them.

### 11.5 Boolean cutters (wire)

- `BOOL` 1.5 px, dash 0.25 block on / 0.25 off (dotted look), depth on; x-ray pass `BOOL@0.25` 1 px depth off.
- Selected: `ACCENT` 2 px solid (selection wins), dotted `BOOL` drawn 0.05 blocks outside it so the role stays visible.
- The cut volume (intersection with the target) faces `BOOL@0.10`, depth off, only while the cutter or its target
  is selected.
- Disabled Boolean modifier: wire turns `GUTTER@0.6`.

### 11.6 Heavymatica areas

- `AREA@0.8` 1.5 px, dash 1 block on / 0.5 off, depth on; x-ray `AREA@0.25` 1 px depth off.
- Top face fill `AREA@0.05`.
- Selected: `ACCENT` 2 px dashed in the same pattern (pattern = type, colour = state).

### 11.7 Name tags and labels (screen space)

- Anchor: top-face centre of the box, offset 14 px up on screen. Pill: `0x14161a@0.82`, rounding 4, pad 6 × 2,
  `monoS` 12 text, 18 high.
- Selection: tag in `ACCENT` for each selected object, up to 8; with more, only the active one plus "+n" on it.
- Hover (after 300 ms still): tag in `FG@0.85`.
- Overlap labels in `OVERLAP` (11.4), snap label in `ACCENT` (11.9).
- Tags never overlap each other: if two tags' rects intersect, the later one moves up by tag height + 4 (max 3 times,
  then hidden).
- Hidden while flying and while orbiting (they flicker otherwise).

### 11.8 Gizmo (move)

Origin = pivot (Median: centre of the selected boxes' centres; Active object: its box centre; Bounding box: centre of
the union). All sizes screen px × `s`.

- **Centre dot**: r 4.5 `FG`, 1 px `#000@0.5` ring.
- **Arrows** (X, Y, Z, positive direction only):
  - shaft from 14 px to 84 px along the projected axis, 2.5 px;
  - head: triangle 14 long × 12 wide, filled;
  - letter: "X/Y/Z" `monoS` bold-ish (draw twice offset 0.5 px if no bold font) in the axis colour, 12 px past the tip;
  - halo: same geometry drawn first in `#000@0.45`, +2 px width, for contrast on bright terrain.
- **XZ plane handle**: the most common Minecraft adjustment is sliding on the ground, so the move gizmo has a
  ground-plane square between the +X and +Z arms: the parallelogram spanned by the projected X and Z directions from
  30 px to 48 px along each. Fill `AX_Y@0.30` (the plane's normal colour, as in Blender), 1.5 px `AX_Y` edge, same
  halo. Hover: fill `AX_Y@0.55`, cursor `ResizeAll`. Dragging moves on X and Z at once (cursor ray intersected with
  the horizontal plane through the pivot, rounded to whole blocks); the arrows hide, both X and Z constraint lines
  show, HUD "Move XZ +4, −2". Hidden when the plane is nearly edge-on (its projected area < 60 px², camera pitch
  below about 8°). Typed equivalent: G then Shift+Y.
- **Hover** (mouse within 9 px of shaft or head): width 3.5, colour mixed 25 % toward white, cursor `Hand`, status
  hint changes (6.3).
- **Drag**: the dragged axis at width 4; the other two arrows hidden; a 1 px `AX_*@0.5` constraint line through the
  pivot across the whole screen; cursor `ResizeEW` or `ResizeNS`, whichever is closer to the arrow's screen direction.
  Movement = cursor travel projected on the arrow direction ÷ px-per-block, rounded to whole blocks.
- **Foreshortened axis**: if one block along an axis projects to < 2 px (axis points at the camera), that arrow fades
  to 25 % and can't be grabbed (use the other view or typed G X).
- **Disabled** (selection locked or includes an item that can't move): arrows `GUTTER`, 2 px, no hover; centre dot
  `GUTTER`; tag "Locked" under the dot in `DIM`.
- Not shown in the Select tool, during typed transforms, flying or box select.

### 11.9 Y ring (rotate tool)

- A horizontal circle in the world plane through the pivot, radius chosen so it projects to about **72 px**
  (compute the world radius from 11.1 at the pivot distance). 64 segments, `AX_Y` 2.5 px with the same `#000@0.45`
  halo.
- Four tick diamonds (6 × 6) at 0°, 90°, 180°, 270°.
- Hover (within 8 px of the ring): 3.5 px, lighter; small ↻ (and ↺ with Shift held) glyph 14 px in `AX_Y` drawn
  just outside the ring at the cursor's angle; status hint 6.3. No text banner in the ring's centre.
- Click (press and release < 5 px): rotate +90° clockwise seen from above; Shift-click −90°.
- Drag: angle from the pivot to the cursor, snapped to 90° steps; a filled arc from the start angle to the snapped
  angle in `AX_Y@0.15`; the ghost (11.3) shows the rotated box; HUD "Rotate Y +90°". Release applies, Esc/RMB cancels.
- Disabled: `GUTTER`, as 11.8.

### 11.10 Snap indicator

Snapping (toolbar Snap on; hold **Ctrl** during a drag to invert) pulls the moving box face onto a face of another
object within 1.5 blocks along the drag axis.

- The two touching faces' shared rectangle: fill `ACCENT@0.15`, outline `ACCENT` 2 px, depth off.
- A 7 px diamond in `ACCENT` at the rectangle centre (screen space).
- Label next to the diamond: "snapped to vent_shaft_cut" in `ACCENT` (also in the HUD).
- Disappears on release.

### 11.11 Box-select rectangle (screen space)

- Replace / add mode: 1 px `ACCENT` border, `ACCENT@0.08` fill.
- Subtract mode (Ctrl held): 1 px `ERR` border, `ERR@0.06` fill.
- Objects that will be selected get the hover outline (11.2) live while dragging.
- An object is hit when its projected bounding box intersects the rectangle. Parts of assemblies select their
  assembly; hidden objects are skipped; locked parts are skipped.

---

## 12. Interaction details

### 12.1 Entering and leaving editor mode (F6)

Enter:
1. Release the mouse cursor (GLFW normal cursor), hide the vanilla HUD (hotbar, crosshair, hand). The camera stays
   where the player looks (spectator: the real player; otherwise a free camera starting at the player's eyes).
2. Toolbar slides down 8 px and fades in; status bar slides up 8 px and fades in; windows fade in. 140 ms, ease-out.
   (Alpha via `ImGuiStyleVar.Alpha`; offset by adjusting `setNextWindowPos` during the transition.)
3. Toast "Editor on · F6 to leave" only the first 3 times per install.

Leave:
1. Commit any running rename; cancel a running drag/typed transform/box select (as Esc).
2. Fade everything out in 100 ms, then capture the cursor and restore the HUD. Free camera returns to the player
   instantly (no fly-back animation; it disorients).
3. Selection, window layout, camera per world are kept for the next F6.

`prefers reduced motion`: there is no OS hook in Minecraft, so add a setting "UI animations" (on by default); off =
no fades or slides anywhere (also no blinking caret).

### 12.2 Mouse

| Input | Result |
|---|---|
| LMB press on gizmo | start gizmo drag |
| LMB press + release < 5 px on an object | select (Shift toggles, Alt cycles through objects under the cursor) |
| LMB press + release < 5 px on empty | deselect (Shift: keep) |
| LMB drag ≥ 5 px starting on empty, or after **B** anywhere | box select |
| LMB double-click on an object | select it and frame it (F) |
| MMB drag | orbit around the target; cursor hidden and locked, restored to the same spot on release |
| Shift MMB drag | pan; same cursor handling |
| Wheel | zoom toward the cursor point (dolly), 12 % per notch |
| RMB press, move ≥ 5 px **or** press W A S D Q E while held | fly; cursor hidden and locked until RMB is released |
| RMB press + release with neither | open the context menu for what's under the cursor (selects it first if it wasn't selected) |
| RMB during a gizmo drag / ring drag / typed transform | cancel |
| Wheel while flying | fly speed ×1.2 / ÷1.2, range 0.2–8× |

The 5 px threshold is `io.MouseDragThreshold = 5 * s`, also used by ImGui drag-and-drop in the tree.

### 12.3 Cursors

| Situation | Cursor |
|---|---|
| default over the viewport | Arrow |
| over a gizmo handle or ring | Hand |
| dragging an arrow | ResizeEW / ResizeNS by screen direction |
| dragging the ring | ResizeAll |
| box select armed (after B) or eyedropper | Crosshair (**custom**: `glfwCreateStandardCursor(GLFW_CROSSHAIR_CURSOR)`, with ImGui's `NoMouseCursorChange` set while active) |
| orbit, pan, fly | hidden + disabled (raw motion) |
| invalid tree drop | NotAllowed |
| text fields | TextInput (ImGui default) |

### 12.4 Keyboard focus

Who gets a key, checked in this order every frame:
1. **Active text field** (`io.WantTextInput`): gets everything. Enter commits, Esc cancels the edit, Tab moves to the
   next field. No editor shortcut fires, not even Ctrl Z (ImGui's own undo inside the field works).
2. **Modal states** (typed transform, box select armed, eyedropper, gizmo/ring drag, tree drag): their keys
   (digits, X/Y/Z, −, Backspace, Enter, Esc, Ctrl) wherever the mouse is. Other editor shortcuts are ignored.
3. **Fly** (RMB held): W A S D Q E Shift; all other keys ignored; ImGui gets no mouse (`NoMouse`).
4. **Open popup/menu**: arrows, Enter, Esc go to the menu.
5. **Tree focused** (the Scene window is focused and the mouse is over it or it was last clicked): arrows, Left/Right,
   Space, Enter, F2 act on the tree; all other shortcuts are global.
6. **Global editor shortcuts** otherwise, regardless of which editor window the mouse is over (so H works while
   pointing at the tree).

Minecraft key bindings are suppressed in editor mode except **F6** (leave) and **F3** (debug overlay). F2 is Rename
in the editor, so the vanilla screenshot key works only outside editor mode (or when rebound to another key).
Chat (T, /) is suppressed; `/` is local view. The editor's `UiLayer` reports `wantsKeyboard()` = true for every key
it consumes by these rules (not only when ImGui wants it), and `wantsMouse()` = true while a viewport gesture is in
progress, so cg-uihost keeps those events away from Minecraft.

Esc cascade (one step per press): close submenu → close menu → cancel rename → cancel typed transform / drag / box
select / eyedropper → nothing left: open the vanilla pause menu (the editor stays
on behind it).

### 12.5 Shortcut list (editor mode)

| Key | Action |
|---|---|
| F6 | leave editor |
| W / G / R | Select / Move / Rotate tool; with a selection, **G** and **R** start a typed transform |
| G then X/Y/Z then number, Enter | typed move |
| R then number (90, 180, −90), Enter | typed rotate (rounds to 90°) |
| M | mirror X |
| B | box select |
| A / Alt A | select all (in local view: what's visible) / none |
| Shift D / Alt D | duplicate / linked duplicate (the copy starts as a typed G move, like Blender) |
| Ctrl G | create assembly from selection |
| H / Alt H | hide selection / show all |
| / | local view on/off |
| F or numpad . | frame selection (all when nothing selected) |
| F2 | rename active |
| X / Delete | delete (no confirm; toast with Undo) |
| PgUp / PgDn, Shift PgUp / Shift PgDn | forward / backward, front / back |
| Shift A | Add menu at the cursor |
| Ctrl Z / Ctrl Shift Z, Ctrl Y | undo / redo |
| Ctrl F | focus the Scene filter |
| Tab | hide / show all editor windows (toolbar and status bar stay) |
| G Shift+Y | typed move on the XZ plane |
| Ctrl S | save all open projects (they also save on every committed change; this is for peace of mind) |

macOS: Cmd replaces Ctrl in labels and handling (Manhattan's `Platform.shortcutName()`).

### 12.6 Toasts

Reuse the look of Manhattan's `Overlay.drawToasts` (pill-rounded card, left bar), extended with levels:

| Level | Bar/border | Lifetime |
|---|---|---|
| info | `ACCENT` bar, border `LINE` | 3.6 s |
| warn | `WARN` bar, border `WARN@0.5` | 5 s |
| error | `ERR` bar, border `ERR@0.7` | 8 s, or until clicked |

- Position: bottom-right, 12 from the right edge, 12 above the status bar; stack upwards, max 3 (the oldest goes).
- Text `ui` `FG`, names in `uiBold`/`ACCENT`. Optional single action as a text button on the right in `ACCENT`
  ("Undo", "Retry", "Show"). Toasts with an action are a tiny ImGui window (`NoDecoration | NoFocusOnAppearing`);
  plain toasts are drawn on the foreground draw list like Manhattan's.
- Hovering a toast pauses its timer. Fade-out 600 ms (Manhattan).
- Use toasts for results of actions that change files or happen out of sight (Delete, Realise, Apply, Export, Push,
  Make local, file copied into another project). Don't toast what is visible anyway (selection, tool change,
  hide/show).

---

## 13. Icons

Style: 16-unit grid, line icons, round caps and joins, stroke `max(1.4, r / 6)` like `Icons.draw`, no fills except
where noted. The SVG `path` data in `mockup-v2.html` (`const ICON = {…}`) is the reference geometry for each new
icon: port each command to `addLine` / `addPolyline` / `addCircle` / `addRect` with `x = cx + (u − 8) / 8 * r`.

| Icon | Status | Drawing |
|---|---|---|
| `AREAS` dashed square | **exists** in Manhattan `Icons` | Heavymatica area (tree, Properties, menu) |
| `CAPTURE` crosshair | **exists** | eyedropper/pick target in the Boolean card |
| `EDITOR`, `LOG`, `PRESETS` … | exist, not used | — |
| `cursor` | new | arrow pointer: closed triangle from top-left, tail to lower right |
| `move` | new | plus with four small arrowheads at the ends |
| `rotate` | new | 300° arc with an arrowhead at the top-right end |
| `mirror` | new | vertical centre line, two mirrored right triangles either side |
| `magnet` | new | U-shaped magnet opening up, two short bars across the tips |
| `undo` / `redo` | new | hook arrow left / right over a half-loop |
| `eye` / `eyeoff` | new | almond outline + pupil circle / almond + diagonal slash |
| `lock` | new | rounded rect body + shackle arc |
| `folder` | new | tab folder outline |
| `object` | new | isometric cube (hexagon + inner Y) |
| `assembly` | new | three small isometric cubes in a triangle |
| `cutter` (∩) | new | two overlapping circles (Boolean); used for the modifier and cutter objects |
| `array` | new | three upright rectangles in a row |
| `link` | new | two chain links at 45° |
| `project` | new | calendar-like rect with a header rule and two binder ticks |
| `loose` | new | three scattered small circles |
| `sync` | new | two arcs with arrowheads (circular arrows) |
| `chev` | new | right chevron (twisty, submenu) |
| `plus`, `close`, `trash`, `dup`, `export` | new | standard: plus; ×; bin with lid; two offset rects; arrow up out of a tray |
| `front` / `back` | new | two overlapping squares, front one solid outline at bottom-right / top-left |
| `explode` | new | four arrows pointing out from the centre + diagonals (break up) |
| `realise` | new | array icon whose middle rect is dashed + an arrow right |
| `group` | new | dashed rounded square around a small solid square (create assembly) |
| `local` | new | circle with a small filled dot (local view) |
| `frame` | new | four corner brackets |
| `active` | new | circle with a check |
| `grip` | new | 2 × 3 dots, 1.5 px, (card drag handle) |
| `kebab` (⋯) | new | three dots horizontal, filled 1.5 px |
| `layout` | new | rect with a left column and a right top/bottom split (Windows menu) |
| `search` | new | circle + handle at 45° |
| `box` | new | dashed rectangle with a small arrow cursor at its lower right (box select) |
| `help` | new | circle with a question mark (two arcs + dot) |
| `stepper −/+` | text | plain "−" / "+" labels in small buttons, no icon needed |
| `eyedropper` | new | optional; else reuse `CAPTURE` |
| `spinner` | new (procedural) | 270° arc 2 px rotating 0.7 s/turn |

Put the new ones in an editor `EditorIcons` class with the same signature as `Icons.draw(dl, name, cx, cy, r, col)`
(or extend `Icons` when the shared host lands; both mods can then use them).

---

## 14. Accessibility and readability

### 14.1 Contrast (WCAG ratios, measured)

| Pair | Ratio | Rule |
|---|---|---|
| `FG` on `PANEL` / `PANEL2` / `SEL` | 13.5 / 12.8 / 8.9 | body text |
| `DIM` on `PANEL` / `PANEL2` | 5.0 / 4.8 | secondary text OK (≥ 4.5) |
| `DIM` on `SEL` | 3.3 | **not allowed**: use `DIM_ON_SEL` (5.1) on selected rows |
| `GUTTER` on `PANEL` | 2.3 | decoration only (twisties, rules, idle eye); never the only carrier of meaning |
| `ACCENT` / `ERR` / `WARN` / `BOOL` / `LINK` on `PANEL` | 8.2 / 6.1 / 9.3 / 6.5 / 9.3 | usable as text |
| `AX_X` / `AX_Z` on `PANEL` | 5.3 / 6.1 | axis tags |
| `ON_ACCENT` on `ACCENT` | 8.0 | primary buttons, ACTIVE badge |

Minimum: text ≥ 4.5:1 against its own background; meaningful non-text marks ≥ 3:1. World overlays use the `#000`
halo (gizmo) or the dark pill (tags) so they hold on snow and on night terrain alike.

### 14.2 UI scale

Shared through cg-uihost (`UiHost.scale()` / `setScale`, one setting and one atlas for every mod). The host clamps to
0.5–4; the editor is designed for **0.75–2.5**; the design is checked
at 0.75, 1.0, 1.5 and 2.0. Fonts are re-rasterised at the scale (the host does this), so nothing is bitmap-scaled.
Screen-space world visuals (gizmo, tags, rectangles) scale with it too. Below 0.9 the toolbar drops labels
automatically (5, narrow step 1).

### 14.3 Colourblind-safe pairs

- Axes: red X / green Y / blue Z always carry the letter (gizmo tips, field tags, HUD).
- Overlap (red) vs. selection (teal): red volumes are also hatched and labelled "−n"; selection is solid outline.
- OK/WARN/ERR dots always come with words ("up to date", "3 not pushed", "failed").
- Boolean violet vs. linked sky vs. accent teal: each has its own icon (∩, chain, none) and line pattern (dotted for
  cutters, solid for links/selection).
- Heavymatica amber vs. overlap red: long-dash outline vs. hatched filled volume.
- Enabled vs. disabled modifiers: switch position and stripe, not just colour.

### 14.4 Other

- Every icon-only button has a tooltip with its name and shortcut (ImGui `setTooltip` after 500 ms hover).
- Full keyboard path: Ctrl F → tree → arrows → F2/Enter; menus are keyboard-navigable.
- No information only on hover except full paths and badge explanations.

---

## 15. Implementation map

### 15.1 Reuse

From **cg-uihost** (`ui-host/`, package `dev.cg.uihost`), the shared ImGui host:

| Need | Host API |
|---|---|
| colours, style, fonts, `u32`, `textColored`, `pushText` | `Theme` (copy of Manhattan's `UiTheme`): `Theme.ACCENT` …, `UiHost.theme().ui()/small()/mono()/numM()/numL()` |
| UI scale | `UiHost.scale()` |
| registering the editor UI | `UiHost.register(UiLayer)`; the editor is one layer, id `schemarray.editor`, `visible()` = editor mode on |
| input routing | override `UiLayer.wantsKeyboard()` / `wantsMouse()` per 12.4 |

**Host changes this spec needs** (small, in `HostCore`): enable `ImGuiConfigFlags.DockingEnable` and
`io.ConfigDockingWithShift`; let a layer request a cursor (crosshair) and cursor capture for orbit/fly; add the two
fonts `monoS` (JetBrains Mono 12) and optionally `uiBold` (Inter SemiBold 15) to `Theme.loadFonts`.

From **Manhattan** (not in the host yet; copy into the editor, or better move into cg-uihost so both mods share them):

| Need | Manhattan source |
|---|---|
| section labels, buttons (normal/primary/danger/ghost), `fit` ellipsis, `wrapped` notes, status pill, `bar` | `Widgets.label`, `Widgets.button`, `Widgets.fit`, `Widgets.wrapped`, `Widgets.statusPill`, `Widgets.bar` |
| 2D map drawing, grid, clipping, hover tooltips | `MapView.draw` (copy the structure into `TopView`) |
| line icon drawing | `Icons.draw` (same signature for new icons) |
| toasts and the HUD pill look | `Overlay.drawToasts`, `Overlay` status pill |
| Cmd/Ctrl naming | `Platform.shortcutName()` |

### 15.2 New widgets (editor-side, small)

- `kbd(String)` key cap; `badge(icon, text, col, fill)`; `axisInt(label, axis, value)` (axis-tagged int field with
  tag scrub); `segmented(String[] opts, int sel)`; `toggleSwitch(id, on)`; `twisty(open)`; `spinner(r, col)`;
  `iconButton(icon, tooltip, enabled, on)`; `menuItemIcon(icon, label, shortcut, enabled, danger)`.

### 15.3 Needs custom draw-list code (flagged)

- Tree row composition (twisty, icon, name runs with search highlight, badges, eye) on top of one
  `selectable(…, SpanAvailWidth | AllowOverlap)` per row.
- Drop indicators in the tree and the modifier stack.
- Modifier card stripe and the toggle switch.
- Icons inside menu items (draw at the item rect; ImGui menu items have no icon slot).
- Top view hatch and dashed outlines.
- All screen-space world visuals (gizmo, ring, tags, box rect, snap diamond) on the background draw list.
- Crosshair cursor (GLFW directly).

### 15.4 Needs world rendering (Minecraft side)

Box outlines with dash patterns (generated as segments), x-ray passes (depth test off), translucent faces for overlap,
ghost and snap volumes. No custom shaders: `RenderType.lines()` and a translucent position-colour quad type with
depth test toggled are enough.
