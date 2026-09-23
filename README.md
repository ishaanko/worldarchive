# WorldArchive

WorldArchive is a Fabric client mod. It backs up your single-player Minecraft
worlds. It makes incremental Git snapshots and standalone ZIP archives. A
restore always creates a new world. Your original world is never changed.

## Features

- Back up manually, on world exit, or on a schedule
- Incremental Git snapshots, one repository per world
- ZIP archives with SHA-256 checksums
- Optional Git remote per world, over HTTPS or SSH or in a local folder
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

- Minecraft 26.3
- Fabric Loader 0.19.3 or newer
- Fabric API 0.160.5 or newer
- Mod Menu 21.0.0-beta.1 or newer (optional)
- Java 25
- Git and Git LFS on `PATH` (only needed for Git backups)

If Git or Git LFS is missing, WorldArchive turns off the Git destination. ZIP
backups still work.

## Quick start

1. Install Fabric Loader and Fabric API.
2. Copy the WorldArchive JAR into your `mods` folder.
3. Click the **World Backups** icon on the title screen. If Mod Menu is
   installed, you can also open **Mods**, select **WorldArchive**, and open
   its configuration.
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
| Scheduled | Off | Backs up every 30 minutes while you play. Skips a world that did not run since its last backup, for example while the game is paused. |

You can set triggers for Git and ZIP separately. You can also pause single
worlds. Open **Settings** from the backup browser.

A backup of the open world asks the game to save first. WorldArchive then
pauses autosave while it copies the world, and sets autosave back the way you
had it. A world exit backup copies the world after the last save. A backup of
an open world checks every file twice, because the game can still write a file
during the copy. A backup of a closed world reads each file once.

A backup leaves out `session.lock`, the `.worldarchive` folder, and every
`.git` folder or file in the world, such as a data pack that is a Git clone.
A link inside a world stops the backup, and the message names the link. A
saves folder or a world folder that is itself a symbolic link or a Windows
junction works. WorldArchive uses the real folder.

Git and ZIP destinations are independent. If one fails and the other succeeds,
WorldArchive keeps the good copy and reports a partial success.

## Git remotes

Each world has its own Git repository. To sync a world to GitHub or another
server:

1. Create an empty repository on the server.
2. Open the **Worlds** settings tab and select the world.
3. Paste the clone address into **Git remote**.

WorldArchive accepts these forms:

- `https://host/path/world.git`
- `ssh://user@host/path/world.git`, also with a port: `ssh://user@host:2222/path/world.git`
- `user@host:path/world.git`
- `file:///path/to/world.git`, or an absolute local path such as
  `/backups/world.git` or `D:\Backups\world.git`

A remote address must not contain a password, an access token, a query, or a
fragment. WorldArchive never asks for your account credentials. Git gets them
from its credential helper or your SSH key. If a push fails, WorldArchive marks
the backup **pending sync**. You can retry it later.

On the remote, each backup has its own branch,
`backups/<date>/<time>Z-<backup id>`, and `main` shows the newest backup.

## Delete

**Delete** removes every copy of a backup: the ZIP archive, the Git copy on
this computer, and the Git copy on the world's remote. It also moves `main` on
the remote to the newest backup that is left. The prompt names the backups and
says how many copies are on the remote.

If the remote cannot be reached or refuses the delete, nothing changes on the
remote, and the Git copy on this computer stays too. WorldArchive still deletes
the ZIP archive. The backup stays in the list with its Git copy, and the result
says that the delete failed. Delete it again later.

If the folder that holds a copy cannot be reached, for example on a drive that
is not connected, the delete fails and nothing changes. Connect the drive and
delete the backup again.

If you removed the world's remote from its settings, a delete removes the
copies on this computer and says that the copy on the old remote stays. A
backup whose only copy is on that remote stays in the list until you add the
remote again.

A deleted backup does not come back after a restart. Two things can stay on
the server: Git LFS files, which most hosts keep until you remove them in the
repository settings, and the content of backups made with WorldArchive 0.3.1
or older, which newer backups from those versions still reach.

## Storage and cleanup

Each world's backup browser has a **Storage** screen. It shows Git and ZIP
usage, tracks the world's growth, and estimates when you will reach your
budget.

By default, guided cleanup keeps:

- One backup for each of the 7 most recent days that have backups
- Then one backup for each of the 4 most recent weeks that have older backups
- Then one backup for each of the 12 most recent months that have older backups
- Every labeled backup, always
- The newest verified backup

You can change the three counts on the **Storage** screen. A week starts on
Monday. Within a day, week, or month, cleanup keeps a manual backup first,
then the backup with the most changed files. Cleanup always shows a preview
first. Nothing is deleted until you confirm.

Cleanup frees space on this computer only. For each backup it lists, it
removes the ZIP archive and the Git copy on this computer. It never touches
copies on the remote. A backup that has a copy on the remote stays in the list
as a remote-only backup, and **Delete** is the way to remove that copy. If the
remote cannot be reached when you confirm, the backups that need it keep every
copy, and the result says why.

Cleanup decides from the files on disk, not from the backup list. A backup
with no copy left leaves the list. Cleanup never deletes a protected backup.
When space is still short, it can offer the Git copies on this computer of all
protected backups as one group. It does this only when each of them keeps a
ZIP archive that **Verify** did not find damaged, or the same copy on the
remote. You select the whole group or none of it.

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

Open **World Backups** and click **Import** after a reinstall, a profile move,
or a move to a new computer. Every import is scanned first. You then pick the
exact backups to bring in. Imports never overwrite your existing backup list.

- To import a Git repository, paste its address into **Repository address**
  and click **Find Backups from Repository**. Any remote form above works, and
  so does a `git://` address. WorldArchive copies and checks the backups. A
  world that has no remote yet gets the repository as its remote.
- To import ZIP archives, click **Choose Backup Folder** and pick their folder.
  WorldArchive copies them into its ZIP folder and never writes into the folder
  you pick.
- To list the backups in WorldArchive's own folders that the backup list lacks,
  click **Find Stored Backups**. This search uses the network only after
  WorldArchive set a damaged backup list aside, to ask each world's remote which
  of the Git backups it finds are there.

WorldArchive 0.1.0 kept all worlds in one shared Git repository, by default
`.minecraft/worldarchive/worldarchive.git`. This version does not read it on
its own. Import it one time with **Find Backups from Repository** and its local
path.

## Default paths

Paths are relative to the Minecraft instance directory, normally `.minecraft`.

| Purpose | Default path |
| --- | --- |
| Configuration | `config/worldarchive.json` |
| Per-world Git repositories | `worldarchive/git/<world-id>.git` |
| ZIP archives | `worldarchive/archives/<world-id>/` |
| Backup catalog | `worldarchive/catalog.json` |
| Import source registry | `worldarchive/import-sources.json` |
| Deleted-backup registry | `worldarchive/deleted-backups.txt` |
| Change inventories | `worldarchive/inventories/` |
| Storage forecast history | `worldarchive/storage-history/` |
| Notice from the last session | `worldarchive/last-background-warning.txt` |
| Temporary world copies | `worldarchive/capture-temp/<id>/` and `<id>.lock` |
| Temporary Git files | `worldarchive/git/<world-id>.git/worldarchive/tmp/` |
| Unfinished restores | `saves/.worldarchive-restore-*` |
| World identity | `saves/<world>/.worldarchive/world.json` |

Each running game makes one `capture-temp/<id>/` folder and locks it with
`<id>.lock`. At start, WorldArchive removes the copies that a stopped game left
behind. It never removes the folder of a game that still runs. Git temporary
files, and a damaged Git LFS file that **Verify** or a restore moved out of the
way, go before the next Git operation on that repository. A Git import
preview and the output of Git commands use the temporary folder of your
system for a short time.

A crash during a restore can leave a `.worldarchive-restore-*` folder in
`saves`. WorldArchive names such a folder in the game log but never deletes
it. Delete it yourself when no restore runs.

WorldArchive keeps a copy when it changes a file that it cannot use as is:
`catalog.json.corrupt-<time>` for a damaged backup catalog,
`config/worldarchive.json.unreadable-<time>` after a settings reset, and
`config/worldarchive.json.schema<number>.bak` before a settings upgrade. From a
damaged backup catalog, WorldArchive keeps every entry that it can still read.
WorldArchive 0.4.0 and older also left `inventories/*.lock` files and a
`storage-reviews/` folder, which this version does not use. You can delete
them.

Keep destination folders outside your world folders. Do not edit managed
folders while the game runs. Do not use one Git destination from two computers
at the same time.

A backup never makes a Git or ZIP folder that you chose in the settings. When
such a folder is missing, for example because its drive is not connected, the
backup to it fails and says that the folder cannot be reached. WorldArchive
makes the folder when you save the settings with it. The default folders are
made when a backup needs them.

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
