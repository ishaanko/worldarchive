# WorldArchive

WorldArchive is a Fabric client mod that makes dependable local backups of single-player Minecraft worlds. It writes incremental Git snapshots and standalone ZIP archives. A restore always creates a new world; the original world is never changed.

## What we can never compromise on

### 1. Never lose a world

This is a backup tool. A bug that corrupts a backup or touches the original world is worse than any missing feature. Concretely:

- Restores are copy-only. They create a new world and never write into the source world.
- The mod never deletes a backup on its own. Every cleanup goes through a user-reviewed plan, and labeled backups are always kept.
- A destination must never read the world while it can still change. All capture work goes through `BackupCoordinator` and the save gate; do not add a path that copies world files outside that flow.
- An operation that fails must leave the previous state intact. Report failure honestly rather than report success with content half-removed (this applies to remote deletes especially: if the remote refuses to stop exposing a backup, the delete failed).

### 2. Clean-room provenance

Another Git-based Minecraft backup mod exists ("Fast" + "Back", GPL-licensed). This project is an independent Apache-2.0 implementation and must stay one. Never read, copy from, or reference that mod or any GPL code. The `provenanceScan` quality gate fails the build if its name, its package path, or GPL license text appears in an implementation file. Markdown is exempt, which is the only reason this paragraph can exist.

## A note from Ishaan

I build complex things as simple as possible. Use laziness as an engineering virtue: spend effort upfront to find the right abstraction, then let it save time forever. Channel yagni. Do not add machinery for a future that may not come. These instructions are good defaults, not hard rules; my explicit request overrides anything here.

## Glossary

- **world** — one single-player world directory, identified by a `WorldId` / `WorldIdentity`.
- **capture** — the short phase that copies the world into an immutable source while saves are gated.
- **save gate** — the mechanism that pauses autosave and world writes for the duration of a capture.
- **destination** / **backend** — where a backup lands: a Git repository or a ZIP archive (`BackupBackend`).
- **snapshot** — one Git commit of a world. One repository per world; an optional remote per world.
- **archive** — one ZIP file with a SHA-256 checksum.
- **manifest** — the metadata recorded with each backup, including the Minecraft version it was made with.
- **catalog** — the persistent per-world index of backup records the UI reads.
- **trigger** — what started a backup: manual, world exit, or schedule.
- **label** — a user mark that protects a backup from cleanup.
- **cleanup** — a user-confirmed deletion plan with a preview.
- **import** / **recovery** — bringing old or external backups into the catalog, and restoring or deleting from it.

## How a backup happens

A trigger in the client runtime asks `BackupCoordinator` for a backup. The coordinator serializes operations per world and coalesces compatible concurrent triggers. The capture phase copies the world under the save gate on a capture thread; only after the capture is sealed does destination work start asynchronously. Each enabled backend (Git, ZIP) writes its artifact and returns a `DestinationResult`. The result is recorded in the catalog, which the UI screens render.

## Where code lives

Two production source sets, split by Minecraft coupling:

- `src/main` — the engine. Pure Java, zero Minecraft imports. `core` (coordinator, capture, gates), `storage/git` and `storage/zip` (backends), `storage/management` (cleanup and retention), `catalog`, `model`, `config`, `importing`, `recovery`.
- `src/client` — the Fabric integration. `runtime` (lifecycle, save gates, scheduling), `ui` (screens), `settings`, `integration` (Mod Menu). New logic goes in `src/main` unless it needs a Minecraft class.
- `src/test` — plain JUnit tests against `src/main` and client logic. No Minecraft runtime in tests; keep it that way by keeping logic out of `src/client`.
- `src/main/resources/assets/worldarchive/lang` — every user-visible string. New UI text needs a lang entry, not a literal.

## Build and verify

`./gradlew build` is the whole verification story: compile, unit tests, Checkstyle, and the quality gates in `gradle/quality.gradle`. CI runs the same command, so green locally means green in CI. `./gradlew runClient` starts the game for in-game verification.

The gates enforce hard ceilings you should design within, not bump into:

- 1,000 lines per Java file, 100 lines per method, cyclomatic complexity 15.
- Every text file (including `.md` and `.yml`): no tabs, no trailing whitespace, final newline.

Tests are focused. Test real behavior — capture ordering, retention math, catalog merges — not implementation detail or UI wiring. A change to backup, restore, or delete logic ships with a test for that behavior.

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
