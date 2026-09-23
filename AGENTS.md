# WorldArchive

WorldArchive is a Fabric client mod that makes dependable local backups of single-player Minecraft worlds. It writes incremental Git snapshots and standalone ZIP archives. A restore always creates a new world; the original world is never changed.

## What we can never compromise on

### 1. Never lose a world

This is a backup tool. A bug that corrupts a backup or touches the original world is worse than any missing feature. Concretely:

- Restores are copy-only. They create a new world and never write into the source world.
- The mod never deletes a backup on its own. Every cleanup goes through a user-reviewed plan, and labeled backups are always kept.
- A destination must never read the world while it can still change. All capture work goes through `SerializedBackupCoordinator` and the save gate; do not add a path that copies world files outside that flow.
- An operation that fails must leave the previous state intact. Report failure honestly rather than report success with content half-removed (this applies to remote deletes especially: if the remote refuses to stop exposing a backup, the delete failed).

### 2. Clean-room provenance

Another Git-based Minecraft backup mod exists ("Fast" + "Back", GPL-licensed). This project is an independent Apache-2.0 implementation and must stay one. Never read, copy from, or reference that mod or any GPL code. The `provenanceScan` quality gate fails the build if its name, its package path, or GPL license text appears in an implementation file. Markdown is exempt, which is the only reason this paragraph can exist.

## Glossary

- **world**: one single-player world directory, identified by a `WorldId` / `WorldIdentity`.
- **capture**: the phase that copies the world into a private folder under `capture-temp`. It reads and hashes each file while it copies it; a capture of the open world reads every file a second time.
- **save gate**: for the open world, the save before a capture and the autosave pause during it; autosave then goes back to how the player set it. The world exit backup captures after the final save instead.
- **destination** / **backend**: where a backup lands: a Git repository or a ZIP archive (`BackupBackend`).
- **snapshot**: one Git commit of a world. One repository per world; an optional remote per world.
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

A trigger in the runtime asks `SerializedBackupCoordinator` for a backup. For the open world, `LiveWorldBackups` has the server save, pauses autosave, and calls `prepareCapture`, which copies the world on a worker. When the copy is complete, autosave goes back to how the player set it, and `createPreparedBackup` queues the destination work. A world exit backup captures after the server's final save. A world that is not open goes through `createBackup`, which captures and then queues in the same way.

The coordinator lets one capture copy a world at a time and writes the backups of one world in order, while different worlds run in parallel. It never merges triggers: each trigger makes its own backup.

The capture (`FileSystemBackupCaptureFactory`) copies the world into `capture-temp` with up to four workers and hashes each file while it copies it. A capture of the open world reads and hashes every file a second time. A capture of a closed world reads a file a second time only when the file changed during the copy, or when its time is within 10 seconds of the newest file's or of the clock, or later. The caller says which kind it is (`CaptureKind`). When the world changed, it tries again and copies only the changed files. Destination work starts only after the capture is complete. Each enabled backend (`WorldGitSnapshotStore`, `ZipBackupBackend`) writes its copy from the capture and returns a `DestinationResult`. The coordinator records one `BackupRecord` in the catalog, updates the world's inventory, and deletes the capture. The UI screens render the catalog.

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

Tests are focused. Test real behavior, such as capture ordering, retention math, and catalog merges, not implementation detail or UI wiring. A change to backup, restore, or delete logic ships with a test for that behavior.

## Taste

- Immutable records for data, `final` classes, `Objects.requireNonNull` at constructor boundaries. Match this.
- Every class carries a one-line Javadoc saying what it is for. Comments describe how a thing is used, and move when the code moves.
- Keep comments in sync with changes. A stale comment is a bug.
- User-facing errors say what happened and what the user can do, in plain language.
- Update `CHANGELOG.md` for user-visible changes.

## Pull requests

`CONTRIBUTING.md` is the source of truth for scope, size, and process. For agents specifically:

- Never open a PR unless explicitly asked.
- Conventional commit titles, plain language: `fix(git): retarget main before deleting snapshot refs`.
- PR body: the problem in a sentence or two, then how you fixed it. End with the model and harness that did the work.
- One concern per PR. If the description says "also", split it.
- When babysitting a PR: poll checks and comments newer than the last push, verify each bot finding against the source, fix real ones, dismiss false positives with a written reason. Stop when the bots are green on the latest commit.
