# WorldArchive

WorldArchive is a Fabric client mod for backing up single-player Minecraft
worlds. It supports incremental Git snapshots and standalone ZIP archives, and
always restores backups as new worlds without modifying the original.

## Features

- Manual backups from the backup browser or `/backup create`
- Automatic backups when leaving a world
- Optional scheduled backups
- Incremental snapshots using an isolated Git and Git LFS repository per world
- Standalone ZIP backups with SHA-256 integrity metadata
- Independent Git and ZIP destinations
- Optional per-world Git remotes over HTTPS, SSH, file, or local paths
- Support for ZIP folders synced by OneDrive, Google Drive, or similar tools
- Per-world and per-destination settings
- Backup labels, verification, remote sync, and deletion
- Partial-success reporting when one destination fails
- Safe, copy-only restores to a new world

WorldArchive does not delete backups automatically. Storage use will grow until
backups are deleted manually.

## Dependencies

- Minecraft 26.2
- Fabric Loader 0.19.3 or newer
- Fabric API 0.155.2+26.2
- Mod Menu 20.0.1 or newer
- Java 25

Git backups also require Git and Git LFS to be installed and available on
`PATH`. They are not required for ZIP-only backups. If either tool is missing,
WorldArchive disables the Git destination and leaves ZIP backups available.

## How to use

Install Fabric Loader, Fabric API, and Mod Menu, then copy the WorldArchive JAR
into the Minecraft instance's `mods` folder.

Select a world on the **Select World** screen and choose **Backups**. The backup
browser can create, restore, delete, sync, and verify backups. It also shows the
status, size, changed-file count, and available destinations for each backup.

Backups can be created in three ways:

| Trigger | Default | Behavior |
| --- | --- | --- |
| Manual | Enabled | Create a backup from the browser or with `/backup create [label]`. |
| World exit | Enabled | Create a backup after the world finishes saving and closes. |
| Scheduled | Disabled | Create a backup every 30 minutes and skip unchanged worlds. |

Triggers can be configured independently for Git and ZIP, and individual worlds
can be enabled or paused. Open settings through Mod Menu, the backup browser, or
`/backup config`.

Git and ZIP destinations are independent. If one fails while the other
succeeds, the successful copy is kept and the backup is reported as a partial
success. Failed remote Git pushes are marked **pending sync** and can be retried
later.

Git stores each world's history in its own repository. To sync to GitHub or
another server, configure a remote URL template containing `{worldId}`, such as
`https://github.com/example/minecraft-{worldId}.git`. Each matching remote
repository must already exist; WorldArchive does not create server-side
repositories or request account credentials.

Restoring a backup verifies it and creates a new, uniquely named world. The
original world is never overwritten or modified.

### Commands

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

Backup IDs may be shortened to an unambiguous prefix. Running `sync` or `verify`
without an ID processes all backups in the current scope. Deletion always
requires confirmation.

### Default paths

Paths are relative to the Minecraft instance directory, normally `.minecraft`.

| Purpose | Default path |
| --- | --- |
| Configuration | `config/worldarchive.json` |
| Per-world Git repositories | `worldarchive/git/<world-id>.git` |
| ZIP archives | `worldarchive/archives/` |
| Backup catalog | `worldarchive/catalog.json` |
| Change inventories | `worldarchive/inventories/` |
| Temporary captures | `worldarchive/capture-temp/` |
| World identity | `saves/<world>/.worldarchive/world.json` |

Destination folders must remain outside world directories. Do not edit managed
repositories or archive folders while WorldArchive is running, and do not use a
Git destination from multiple computers at the same time. Configurations from
older releases retain their shared Git repository as read-compatible legacy
storage; WorldArchive never moves or deletes it during migration.

## Building the mod

Install a Java 25 JDK and set `JAVA_HOME` to it. Git is required by the project
checks. Git LFS is recommended for full integration-test coverage.

On Windows:

```powershell
.\gradlew.bat --no-daemon check build
```

On Linux or macOS:

```sh
./gradlew --no-daemon check build
```

The build runs the test suite, Checkstyle, formatting checks, the license check,
and the provenance scan. Generated JARs are written to `build/libs/`.

## License

Copyright 2026 Ishaan Kothari.

WorldArchive is licensed under the [Apache License 2.0](LICENSE).

## Contributions

Bug reports and pull requests are welcome. For larger changes, open an issue
first to discuss the proposed approach.

Before submitting a pull request, run the full project checks:

```sh
./gradlew --no-daemon check build
```
