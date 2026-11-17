# Vitruvius API v2: scene objects of your own

For the author of Heavymatica (or any mod that has things with a position and a size). `VitruviusApi.API_VERSION` is `2`.
The 1.x API (list entries, shape actions, folder actions) is unchanged and keeps working.

## What you get

Register a `SceneObjectType` and your objects become full scene objects of the 2.0 editor:

- Add menu: **Add ▸ <your displayName>** creates one at the camera target (project, folder, assembly or Loose).
- They sit in the Scene tree (areas icon), are picked in the world and in the Top view, and are drawn by **you** (`renderOutline`).
- Properties shows your section (`drawProperties`) under the transform; the transform, rotation Y in 90 degree steps, mirror and
  an **array** modifier come from the editor.
- Move, rotate, mirror, duplicate, hide, delete and undo all work like for schematic objects. Edits you make in
  `drawProperties` are one undo step each.
- They are saved in `project.json` and in `.lasm` files as `{"type": "external", "typeId": ..., "transform": ..., "data": ..., "mods": [array]}`.

What they never do: change schematic blocks, take part in a Boolean or in the front order (`affectsBlocks()` is reserved and
`false`). A project with a type nobody registered still loads: the node is grey with a **MOD** badge, keeps its `data`
unchanged, and can still be moved, hidden and deleted.

## The types (package `dev.vitruvius.api`)

```java
public interface SceneObjectType {
    String id();                                         // "heavymatica:area", saved in project files
    String displayName();                                // the Add menu entry
    JsonObject create();                                 // default data of a new object
    Box bounds(JsonObject data);                         // LOCAL bounds in blocks, must have volume
    void renderOutline(JsonObject data, Transform world, OverlayDrawer d, boolean selected);
    void drawProperties(JsonObject data, PropertyUi ui); // data is a working copy; ui.changed() commits it
    default boolean affectsBlocks() { return false; }
}
```

`Box` and `Transform` are `dev.vitruvius.editor.core` records. A box is a block range `[min, maxExclusive)`.
`world` is the node's world transform (mirror first, then turn, then move: `world.apply(localBlock)`), and the box in the world is
`bounds(data).transformed(world)`. `renderOutline` is called every frame for every visible object and every array copy
of it, on the client thread: keep it cheap.

```java
public interface OverlayDrawer {                       // world blocks, colors 0xRRGGBB
    void box(Box box, int rgb, float alpha, boolean dashed);
    void line(double x1, double y1, double z1, double x2, double y2, double z2, int rgb, float alpha, boolean dashed);
    void fill(Box box, int rgb, float alpha);          // translucent faces, depth off
}
public interface PropertyUi {                          // no ImGui types: you do not need the UI library
    void section(String title);  void text(String t);  void note(String t);
    int intField(String label, int value);             double floatField(String label, double value);
    boolean checkbox(String label, boolean value);     String textField(String label, String value);
    int combo(String label, int index, String... options);
    boolean button(String label);                      // does not set changed()
    boolean changed();  void markChanged();
}
```

Every widget returns the (possibly edited) value and sets `changed()` when it differs.

## Registering and reading back

```java
VitruviusApi.registerObjectType(SceneObjectType type);          // client thread, at startup
List<SceneObjectView> VitruviusApi.objectsOfType(String typeId); // for your Manhattan export
```

`SceneObjectView(UUID id, String name, String cell, Transform world, JsonObject data, boolean hidden)`: one entry per placed
copy (`cell` is `"base"` or an array cell key like `"1,0,0"`), with the copy's world transform and a deep copy of the data.
Hidden objects are included with `hidden = true`; filter them if you export only what is visible. The list is empty when no
editor session runs (no world, or the editor never started), so call it when you export, not at startup.

## Example: a box area

```java
public final class AreaType implements SceneObjectType {
    @Override public String id() { return "heavymatica:area"; }
    @Override public String displayName() { return "Heavymatica area"; }

    @Override public JsonObject create() {
        JsonObject o = new JsonObject();
        o.addProperty("sx", 16); o.addProperty("sy", 8); o.addProperty("sz", 16);
        o.addProperty("name", "area");
        return o;
    }

    @Override public Box bounds(JsonObject d) {
        return Box.ofSize(new Vec3i(d.get("sx").getAsInt(), d.get("sy").getAsInt(), d.get("sz").getAsInt()));
    }

    @Override public void renderOutline(JsonObject d, Transform world, OverlayDrawer draw, boolean selected) {
        Box b = bounds(d).transformed(world);
        draw.box(b, 0xf2b35e, selected ? 1f : 0.8f, true);   // long-dash amber outline like the design says
        draw.fill(b, 0xf2b35e, 0.05f);
    }

    @Override public void drawProperties(JsonObject d, PropertyUi ui) {
        ui.section("Area");
        d.addProperty("name", ui.textField("Name", d.get("name").getAsString()));
        d.addProperty("sx", Math.max(1, ui.intField("Size X", d.get("sx").getAsInt())));
        d.addProperty("sy", Math.max(1, ui.intField("Size Y", d.get("sy").getAsInt())));
        d.addProperty("sz", Math.max(1, ui.intField("Size Z", d.get("sz").getAsInt())));
        ui.note("Manhattan walks this area when you export.");
    }
}

// in your client initializer:
if (FabricLoader.getInstance().isModLoaded("vitruvius")) {
    VitruviusApi.registerObjectType(new AreaType());
}

// in your export:
for (SceneObjectView v : VitruviusApi.objectsOfType("heavymatica:area")) {
    if (v.hidden()) continue;
    Box world = new AreaType().bounds(v.data()).transformed(v.world());   // one per array copy
    exportArea(v.name(), world);
}
```

## Rules of thumb

- Keep `data` plain JSON you own. The editor never reads it, copies it deeply when it duplicates, and writes it back as is.
- `bounds` is asked often (picking, framing, the Top view): do not allocate much, and never return an empty box. If it throws
  or returns an empty box the editor treats the object as one block so the node stays usable.
- A change of `data` through `drawProperties` moves nothing: if your size grows, the box grows around the same local origin.
- Do not call the API from other threads. Do not keep `Transform`s across ticks: ask `objectsOfType` again.
- A type id is forever: renaming it orphans every saved object (they show as MOD nodes).
