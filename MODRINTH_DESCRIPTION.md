# WorldArchive

WorldArchive is a Fabric client mod for dependable backups of single-player Minecraft worlds. Create incremental Git snapshots, standalone ZIP archives, or both, then verify and restore any backup as a new world without modifying the original.

## Features

- Manual backups from the backup browser
- Automatic backups when leaving a world
- Optional scheduled backups (every 30 minutes by default, skipping unchanged worlds)
- Independent Git and ZIP destinations
- Incremental Git snapshots with optional Git LFS and per-world remote sync
- ZIP archives with SHA-256 integrity metadata, usable inside OneDrive, Google Drive, or similar synced folders
- Per-world destination settings, ZIP folder overrides, and per-world pausing
- Backup labels, verification, synchronization, and manual deletion
- Per-world storage overview with growth tracking and budget forecasts
- Guided cleanup with a full preview; labeled backups are always kept
- Import and recovery of existing WorldArchive Git histories and ZIP folders
- Safe, copy-only restores to uniquely named new worlds
- Partial-success reporting when one destination fails
- Every backup records the Minecraft version it was made with, and restores warn across versions
- The Edit World backup buttons create and browse WorldArchive backups instead of vanilla zip copies

WorldArchive never deletes backups on its own. Guided cleanup shows a preview and waits for your confirmation before removing anything.

## Requirements

- Minecraft 26.2
- Fabric Loader 0.19.3 or newer
- Fabric API 0.157.0+26.2
- Mod Menu 20.0.1 or newer
- Java 25

Git backups also require Git and Git LFS on your system path. They are not needed for ZIP-only backups; if either tool is unavailable, the Git destination is disabled and ZIP backups remain usable.

WorldArchive is client-side and backs up local single-player saves only. It does not back up servers you join, and it never stores your Git credentials.

## Getting started

Open **Mods**, choose **WorldArchive**, and use its configuration button. The **World Backups** screen lets you create, restore, delete, sync, verify, and import backups.

You can also select a world in **Singleplayer** and choose **Backups** to open that world's history directly.

Restores always create a separate world. Your original save is never overwritten or modified.

Source code and issue tracking are available on [GitHub](https://github.com/ishaanko/worldarchive).
