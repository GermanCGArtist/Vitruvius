# M1 in-game checks: Litematica adapter, reconciler, project manager (WP 1.3)

Build the jar, install it as the architect does, then start Minecraft 26.1.1 with Litematica 0.27.14. There is no UI for projects yet (WP 1.4), so steps that need a project use a hand-written `project.json` and a debug call (`Reconciler.projects().open(path)`). Watch the game log for lines starting with `[SchematicArray]`.

Keep a copy of `config/schemarray/` (folders.json, arrays.json) and of the world before the first run.

## A. Old behaviour with the editor unused
1. Join a world that has several placements, one of them with a 1.x array (for example 3 x 1 x 1) and one in a folder. Wait 3 seconds.
   - Expect: nothing visibly changes, the array copies are there, the placement list looks as before (copies are not listed).
2. Move the array's original with Litematica's keys. Expect the copies follow at once.
3. Open the array config of the original and change the count from 3 to 4. Expect a 4th copy appears, and no error in the log.
4. Hide a folder in the list and show it again. Expect the placements stop rendering and render again (copies too).
5. Delete the original in the list. Expect its copies disappear too and the entry leaves the list.
6. Check `config/schemarray/arrays.json` and `folders.json`: the editor never writes them (edits made through the 1.x screens are fine).

## B. First-run migration
1. Delete `config/schemarray/editor-session.json` (and the `editor-loose` folder) and join the world from step A.
   - Expect: after about 2 seconds, `editor-session.json` has `"migrated": true` for this world, and `editor-loose/<world>-<hash>.lasm` exists with one object per placement (the array as modifiers).
   - Expect: no copies are created twice, nothing is removed, the picture is the same.
2. Move the original of the array in Litematica. Expect the copies follow (now done by the reconciler, not ArraySync).
3. Change the array count in the 1.x config screen. Expect the copies follow, and the change is in the `.lasm` after at most 30 s.
4. Leave the world and join again. Expect the same picture, no duplicate placements, no migration line in the log.
5. Downgrade check: put the 1.6.x jar back and join. Expect the arrays and folders work as before (the config files were never changed).

## C. Opening and closing a project
1. Create `schematics/Projects/Test/project.json` with one object (file `files/a.litematic`, copy a small schematic there), `"world"` set to your world key (`sp:<save name>`) and `"dim": "minecraft:overworld"`. Open it.
   - Expect: the placement appears at its position within a second, and one more per cell if the object has an Array modifier.
2. Close it. Expect all its placements disappear, and `project.json` is written.
3. Re-open it. Expect the placements come back with the same hash ids (see `placementIds` in `project.json`).
4. Open it, leave the world, join again. Expect it is open again and the placements are there once.
5. Go to the Nether (or any other dimension). Expect the project's placements are removed; come back, they return.
6. Open a project whose `world` is another save. Expect no placements in this world.

## D. Drift
1. With a project open, move its object's placement with Litematica's move keys. Expect the placement stays where you put it (it is not pulled back), and after at most 30 s the `origin`, `rot` and `mirror` in `project.json` follow.
2. Rotate and mirror it from the placement config screen. Same expectation.
3. Move one of the array copies. Expect it jumps back to its cell within a tick (only the base is adopted).
4. Edit `project.json` by hand while the project is closed, then open it. Expect the placement appears at the new position (the file wins).
5. Delete the base placement of a project object in the Litematica list. Expect it is made again at once, with the same id.
6. Delete the placement of a Loose (migrated) object. Expect the object stays in the model (flagged missing, not recreated, copies untouched) and is not removed from the `.lasm`.

## E. Budget and rebuilds
1. Give an object an Array modifier of 500 copies (use a small schematic). Open the project.
   - Expect: the copies appear over about 8 ticks (64 per tick), no long freeze, and after about a second all chunks render (no missing chunks on any copy).
2. After joining a world with many managed placements, wait 10 seconds. Expect no copy has holes (the delayed rebuilds at 2 s and 10 s fix them).

## F. Linked assemblies
1. Write `files/tower.lasm` (see `AssemblyIo`) with two parts, and put two AssemblyNodes with `source: link files/tower.lasm` at different positions in `project.json`. Open it.
   - Expect: two sets of placements, one per instance, at the composed positions.
2. Edit the `.lasm` (move a part) and save it. Expect both instances update within a second.
3. Move a linked placement in Litematica. Expect it is put back (linked parts are read only).
4. Quit; check `project.json`: it holds no placement ids for the linked parts.

## G. Robustness
1. Replace the schematic file of a project object while the game runs. Expect no crash and no placement lost; Litematica may keep showing the old copy (known limitation).
2. Corrupt `project.json` of a remembered project and join. Expect one log line, the other projects open, the world loads normally.
3. Put garbage into `editor-session.json`. Expect it is ignored and rewritten.
4. Check the log after each section for `editor reconciler stopped`. If it appears, the 1.x behaviour continues; report the stack trace.
