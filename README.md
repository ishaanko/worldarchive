# WorldArchive

WorldArchive is a Fabric client mod that backs up your single-player Minecraft
worlds, as incremental Git snapshots, standalone ZIP archives, or both. A
restore always creates a new world, so your original world is never touched.

## Features

- Backups on demand, on world exit, or on a schedule
- One Git repository per world, with an optional remote to push to
- ZIP archives with SHA-256 checksums, fine to keep in OneDrive or Google Drive
- Labels, verification, and a storage screen that forecasts disk use
- Guided cleanup with a preview; labeled backups are always kept
- Import of Git repositories and ZIP folders after a reinstall or a move
- The Edit World screen's backup buttons use WorldArchive instead of vanilla's

WorldArchive never deletes a backup on its own. You review and confirm every
cleanup.

## Requirements

- Minecraft 26.3
- Fabric Loader 0.19.3 or newer
- Fabric API 0.160.5 or newer
- Java 25
- Mod Menu (optional)
- Git 2.29 or newer and Git LFS on `PATH`, for Git backups only

Without Git or Git LFS, the Git destination turns off and ZIP backups still
work.

## Quick start

1. Install Fabric Loader and Fabric API.
2. Put the WorldArchive JAR in your `mods` folder.
3. Click the **World Backups** icon on the title screen, or select a world in
   **Singleplayer** and click **Backups**.

From there you can back up, restore, verify, sync, import, and delete. Open
**Settings** from the backup browser to pick destinations and triggers.

## Triggers

| Trigger | Default | What it does |
| --- | --- | --- |
| Manual | On | Backs up when you click. |
| World exit | On | Backs up after the world saves and closes. |
| Scheduled | Off | Backs up every 30 minutes while you play, and skips a world that has not run since its last backup. |

Git and ZIP have their own trigger settings, and you can pause single worlds.

## Git remotes

To sync a world to GitHub or another server, create an empty repository there,
open the **Worlds** settings tab, select the world, and paste the clone address
into **Git remote**. These forms work:

- `https://host/path/world.git`
- `ssh://user@host/path/world.git` (a port such as `host:2222` is fine)
- `user@host:path/world.git`
- `file:///path/to/world.git`, or a local path like `D:\Backups\world.git`

The address can't hold a password or token. Git signs in with its credential
helper or your SSH key, and WorldArchive never stores credentials. A failed
push leaves the backup marked **pending sync** until you retry.

Backups upload as they are made. To upload less often, set **Upload to remote**
on the Git settings tab to **Once a day** or **Only with Sync**; **Sync** then
uploads the backups you select. This matters on GitHub, which keeps every Git
LFS file you ever uploaded until you delete the repository, and whose free plan
holds 10 GiB of them.

## What Delete, cleanup, and restore touch

- **Delete** removes every copy of a backup: the ZIP, the local Git copy, and
  the copy on the remote. If the remote refuses, the Git copies stay and the
  delete reports a failure.
- **Cleanup** frees space on this computer only. It never touches the remote
  and never deletes a labeled backup.
- **Restore** copies a backup into a new world. It warns when the backup comes
  from a different Minecraft version, but never blocks. A ZIP restore is byte
  for byte. A Git restore has every chunk, but the unused space inside region
  files is not kept.

## Where files live

Paths are relative to the Minecraft instance folder, usually `.minecraft`.

| What | Path |
| --- | --- |
| Settings | `config/worldarchive.json` |
| Git repositories | `worldarchive/git/<world-id>.git` |
| ZIP archives | `worldarchive/archives/<world-id>/` |
| Backup catalog | `worldarchive/catalog.json` |
| World identity | `saves/<world>/.worldarchive/world.json` |

Keep your Git and ZIP folders outside your world folders, and don't point two
computers at the same Git folder at once.

## Build

Install Java 25 and Git, then run `./gradlew build` (or `.\gradlew.bat build`
on Windows). The JAR lands in `build/libs/`.

## License

Copyright 2026 Ishaan Kothari. Licensed under the
[Apache License 2.0](LICENSE).

## Contributing

Bug reports and small pull requests are welcome. Read
[CONTRIBUTING.md](CONTRIBUTING.md) first, and open a discussion before a
larger change.
