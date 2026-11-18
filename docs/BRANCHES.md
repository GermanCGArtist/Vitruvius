# Branches and version history (design and two-player test sheet)

Branch `feat/branches` (worktree `F:\workflow\Vitruvius-branches`). Unit tests: `sync/history/HistoryTest`. **Not yet tried in game.**

## Design

- **Commit** = one manifest placement with payload v2: `commit` id, `parents`, `branch`, `draft`, `msg`, plus the scene (project.json without per-player fields) and `cells` (slot -> Syncmatica placement id of the cell file). A commit points to cell files, it does not copy them; cells that did not change are shared by all commits.
- **Names** (what the access mod reads): main = `[LA2] <project>` (same as before, version 1 manifests read as main), other branch = `[LA2:<branch>] <project>`, cells `[<project>] <object>` unchanged.
- **Branch** = a name in the commits. Head = newest commit of the branch that no other commit of the branch lists as parent.
- **Draft**: every push replaces one draft manifest per branch and player (auto-push as before). **Commit** (History window) seals it with a message: the manifest is written again with `draft=false`, a push never removes it, the next change starts a new draft on top.
- **Default branch**: the player who shares a project pushes to `main`; a player who opens a shared project (or pushes a project somebody else already pushed) gets a branch named after them. Live sharing only merges manifests of the *same branch*; other branches arrive through **Merge**.
- **Merge** B into the checked-out branch A: `ProjectMerge` with the merge base of both heads, same winner rule per cell file; result is applied to the open project and committed ("Merge B into A", two parents). Fast-forward and already-up-to-date are detected.
- **Restore**: the project goes back to the chosen commit and that state is committed as a new commit on the checked-out branch (history stays linear, nothing lost). **Switch** checks out another branch's head (needs nothing unpushed). **New branch** starts at the current state; uncommitted changes move with it.
- **Storage**: a push no longer removes a replaced cell file that another commit lists (`Retention.referenced`). The History window's *Clean up* drops sealed commits older than N days (never branch heads) or deletes a branch's own commits and removes the placements nothing else lists; it shows the numbers first. Drafts are overwritten, so only sealed commits grow the history.
- **Access mod** (`F:\workflow\VitruviusAccess`, commit 63ee414): branch manifests now resolve to the project group (before, `[LA2:x] P` would have been read as a cell of project `la2:x` and stayed public). Writing, moving or removing `[LA2] <project>` needs owner or op, edit access covers branches and cells. Needs the new access jar on the server.

Limits worth knowing: files fetched by merge/restore are copied into the project and pushed again as the player's own cells (a duplicate upload, no corruption); an old commit whose cell files were cleaned up restores without them (the toast says how many); two players writing the same branch at once show as "(diverged)" and the newer head wins until one merges.

## Two-player test sheet (A = owner, B = player with edit access, both with the new mod; server has the new access mod)

Prep: server `/vacl <project> claim` by A, `/vacl <project> edit add B`. A has a project with two objects, auto-push on.

1. **Share**: A shares the project. Server placement list shows `[LA2] <project>`. History window (Windows menu): branch `main`, one entry "working changes".
2. **Commit**: A types "first version" and presses Commit. Log shows the commit with message; Commit button is disabled until A edits something. Server: the `[LA2] <project>` placement stays when A edits again, plus a second one for the new draft.
3. **B joins**: B opens the project from "Shared projects". Window shows `on <B's name>`. B moves an object and presses Commit "B moves wall". Server shows `[LA2:<B>] <project>` manifests. A sees nothing change in the scene (different branch) but B's branch appears in A's History window.
4. **B cannot write main**: B uses Switch to main, edits, waits for auto-push: refusal toast ("Only the owner ... may write its main branch"). B switches back.
5. **Merge**: A selects B's branch, Merge into main. Toast "Merge <B> into main", B's move appears in A's scene, a merge commit with two parents ("(merge)") is in the log.
6. **Both edit one object** (A on main, B on own branch, same object differently), A merges: toast says "N edited by both"; A and B both merging the other way end with the same scene.
7. **Restore**: A selects the first commit, Restore this state: scene returns to the first version, a new commit "Restore ..." appears on top; earlier commits still listed.
8. **New branch**: A makes a branch "experiment", edits, commits; Switch to main restores the main scene; Switch to experiment brings the edit back.
9. **Storage**: after steps 1-8 the server has the cell files of every state. Clean up (days is at least 1, so run it the next day, or edit the commit times) removes old commits and only the files nobody lists; heads stay.
10. **Old clients / legacy**: a project pushed with the previous version still opens ("Shared projects") and counts as main.

Report anything that differs; especially steps 3, 5 and 7 touch the file copy path.
