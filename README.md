# WorldArchive

WorldArchive is an independently developed Fabric client mod for dependable
single-player world backups. It writes each saved world state to independent
Git/Git LFS and ZIP destinations, reports partial failures without discarding a
successful copy, and restores only into a new world.

## Requirements

- Minecraft 26.2
- Fabric Loader 0.19.3 or newer
- Fabric API 0.155.2+26.2
- Java 25
- Mod Menu 20.0.1 or newer (required)
- System Git and Git LFS on `PATH` to use the Git destination

Git and Git LFS are optional if only ZIP backups are wanted. Both tools must be
available for Git backups. If either is missing at startup, WorldArchive disables
Git with a persistent warning and an enabled ZIP destination can still succeed.
If a previously healthy tool disappears during a backup, Git fails visibly so
a successful ZIP copy is reported as partial success rather than full success.

## Install

Install Fabric Loader, Fabric API, and Mod Menu for Minecraft 26.2. Copy the
WorldArchive JAR into the instance's `mods` directory. To enable Git snapshots,
also install Git and Git LFS for the operating system before starting Minecraft.

This repository has not published a release yet. To build the current source,
follow [Building and verification](#building-and-verification), then use the
non-sources JAR from `build/libs/`.

## Backup behavior

WorldArchive briefly gates a live integrated-server save while it makes one
private, immutable filesystem capture. Every enabled destination consumes that
same capture after the world is safe to resume.

| Trigger | Default | Behavior |
| --- | --- | --- |
| Manual | On | `/backup create [label]` or **Create** in the browser first saves the active world, then captures it. |
| World exit | On | Arms on the final save, then captures the quiescent world immediately after the integrated server stops and before client shutdown finishes. |
| Scheduled | Off | Starts at 30 minutes when enabled, skips an unchanged world, and does not replay missed runs after Minecraft was closed. |

Trigger settings can be changed globally, per destination, and per discovered
world. Git and ZIP are enabled independently. A failure in one produces a
partial-success result when the other remains durable. WorldArchive never
auto-deletes backups; explicit deletion is always confirmed, so storage use can
grow over time.

## Storage and configuration

Paths are relative to the Minecraft instance's game directory (normally
`.minecraft`):

| Purpose | Default path |
| --- | --- |
| Configuration | `config/worldarchive.json` |
| Shared bare Git repository | `worldarchive/worldarchive.git` |
| ZIP archive root | `worldarchive/archives/` |
| Catalog and change inventories | `worldarchive/catalog.json` and `worldarchive/inventories/` |
| Temporary private captures | `worldarchive/capture-temp/` |
| Stable world identity | `saves/<world>/.worldarchive/world.json` |

The Git destination stores parentless snapshot commits under WorldArchive-owned
refs in one bare repository; it never places a `.git` directory in a world.
Repository-local Git LFS rules cover common large Minecraft files (`*.mca`,
`*.mcr`, `*.dat`, `*.dat_old`, `*.nbt`, and `*.zip`) and can be configured.

An optional credential-free HTTPS, SSH, file, or local Git remote can mirror
snapshots. Authentication remains with Git Credential Manager or existing SSH
configuration; WorldArchive does not store tokens. If the remote push or LFS
upload fails, the local snapshot remains successful and is marked **pending
sync** for a later **Sync** action or `/backup sync`.

ZIP archives are independent of Git, carry SHA-256 integrity metadata, and are
published atomically only after completion. The archive root can be any safe
local folder, including a OneDrive or Google Drive desktop-synced folder.
WorldArchive uses the filesystem only and does not call cloud-provider APIs.

Open settings from Mod Menu, **Settings** in the backup browser, or
`/backup config`. The native screen provides destination and trigger controls,
health checks, per-world enablement, editable absolute paths, and a native
folder picker where supported. A destination root cannot be changed while the
catalog still contains durable backups at that destination; delete those
backups first. Editing destination paths directly in the JSON configuration
while Minecraft is closed is unsupported because it bypasses this safeguard.
Unsafe stored destination paths put the runtime into a visible safety-blocked
state, and no backup starts until the conflict is corrected. Safe settings and
newly discovered or restored world paths take effect without restarting the
client.

## Browser and commands

Select a valid world on the vanilla Select World screen, then choose
**Backups**. The browser shows the date, optional label, trigger, Git and ZIP
availability, remote-sync and verification states, logical size, and changed
file count. It provides **Create**, **Restore**, **Delete**, **Sync**,
**Verify**, **Open Folder**, and **Settings** actions with progress and
confirmation screens.

The complete client-side command tree is:

```text
/backup
/backup create [label]
/backup list [page]
/backup gui
/backup restore <id>
/backup delete <id>
/backup sync [id]
/backup verify [id]
/backup status
/backup config
```

`/backup` opens a clickable help/status panel. Backup IDs have suggestions and
may be entered as unambiguous prefixes. Omitting the optional ID from `sync` or
`verify` processes all backups in the current command scope. Delete opens a
confirmation instead of deleting immediately.

## Restore and safety

Restore verifies a destination against the catalog manifest, materializes a
private staging copy, and atomically publishes a uniquely named world folder.
The original world is never replaced or modified. The restored copy receives a
fresh UUID in `.worldarchive/world.json` together with the source backup ID, then
can be selected on the world list or opened immediately. When restoring from
the active world, WorldArchive fully saves and disconnects that world before it
navigates to the restored copy, so the original session lock is released first.
If the local Git repository was lost but the configured remote still has the
snapshot, WorldArchive verifies the remote manifest against the catalog before it
installs the exact local ref and restores the copy.

WorldArchive rejects unsafe destination overlap, path traversal, symbolic links,
Windows reparse points, special files, path collisions, and filesystem identity
changes detected during capture or restore. Operations are serialized with
process and filesystem locks. Do not edit a managed repository or archive tree
while WorldArchive is running, and do not share a Git repository between
concurrent writers. If Git reports a stale lock, WorldArchive leaves it in place;
first confirm that no Git, Minecraft, or WorldArchive process is using the
repository before following normal Git recovery guidance.

Remote URLs must not contain credentials, and user-visible process errors are
redacted. Destination paths must remain outside all world directories.

## Limitations

- Version 0.1.0 is client-only and supports integrated single-player worlds on
  Fabric 26.2; dedicated servers and NeoForge are not supported.
- Restore is copy-only. There is no in-place world replacement.
- There is no migration from other backup mods or foreign backup formats.
- Cloud support is limited to existing desktop-synced folders; there are no
  OneDrive or Google Drive API integrations.
- A Git repository inside a synced folder is supported only as
  single-machine, single-writer storage.

## Building and verification

Install a Java 25 JDK and point `JAVA_HOME` to it. Git is also required by the
repository quality tasks; Git LFS should be installed for maximum integration
test coverage. The Gradle wrapper downloads the pinned Gradle distribution.

```powershell
.\gradlew.bat --no-daemon check build
```

```sh
./gradlew --no-daemon check build
```

`check` runs unit and integration tests, Checkstyle, formatting checks, the
Apache-2.0 license check, and the clean-room provenance scan. `build` compiles
the Fabric client source set and writes the mod and sources JARs to
`build/libs/`. The initial implementation has also completed a Minecraft client
smoke covering settings, manual Git and ZIP capture, the browser, both restore
continuations, and final world-exit capture. Repeat that smoke before publishing
a release.

## Clean-room development and license

WorldArchive is a clean-room implementation: reference behavior was studied
separately, and no reference source, identifiers, or license headers are
tracked in this project. Production code uses package
`dev.ishaankot.worldarchive` and mod ID `worldarchive`.

Copyright 2026 Ishaan Kothari. Licensed under the
[Apache License 2.0](LICENSE).
