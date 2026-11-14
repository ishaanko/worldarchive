# WorldArchive

WorldArchive backs up your single-player Minecraft worlds, as incremental Git
snapshots, standalone ZIP archives, or both. A restore always creates a new
world, so your original world is never touched.

## Features

- Backups on demand, on world exit, or every 30 minutes on a schedule
- One Git repository per world, with an optional remote to push to
- ZIP archives with SHA-256 checksums, fine to keep in OneDrive or Google Drive
- Labels, verification, and a storage screen that forecasts disk use
- Guided cleanup with a preview; labeled backups are always kept
- Import of Git repositories and ZIP folders after a reinstall or a move
- Restores warn when a backup comes from a different Minecraft version
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
work. WorldArchive runs on the client and backs up local worlds only, not
servers you join.

## Getting started

Click the **World Backups** icon on the title screen, or select a world in
**Singleplayer** and click **Backups**. From there you can back up, restore,
verify, sync, import, and delete.

Source code and issues are on [GitHub](https://github.com/ishaanko/worldarchive).
