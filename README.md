# WorldArchive

WorldArchive is a Fabric client mod for backing up single-player Minecraft
worlds. It supports incremental Git snapshots and standalone ZIP archives, and
always restores backups as new worlds without modifying the original.

## Features

- Manual backups from the backup browser
- Automatic backups when leaving a world
- Optional scheduled backups
- Incremental snapshots using an isolated Git and Git LFS repository per world
- Standalone ZIP backups with SHA-256 integrity metadata
- Independent Git and ZIP destinations
- Optional per-world Git remotes over HTTPS, SSH, file, or local paths
- Support for ZIP folders synced by OneDrive, Google Drive, or similar tools
- Optional per-world ZIP folder overrides
- Per-world and per-destination settings
- Backup labels, verification, remote sync, and deletion
- Previewed recovery of existing WorldArchive Git histories
- Copy or read-only link import for folders of WorldArchive ZIP archives
- Offline catalog and Git-ref rebuilding from managed backup storage
- Recovery-only access to backups for worlds whose save folders are gone
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

Open **Mods**, select **WorldArchive**, and choose its configuration button.
The **World Backups** screen can create,
restore, delete, sync, and verify backups. It also shows the status, size,
changed-file count, and available destinations for each backup.
You can also select a world in **Singleplayer** and choose **Backups** to open
that world's backup history directly.

Backups can be created in three ways:

| Trigger | Default | Behavior |
| --- | --- | --- |
| Manual | Enabled | Create a backup from the browser. |
| World exit | Enabled | Create a backup after the world finishes saving and closes. |
| Scheduled | Disabled | Create a backup every 30 minutes and skip unchanged worlds. |

Triggers can be configured independently for Git and ZIP, and individual worlds
can be enabled or paused. Open WorldArchive through Mod Menu, then choose
**Settings** from the backup browser.

Git and ZIP destinations are independent. If one fails while the other
succeeds, the successful copy is kept and the backup is reported as a partial
success. Failed remote Git pushes are marked **pending sync** and can be retried
later.

Git stores each world's history in its own repository. To sync a world to
GitHub or another server, open the **Worlds** settings tab, choose that world,
and paste the clone URL of an existing empty repository into **Git remote**.
WorldArchive creates a short six-character world code automatically; users do
not need to find, copy, or add it to the URL. WorldArchive does not create
server-side repositories or request account credentials.

The **ZIP** tab defines the default archive folder. The **Worlds** tab lists
multiple worlds at once and lets any selected world inherit that default or use
a separate ZIP root, such as a different drive or synced folder.

Restoring a backup verifies it and creates a new, uniquely named world. The
original world is never overwritten or modified.

### Recover existing backups

Open WorldArchive through Mod Menu, then choose **Import**, to recover backup history after a
reinstall, profile move, catalog loss, remote rename, or computer migration.
Every repository, folder, and stored-backup import is scanned first and shown
as a list where you choose the exact backups to import. Identical records merge
safely; a conflicting backup
identity is reported and never overwrites the existing catalog or artifact.

For repository histories, paste any supported credential-free HTTPS, SSH, Git,
file, or absolute local repository location. **Repository files: Copy to this
device** copies and verifies the selected Git and Git LFS objects in
WorldArchive's managed storage. **Repository files: Keep in repository** keeps
only pinned local metadata and downloads required Git LFS objects from the
source when verifying or restoring. Imports are recovery-only; configure a
world's repository separately in Settings for future backups. WorldArchive
never contacts imported repositories automatically at startup.

For backup folders, **Folder files: Copy into WorldArchive** publishes verified
archives into managed storage. **Folder files: Leave in selected folder** keeps
archives where they are and records a pinned checksum and relative path.
Deleting a linked backup only removes WorldArchive's catalog link—the source ZIP
is untouched.

Use **Find Stored Backups** to recover catalog entries and missing Git snapshot
refs from local managed repository histories and ZIP archives. This search is
local-only and does not fetch from a network.

### Default paths

Paths are relative to the Minecraft instance directory, normally `.minecraft`.

| Purpose | Default path |
| --- | --- |
| Configuration | `config/worldarchive.json` |
| Per-world Git repositories | `worldarchive/git/<world-id>.git` |
| ZIP archives | `worldarchive/archives/` |
| Backup catalog | `worldarchive/catalog.json` |
| Import source registry | `worldarchive/import-sources.json` |
| Deleted-backup registry | `worldarchive/deleted-backups.txt` |
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
