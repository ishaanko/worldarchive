# WorldArchive

WorldArchive is a Fabric client mod. It backs up your single-player Minecraft
worlds. It makes incremental Git snapshots and standalone ZIP archives. A
restore always creates a new world. Your original world is never changed.

## Features

- Back up manually, on world exit, or on a schedule
- Incremental Git snapshots, one repository per world
- ZIP archives with SHA-256 checksums
- Optional Git remote per world (HTTPS, SSH, file, or local path)
- ZIP folders can sit in OneDrive, Google Drive, or similar synced folders
- Labels, verification, remote sync, and storage forecasts
- Guided cleanup with a preview; labeled backups are always kept
- Import and recovery tools for old backups
- Copy-only restores that never touch the original world
- Every backup records the Minecraft version it was made with
- The Edit World backup buttons create and browse WorldArchive backups

WorldArchive never deletes backups on its own. You review and confirm every
cleanup.

## Requirements

- Minecraft 26.2
- Fabric Loader 0.19.3 or newer
- Fabric API 0.155.2+26.2
- Mod Menu 20.0.1 or newer
- Java 25
- Git and Git LFS on `PATH` (only needed for Git backups)

If Git or Git LFS is missing, WorldArchive turns off the Git destination. ZIP
backups still work.

## Quick start

1. Install Fabric Loader, Fabric API, and Mod Menu.
2. Copy the WorldArchive JAR into your `mods` folder.
3. Open **Mods**, select **WorldArchive**, and open its configuration.
4. Use the **World Backups** screen to create, restore, verify, sync, and
   delete backups.

Tip: you can also select a world in **Singleplayer** and click **Backups**.
On the **Edit World** screen, **Make Backup** creates a WorldArchive backup
and **Backups** opens the world's backup browser; vanilla's own backup
feature is replaced there.

## Backup triggers

| Trigger | Default | What it does |
| --- | --- | --- |
| Manual | On | Backs up when you click. |
| World exit | On | Backs up after the world saves and closes. |
| Scheduled | Off | Backs up every 30 minutes. Skips unchanged worlds. |

You can set triggers for Git and ZIP separately. You can also pause single
worlds. Open **Settings** from the backup browser.

Git and ZIP destinations are independent. If one fails and the other succeeds,
the good copy is kept. The backup is then reported as a partial success.

## Git remotes

Each world has its own Git repository. To sync a world to GitHub or another
server:

1. Create an empty repository on the server.
2. Open the **Worlds** settings tab and select the world.
3. Paste the clone URL into **Git remote**.

WorldArchive adds a short world code by itself. It never asks for your account
credentials. If a push fails, the backup is marked **pending sync**. You can
retry it later.

## Storage and cleanup

Each world's backup browser has a **Storage** screen. It shows Git and ZIP
usage, tracks the world's growth, and estimates when you will reach your
budget.

Guided cleanup keeps:

- One backup per day for the last 7 days
- One per week for 4 earlier weeks
- One per month for 12 earlier months
- Every labeled backup, always

Within a period, manual backups and backups with more changed files win.
Cleanup always shows a preview first. Nothing is deleted until you confirm.

## Minecraft versions

Every new backup records the Minecraft version it was made with. The restore
screen shows that version before you confirm.

If the version differs from the game you are running, the screen says so. A
backup from an older version is fine: Minecraft upgrades the restored copy when
you open it. A backup from a newer version is riskier, and the screen warns that
the restored copy may not open at all.

A version difference never blocks a restore. Restores only ever create a new
world, so nothing you already have is at risk. Backups made before this feature
shipped have no recorded version, and the screen says that too.

## Recover old backups

Open **Import** from the mod screen after a reinstall, a profile move, or a
switch to a new computer. Every import is scanned first. You then pick the
exact backups to bring in. Imports never overwrite your existing catalog.

- **Repository**: paste a repository location (HTTPS, SSH, Git, file, or local
  path, without credentials). WorldArchive copies and verifies the history,
  then connects it as the world's remote.
- **Choose Backup Folder**: pick a folder of WorldArchive ZIP archives.
- **Find Stored Backups**: rebuild catalog entries and Git refs from local
  managed storage. This search never touches the network.

## Default paths

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
| Storage forecast history | `worldarchive/storage-history/` |
| Storage review cadence | `worldarchive/storage-reviews/` |
| Temporary captures | `worldarchive/capture-temp/` |
| World identity | `saves/<world>/.worldarchive/world.json` |

Keep destination folders outside your world folders. Do not edit managed
folders while the game runs. Do not use one Git destination from two computers
at the same time.

## Build the mod

Install Java 25 and Git, then run:

```sh
./gradlew build        # Linux or macOS
.\gradlew.bat build    # Windows
```

The JAR files are in `build/libs/`.

## License

Copyright 2026 Ishaan Kothari.

WorldArchive is licensed under the [Apache License 2.0](LICENSE).

## Contributions

Bug reports and pull requests are welcome. For larger changes, open an issue
first.
