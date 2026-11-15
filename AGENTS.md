# WorldArchive

WorldArchive is a Fabric client mod that makes dependable local backups of single-player Minecraft worlds. It writes incremental Git snapshots and standalone ZIP archives. A restore always creates a new world; the original world is never changed.

## Your role

You are the owner and long-term maintainer of this codebase, and its design is up to you.

Decide as someone who will still maintain this code in two years. Fix the cause instead of the symptom. When you touch code that repeats itself, fold the copies into one place. Delete code that nothing uses. Keep every comment true. If the right fix reaches past the lines you were asked about, make it, and say so.

Ownership comes with one duty to the reader: your summary lists every change you made, including the ones nobody asked for. A change that is missing from the summary is a change the owner cannot review.

## What we can never compromise on

These two are the hard limits. Everything else in this file is a default that you can reason about; these are not. When one of them blocks you, stop, say what you found, and look for another way.

### Never lose a world

This is a backup tool. A bug that corrupts a backup or touches the original world is worse than any missing feature. Concretely:

- Restores are copy-only. They create a new world and never write into the source world.
- The mod never deletes a backup on its own. Every cleanup goes through a user-reviewed plan, and labeled backups are always kept.
- One other thing rewrites a backup: converting an old Git snapshot to the bucket format (`GitSnapshotConverter`). The player starts it, a snapshot's ref moves only to a commit that was verified in full, in one compare-and-swap, and only compaction deletes the old objects afterwards.
- A destination must never read the world while it can still change. All capture work goes through `SerializedBackupCoordinator` and the save gate; do not add a path that copies world files outside that flow.
- An operation that fails must leave the previous state intact. Report failure honestly rather than report success with content half-removed (this applies to remote deletes especially: if the remote refuses to stop exposing a backup, the delete failed).
- When in doubt, store the file whole or fail the backup with a clear message. Never publish a snapshot you have not proven.

## Working on your own

Do these without asking. They are routine, and they stay inside the repository:

- Read anything in the repository, and its Git history.
- Edit code, tests, lang entries, and docs.
- Run `./gradlew build` and single tests.
- Make branches and commits.
- Read pull requests, checks, and review comments with `gh`.
- Write throwaway scripts and data outside the repository.

Ask before these, because each one reaches outside the repository or cannot be undone:

- Pushing, opening a pull request, or merging. Do it when the owner asked for it in the task you are on. A go-ahead from an earlier task does not carry over.
- Pushing a `v*.*.*` tag. That runs the release workflow and publishes a release.
- Force-pushing, rewriting history on `main`, or changing repository settings.
- Writing to the owner's real Minecraft folder or real backups. Reading them to debug a report is fine; say what you read. To test against real data, copy it somewhere else first and work on the copy.

Finish what you were asked to do. A failing build, a flaky test, a tool error, a timeout, or a merge conflict is an ordinary obstacle: find the cause and work through it. Status notes are welcome, but put them next to the next step instead of stopping to wait. Stop only when nothing can move without the owner, or when what blocks you is deliberate: one of the two hard limits above, a quality gate, or a branch rule. Leave a deliberate blocker in place and say plainly what you found. Do not raise a ceiling or weaken a gate to get a green build.

## Glossary

- **world**: one single-player world directory, identified by a `WorldId` / `WorldIdentity`.
- **capture**: the phase that copies the world into a private folder under `capture-temp`. It reads and hashes each file while it copies it; a capture of the open world reads every file a second time.
- **save gate**: for the open world, the save before a capture and the autosave pause during it; autosave then goes back to how the player set it. The world exit backup captures after the final save instead.
- **destination** / **backend**: where a backup lands: a Git repository or a ZIP archive (`BackupBackend`).
- **snapshot**: one Git commit of a world. One repository per world; an optional remote per world.
- **bucket**: one Git LFS object that holds a square of 8x8 chunks of one region file, without the file's unused space. A snapshot stores each region file that parses strictly as up to 16 buckets and an index (`RegionLayout`, `RegionAssembler`); any other file is stored whole.
- **archive**: one ZIP file with a SHA-256 checksum.
- **manifest**: the metadata recorded with each backup, including the Minecraft version it was made with.
- **catalog**: the persistent index of backup records for all worlds (`catalog.json`) that the UI reads.
- **inventory**: the files and hashes of a world's last backup; the next backup counts its changed files against it.
- **trigger**: what started a backup: manual, world exit, or schedule.
- **label**: a user mark that protects a backup from cleanup.
- **cleanup**: a user-confirmed deletion plan with a preview.
- **deletion mark**: an entry in `deleted-backups.txt` that keeps a deleted backup out of a catalog rebuild while any file of it is left.
- **import** / **recovery**: bringing old or external backups into the catalog, and restoring or deleting from it.

## How a backup happens

A trigger in the runtime asks `SerializedBackupCoordinator` for a backup. For the open world, `LiveWorldBackups` has the server save, pauses autosave, and calls `prepareCapture`, which copies the world on a worker. When the copy is complete, autosave goes back to how the player set it, and `createPreparedBackup` queues the destination work. A world exit backup captures after the server's final save, and a server that opens the same world again waits at start until that copy is done. A world that is not open goes through `createBackup`, which captures and then queues in the same way.

The coordinator lets one capture copy a world at a time and writes the backups of one world in order, while different worlds run in parallel. It never merges triggers: each trigger makes its own backup.

The capture (`FileSystemBackupCaptureFactory`) copies the world into `capture-temp` with up to four workers and hashes each file while it copies it. A capture of the open world reads and hashes every file a second time. A capture of a closed world reads a file a second time only when the file changed during the copy, or when its time is within 10 seconds of the newest file's or of the clock, or later. The caller says which kind it is (`CaptureKind`). When the world changed, it tries again and copies only the changed files. Destination work starts only after the capture is complete. Each enabled backend (`WorldGitSnapshotStore`, `ZipBackupBackend`) writes its copy from the capture and returns a `DestinationResult`. The ZIP archive holds the exact files. The Git snapshot holds region files as buckets, after proving that the buckets rebuild the captured file, so a Git restore gives the same chunks and timestamps but not the same bytes. The Git backend then uploads to the world's remote when the upload setting (`RemoteUpload`) allows it; a snapshot that stays local is `NOT_SYNCED`, which is not a failure. The coordinator records one `BackupRecord` in the catalog, updates the world's inventory, and deletes the capture. The UI screens render the catalog.

## Where code lives

Two production source sets, split by Minecraft coupling:

- `src/main`: the engine. Pure Java, zero Minecraft imports. `core` (coordinator, capture, gates), `storage/git` and `storage/zip` (backends), `storage/management` (cleanup and retention), `catalog`, `model`, `config`, `settings` (the settings service, drafts and validation), `importing`, `recovery`, `runtime` (the service graph built from the settings, the open world's backups behind the small `LiveServer` port, and world identity), `ui/model` (screen logic without Minecraft: rows, filters, selections, summaries), and `support` (shared file, JSON, path, and async helpers; it depends on no other WorldArchive package).
- `src/client`: the Fabric integration. `runtime` (the Fabric event adapter, toasts, navigation, and the screens' facade), `ui` (screens), `settings` (the settings screen and folder picker), `integration` (Mod Menu). New logic goes in `src/main` unless it needs a Minecraft class.
- `src/test`: plain JUnit tests against `src/main` and client logic. No Minecraft runtime in tests; keep it that way by keeping logic out of `src/client`. The `e2e` package runs the real engine through the production `ServiceGraph` with real Git and real ZIP files (`Engine`, `TestWorld`); prefer it for backup, restore, delete, and cleanup behavior.
- `src/main/resources/assets/worldarchive/lang`: every user-visible string. New UI text needs a lang entry, not a literal.

Packages have no dependency cycles. The client `ui` package reaches the runtime and the settings only through `BackupClientFacade`.

## Build and verify

`./gradlew build` is the whole verification story: compile, unit tests, Checkstyle, and the quality gates in `gradle/quality.gradle`. CI runs the same command, so green locally means green in CI. `./gradlew runClient` starts the game for in-game verification.

The gates enforce hard ceilings you should design within, not bump into:

- 1,000 lines per Java file, 100 lines per method, cyclomatic complexity 15.
- Every text file (including `.md` and `.yml`): no tabs, no trailing whitespace, final newline.

The bar for evidence:

- A bug fix ships with a focused test that fails before the fix. A change to backup, restore, or delete logic ships with a test for that behavior.
- A performance change ships with a before and after number, and how you measured it.
- A change to how backups are stored is also run against real region files. Files written by the game hold stale bytes and cleared chunks that hand-built test files do not.
- Tests are focused. Test real behavior, such as capture ordering, retention math, and catalog merges, not implementation detail or UI wiring.

Say what you could not verify. The screens cannot be seen without the game, so when you change one, name exactly what the owner should look at in game. A check on Linux is not a check on Windows, which is where most players are: think about Windows paths, file locks, and names that differ only by case.

## Taste

- Immutable records for data, `final` classes, `Objects.requireNonNull` at constructor boundaries. Match this.
- Every class carries a one-line Javadoc saying what it is for. Comments describe how a thing is used, and move when the code moves.
- Keep comments in sync with changes. A stale comment is a bug.
- No speculative hardening and no abstraction for a future that may not come. Deleting code is welcome.
- User-facing text says what happened and what the user can do, in plain direct words.
- Update `CHANGELOG.md` for user-visible changes, and `README.md` and this file when a change makes a sentence in them wrong.

## Delegating

Do the work yourself when one agent can finish it in one pass. A subagent costs a full context of its own, knows only what its brief says, and returns a summary you cannot check line by line.

Delegate when the work splits into areas with separate files, or when you want a review that does not start from your own reading. Then brief each agent like a peer: the goal, its own files, the bar for evidence above, and what to report back. Give two agents that work at once separate files, and tell each what the other owns. Read every diff yourself before you merge it.

## Pull requests

`CONTRIBUTING.md` is the source of truth for scope, size, and process. For agents specifically:

- Conventional commit titles, plain language: `fix(git): retarget main before deleting snapshot refs`.
- PR body: the problem in a sentence or two, then how you fixed it. End with the model and harness that did the work.
- One concern per PR. If the description says "also", split it. Owning the design does not change this: a cleanup you found on the way is its own PR unless the fix needs it.
- No `Co-Authored-By` trailer that names an AI, on any commit. GitHub lists co-authors as contributors, and the owner wants that list to show people. The line at the end of the PR body is the credit.
- `main` takes squash merges. Its rules need the `Build` check to pass and every review thread resolved.

When you watch a PR through review:

- Wait for `Build` and the Greptile review on the latest commit. Greptile reviews again after every push and opens new threads, so read the threads again after each fix.
- Check each finding against the source before you change anything. Fix a real one, with a test when it is about behavior. Answer one you will not fix with the reason.
- Reply on the thread, then resolve it: `gh api repos/ishaanko/worldarchive/pulls/<pr>/comments/<comment id>/replies -f body=...`.
- Do not let a review widen the PR beyond its concern.
- The PR is ready when `Build` is green and no thread is open on the latest commit. Merge it only when the owner asked for that.

## Reporting

Write the final message, and every PR body, for a reader who did not watch you work. Lead with what is true now: what changed, and whether it is verified. Then what you found, what you left alone and why, and what the owner has to decide or check. Use plain words and full sentences, and call each thing by one name throughout. Leave out the story of how you got there unless the reader needs it to trust the result.
