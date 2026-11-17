# Litematica Array 2.0 — Independent UI/UX Design Proposal (Gemini)

**Scope:** Independent second design proposal for the Dear ImGui 1.90 (`imgui-java`) placement editor, grounded in [`DECISIONS.md`](../../DECISIONS.md), the baseline [`mockup.html`](../../mockup.html), and the existing Manhattan theme tokens and `ImDrawList` primitives in [`UiTheme.java`](../../../../Manhattan/src/main/java/dev/baritonequeue/UiTheme.java) and [`Widgets.java`](../../../../Manhattan/src/main/java/dev/baritonequeue/Widgets.java).  
**Constraint check:** Every idea below uses standard Dear ImGui 1.90 widgets, `ImGuiTable`, `ImGui::BeginMenu`, `ImDrawList` vector primitives (lines, rounded rects, circles, polygons), and Minecraft's existing world-space line/quad renderer—**zero custom shaders**.  
**Priority legend:** **`[MUST]`** essential for 2.0 clarity/usability · **`[SHOULD]`** high-value polish · **`[COULD]`** nice-to-have / post-M2 enhancement.

---

## 1. Critique of `mockup.html`

`mockup.html` establishes strong foundations—the dark `#131518` / `#191c20` / `#5ec4b6` Hybrid palette, clear 3-axis color coding, live overlap trim callouts, and an intuitive typed-transform loop. However, inspecting it against real Minecraft play and Dear ImGui ergonomics exposes eight friction points:

1. **Floating-window collision & Properties starvation:** `w-props` is capped at `max-height: calc(100% - 330px)` to avoid colliding with `w-map` pinned below it. On a 720p–900p Minecraft window, selecting an object with an Array + Boolean modifier forces heavy scrolling inside Properties while `Top view` permanently consumes 250px of vertical height even when not needed. Meanwhile, `mockup.html` uses frosted translucent rounded windows (`backdrop-filter: blur(6px)`), whereas `UiTheme.java` explicitly defines crisp opaque `PANEL` (`0x191c20`) surfaces with `WindowRounding(0f)`, `ChildRounding(6f)`, and `LINE` (`0x2a2e35`) bordered child cards.
2. **Scene graph badge bloat & truncated names:** A single object row can accumulate up to six right-side widgets (`ACTIVE`, sync dot, link, `×6`, `bool`, `−128`, `P2`, lock, eye), squeezing the object name to ~90px at 318px panel width. Front-order (`P2`) and lock indicators also visually blend with modifier pills, and there is no priority-sorted or overlap-focused view when resolving conflicts across multiple open projects.
3. **Raw number inputs & multi-select trap in Properties:** Location inputs lack 1-block `-`/`+` nudge steppers (vital in Minecraft), modifiers cannot be collapsed, toggled off, or reordered, Boolean targets lack an in-world eyedropper picker, and selecting multiple objects still renders `sel[0]`'s single-object transform inputs (editing `X` moves only the first object despite the note claiming shared-pivot editing). The disabled `Mirror Z` button looks broken rather than helpful.
4. **18-item flat right-click menu:** Selecting an object and right-clicking opens a ~520px vertical list with five dividers, risking viewport clipping and slow visual scanning.
5. **Noisy Rotate gizmo & missing XZ plane handle:** The Rotate gizmo stamps a large text banner across the center of the ring (`Y · 90° steps · click ↻ shift-click ↺`), while the Move gizmo lacks an XZ ground-plane handle—even though sliding schematics horizontally across terrain without changing Y is the most frequent placement action in Minecraft. Always-on overlap labels also clutter multi-overlap scenes.
6. **Toolbar overflow & status-bar shortcut dump:** At narrow widths, `#toolbar` scrolls horizontally; `Add (Shift+A)` and `Box Select (B)` are missing from the toolbar; and `#status` packs nine static keycap hints that clip or vanish entirely on narrower windows.
7. **Minimal Fly / Typed-Transform HUD:** The single-line `#hud` hides typed axis options until pressed and gives no visual feedback for spectator chunk loading during RMB fly mode or start-vs-current ghost delta during gizmo drags.
8. **Blender-heavy onboarding gap:** Litematica users think in *Placements, Regions, Material Lists, and Printers*, not *Outliners, Median Pivots, Linked Duplicates, Local View, or Non-Destructive Modifiers*. `mockup.html` hides creation behind empty-space right-click and shows dead-end toasts when clicking inside linked assemblies.

---

## 2. Concrete Alternative Proposals

### 2.1 Layout & Window Choreography

- **`[MUST]` Docked side rails with collapsible Top View + Focus Mode:** Replace permanently overlapping floating boxes on the right with a **unified Inspector rail** (dockable/undockable via ImGui docking or `ImGuiWindowFlags`) where **Properties** fills the primary region and **Top View** sits in a **collapsible bottom split card** (drag-splitter or header click to collapse to a 30px bar, returning 100% height to Properties). Add a **`Panels` toggle (`Tab` or toolbar icon)** to collapse both side rails for unobstructed building inspection.  
  *ImGui implementation:* Two `ImGui::BeginChild` regions separated by an `ImGui::InvisibleButton("##split", -1, 6*s)` drag splitter inside the docked/floating `Inspector` window, styled with `UiTheme.PANEL`, `ChildRounding(6f)`, and `LINE` borders (matching `Widgets.beginTile`).
- **`[MUST]` Alignment with `UiTheme.java` Hybrid surface hierarchy:** Use opaque `PANEL` (`0x191c20`) for dock frames, `PANEL2` (`0x1e2126`) for title bars and modifier cards, and `SLOT` (`0x1c1f24`) for numeric wells—avoiding fake blur shaders. Reserve faint translucency (`0.94f` alpha) only for the floating viewport toolbar and HUD pills.  
  *ImGui implementation:* `ImGui::PushStyleColor(ImGuiCol_WindowBg, ...)` using existing `UiTheme.u32()` helpers.
- **`[SHOULD]` Dedicated "Add" drawer / button in Scene footer & Toolbar:** Never require discovering empty-viewport RMB to place a schematic. Put a primary-tinted **`+ Add` (`Shift+A`)** button in both the toolbar and the Scene panel header/footer.  
  *ImGui implementation:* `Widgets.button("+ Add", 1)` opening an `ImGui::BeginPopup("add_menu")`.
- **`[COULD]` Pop-out Top View:** Allow clicking an undock icon in the Top View card header to promote it to a standalone floating ImGui window on multi-monitor setups.

---

### 2.2 Scene Graph Row Design

- **`[MUST]` Fixed-rail 3-zone row layout (`Name` | `Compact Status` | `Prio + Eye`):** Give the object name maximum horizontal budget by collapsing secondary metadata into a fixed-width trailing rail:
  1. **Left:** Disclosure chevron + `14px` semantic type icon + **Object name** (middle- or end-truncated via `Widgets.fit()`).
  2. **Middle-right (max 2 tokens):** A single combined modifier pill (`×6 · ◐` when both Array and Boolean exist, with full breakdown on hover) or link glyph (`🔗`), plus an **explicit red trim badge (`−128`)** *only* when the object is currently losing blocks in an overlap.
  3. **Far-right fixed rail:** Clickable `P#` front-order tag (`40px` fixed column, highlighted in `--warn`/`--err` if involved in an overlap) + `Eye` visibility toggle (`22px`).  
  *ImGui implementation:* `ImGui::BeginTable("##scene", 3, ImGuiTableFlags_RowBg | ImGuiTableFlags_NoBordersInBody)` with `TableColumnFlags_WidthStretch` for column 0 and `TableColumnFlags_WidthFixed` for columns 1–2; custom `ImDrawList` rounded badge pills drawn via `ImGui::GetWindowDrawList()`.
- **`[MUST]` "Overlaps / Order" filter mode:** Add a 5th filter chip **`Overlaps`** (or `Front Order` sort toggle) next to `All / Schematics / Assemblies / Areas`. Toggling it flattens active trimmed/overlapping objects sorted `P1 → Pn` across all open projects so priority conflicts can be resolved in seconds.  
  *ImGui implementation:* Conditional flat list pass inside the same `ImGuiTable` loop.
- **`[SHOULD]` Assembly lock indicator at header + helpful child click:** Instead of repeating a lock icon on every child row inside a linked assembly, show the `Linked · Read-only` badge cleanly on the assembly row and mute child rows slightly (`DIM`). Clicking a child still selects the parent assembly (`owner-sel` tint on the clicked row) and displays an inline action banner in Properties instead of only firing an ephemeral toast.
- **`[COULD]` Hover `+` quick-add on Project/Folder rows:** Hovering a project or folder row reveals a subtle `+` icon button to add a child schematic or subfolder directly into that container.

---

### 2.3 Properties & Modifier Cards

- **`[MUST]` Dedicated Multi-Select Inspector mode:** When `sel.length > 1`, replace single-object `X/Y/Z` absolute inputs with a **Selection Batch** panel: selection count + combined block total, active `Pivot` indicator, **Relative Nudge (`ΔX / ΔY / ΔZ` steppers)**, batch `Rotate ±90°`, `Mirror X`, `Create Assembly (Ctrl+G)`, `Front/Back`, and `Hide/Delete`. This prevents the bug where editing `Location` silently moves only `sel[0]`.  
  *ImGui implementation:* Branch on `selection.size() > 1` at the top of `renderProperties()`.
- **`[MUST]` 1-block `-` / `+` steppers + drag-int on Location:** Wrap each `X`, `Y`, `Z` coordinate in an `ImGui::DragInt` (drag horizontally for fast shifts, double-click to type) flanked by compact `-` / `+` buttons (`Hold Shift` = 16-block chunk step!).  
  *ImGui implementation:* `ImGui::PushButtonRepeat(true)` around `-` / `+` small buttons + `ImGui::DragInt` with `AX_X`/`AX_Y`/`AX_Z` colored left accent strip drawn via `ImDrawList::AddRectFilled`.
- **`[MUST]` Collapsible, toggleable, reorderable Modifier Cards:**
  - Header bar: collapse arrow + icon + title (`Array · ×6 · 48×6×7` or `Boolean · Difference`) + **Enable/Disable eye toggle** + **↑/↓ reorder** + **× remove**.
  - **Array card:** Support switching spacing mode between **Relative (`× size`)** and **Blocks (`exact gap`)** and show total generated span + total blocks inside the card.
  - **Boolean card:** Add an **Eyedropper (`Pick`)** button next to the target dropdown so clicking an object in the 3D world assigns it as the cutter immediately. Keep `Apply → new .litematic` as the primary accent CTA (`Widgets.button(..., 1)`).  
  *ImGui implementation:* `ImGui::BeginChild` bordered card (`LINE` border, `PANEL2` bg) with `ImDrawList` colored left accent bar (`ACCENT` for Array, `BOOL` `#b48cf0` for Boolean).
- **`[SHOULD]` Clean Mirror control:** Remove the permanently disabled `Z` button. Show `Mirror X (M)` as a full-width toggle alongside a `Mirror Z (X + 180°)` convenience action button (or tooltip explaining that Minecraft block states + 90° Y rotations make `Mirror Z` equivalent to `Mirror X` + `180°`).
- **`[COULD]` Inline Linked-Assembly banner:** When a linked assembly is selected, display a purple-accented header card (`Linked from station_a.lasm`) with one-click `Make local` and `Break up` buttons right at the top.

---

### 2.4 Right-Click Context Menu Structure (with Submenus)

Replace the 18-item flat menu with a scannable **7-row menu with submenus** (target height ~240px):

- **`[MUST]` Object / Multi-Select Context Menu:**
  - *Header (non-interactive):* `tunnel_segment` (or `3 objects`) · `Project / Assembly`
  - `Duplicate` (`Shift+D`)
  - `Linked duplicate` (`Alt+D`)
  - `Transform ▸` → `Move (G)` · `Rotate +90° (R)` · `Rotate −90° (Shift+R)` · `Mirror X (M)` · `Mirror Z (X + 180°)` · `Snap to nearest face`
  - `Modifiers ▸` → `Add Array` · `Add Boolean` · *(sep)* · `Realise array (N objects)` *(when Array present)* · `Apply Boolean → .litematic` *(when Boolean present)*
  - `Front order (P2) ▸` → `Bring to front (P1)` · `Bring forward` · `Send backward` · `Send to back`
  - `Assembly ▸` → `Create assembly from selection (Ctrl+G)` · *(when Assembly)* `Break up in place` · `Make local (copy files)`
  - `View & Visibility ▸` → `Hide / Show (H)` · `Local view (/)` · `Frame selected (.)` · `Show all hidden (Alt+H)`
  - `Share & Export ▸` → `Push to Syncmatica` · `Export .litematic / .zip` · `Bake to one .litematic` *(when Assembly)*
  - *(separator)*
  - `Delete` (`X`) in `ERR` red.
- **`[MUST]` Empty Viewport Context Menu:**
  - `Add to <Active Project> ▸` → `Schematic… (Shift+A)` · `Assembly…` · `Link project as assembly…` · `Heavymatica dig area`
  - `Show all hidden (Alt+H)` · `Frame all (F)`
- **`[SHOULD]` Project / Folder Context Menu:** Keep compact (`Make active project`, `New folder`, `Convert to project` for Loose/Folder, `Assembly ▸`, `Export .zip`, `Push to Syncmatica`, `Close project`).  
  *ImGui implementation:* Native `ImGui::BeginPopupContextWindow()` / `ImGui::BeginMenu()` / `ImGui::MenuItem(..., shortcut)`. ImGui handles submenu hover delay, keyboard navigation, and viewport-edge flipping automatically.

---

### 2.5 Gizmo Look in the World

- **`[MUST]` Add XZ Ground-Plane Handle to Move Gizmo:** Add a small translucent square handle (`#f2b35e` or `#e2e5e9` corner bracket between the `+X` and `+Z` arms) so dragging it slides the schematic across `X` and `Z` simultaneously while keeping `Y` locked—matching 90% of Minecraft placement adjustments.  
  *Rendering:* Existing world-space line/quad pass + screen-space 2D quad hit-test in `ImDrawList` / mouse ray.
- **`[MUST]` Clean 90° Rotate Ring without center text clutter:** Remove the centered `Y · 90° steps · click ↻ shift-click ↺` text banner. Instead, draw **4 crisp cardinal tick dots/notches** (`0°`, `90°`, `180°`, `270°`) on the green Y-ring, draw subtle curved `↻` / `↺` glyphs on hover, and show a translucent **90° sector wedge** when hovering or rotating.
- **`[MUST]` Drag Ghost + Dimension Tether + Snap Target Face Highlight:** While dragging (`DECISIONS.md`: outline only), keep a faint **dotted ghost box at the origin position**, draw a dashed **tether line** from start center to current center with a `+12, 0, −4` block pill, and highlight the **snapped neighbor face** in solid `--accent` (`#5ec4b6`) so face snapping is unmistakable.
- **`[SHOULD]` Selection-only / Hover-only Overlap Tags:** Render red overlap volumes and edges at all times, but only draw floating text tags (`overlap · 128 blocks trimmed`) when the overlap or either participating object is **hovered or selected**, eliminating label pile-ups in dense builds.
- **`[SHOULD]` Contrast-haloed Gizmo Arms:** Draw a `1.5px` dark (`#0e1013cc`) backing stroke behind X/Y/Z gizmo lines so red/green/blue handles stay legible over grass, stone, or sky.

---

### 2.6 Toolbar & Status Bar

- **`[MUST]` Responsive 4-cluster Toolbar with `+ Add` and `Box Select`:**
  1. **Mode & Create:** `EDITOR F6` pill + `+ Add (Shift+A)` button.
  2. **Tools:** `Select (W)`, `Box (B)`, `Move (G)`, `Rotate (R)`, `Mirror (M)`.
  3. **Precision & View:** `Snap` toggle, `Pivot` (`Median` / `Active` / `BBox`), `Local (/)` toggle, `Panels (Tab)` toggle.
  4. **History & Sync:** `Undo` / `Redo`, `Push` Syncmatica pill with pending count badge, and `?` Onboarding guide button.  
  On narrow screens, the toolbar wraps cleanly into two rows rather than scrolling horizontally.  
  *ImGui implementation:* `ImGui::WindowFlags_NoDecoration | AlwaysAutoResize` floating bar using `ImGui::SameLine` with automatic width wrap check (`ImGui::GetContentRegionAvail().x`).
- **`[MUST]` Contextual 3-slot Status Bar:** Replace the truncated 9-shortcut list with **3–4 context-sensitive hints** on the left (e.g., idle: `Click select · B box · Hold RMB fly · RMB menu`; object selected: `G move · R rotate · M mirror · Shift+D dup · / local`), **live selection + world bounds (`X, Y, Z · W×H×D`)** in the center, and **Derived Cache + Syncmatica pills** on the right.

---

### 2.7 The Fly & Typed-Transform HUD

- **`[MUST]` Multi-mode Viewport HUD Pill (Top-Center / Bottom-Center):** Render via `ImDrawList` (`PANEL` at 92% alpha, `1px` `LINE` border, `6px` rounding) with three crisp states:
  1. **Typed Transform (`G` / `R`):** Mode badge (`MOVE` or `ROTATE`), **3 clickable/live axis chips `[X]` `[Y]` `[Z]`** (active axis lit in `--ax-x/y/z`), large `Barlow Condensed` numeric delta (`+12` / `−90°`) with cursor caret, plus `Enter confirm · Esc cancel`. Users can switch axes by pressing `X/Y/Z` *or* clicking the chip.
  2. **Gizmo Drag:** Compact `MOVE X/XZ  +12, 0, −4` readout + `Snapped → vent_shaft_cut` accent pill when active + `Outline preview · release to place`.
  3. **RMB Fly Mode:** `FLY` badge + `WASD move · Q/E down/up · Shift 3×` + live **speed bar (`0.2×`–`8.0×`)** + `Spectator · loading chunks` indicator (`DECISIONS.md`).
- **`[SHOULD]` Seamless Drag → Typed handoff:** If a user starts dragging a gizmo handle and types digits (e.g. `16`), lock into exact numeric offset on that axis—matching Blender's muscle memory.

---

### 2.8 Onboarding for Litematica Users Who Don't Know Blender

- **`[MUST]` Empty-State Litematica → Array 2.0 Translation Card:** When nothing is selected, replace the 2-line empty message in Properties with a compact, scannable **4-row "From Litematica?" card**:
  - *Placement* → **Object** (move with `G` or arrows, rotate 90° with `R`)
  - *Overlapping Placements* → **Front Order (`P1` wins)** (blocks behind are trimmed cleanly from Material List & Printer)
  - *Stacked Copies* → **Array Modifier** (non-destructive until *Realise*)
  - *Camera* → **Middle-mouse orbit · Hold RMB + WASD fly**
- **`[MUST]` Actionable "Loose Placements" Migration Banner:** Selecting `Loose placements` (or any migrated 1.x placement inside it) shows a plain-language explanation and a 1-click **`Convert to Project`** button (`DECISIONS.md`), explaining what a project unlocks (Arrays, Booleans, Syncmatica manifests) without forcing migration.
- **`[SHOULD]` Dismissible `? Quick Guide` Popover:** A toolbar `?` button opens a single-screen cheat sheet covering Mouse Navigation, Blender Hotkeys (`G/R/Shift+D/Alt+D/H//`), and Non-Destructive vs `Apply`/`Bake`.
- **`[COULD]` Contextual First-Use Callouts:** One-time subtle status/toast tips the first time a user triggers `Alt+click` cycling, `Local view (/)`, or creates an overlap.

---

## 3. Summary Matrix (for `mockup-gemini.html` Verification)

| Area | Key Change in `mockup-gemini.html` | Priority | ImGui Primitive |
| :--- | :--- | :--- | :--- |
| Layout | Docked rails + collapsible Top View splitter + Panels toggle | `must` | `BeginChild` + `InvisibleButton` splitter |
| Scene Graph | 3-zone fixed-rail rows, combined modifier chip, `Overlaps` view | `must` | `BeginTable` + `ImDrawList` badges |
| Properties | Multi-select batch view, `-`/`+` steppers, Boolean eyedropper | `must` | `DragInt` + `PushButtonRepeat` + `BeginChild` |
| Context Menu | 7-item root menu with `Transform/Mods/Order/View/Export` submenus | `must` | `BeginPopup` + `BeginMenu` + `MenuItem` |
| Gizmo | XZ plane handle, clean 90° ticks, start ghost + snap face highlight | `must` | World lines + `ImDrawList` overlay |
| Toolbar/Status | Wrapping 4-cluster toolbar (`+ Add`, `Box B`), contextual status bar | `must` | Floating `Begin` + auto-wrap layout |
| HUD | Axis chips on `G/R`, drag delta + snap pill, RMB fly speed bar | `must` | Foreground `ImDrawList` rounded pill |
| Onboarding | Litematica translation empty state, Loose migration CTA, `?` guide | `must` | `BeginChild` card + `BeginPopup` |
