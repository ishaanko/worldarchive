# Changelog

## 0.1.0 (unreleased)

### Added

- Manual backups from the backup browser and `/backup create`.
- Automatic backups when a world closes.
- Optional scheduled backups. Unchanged worlds are skipped.
- Git snapshots stored in a shared bare repository.
- Git LFS support for large Minecraft files.
- Optional Git remotes with retryable sync.
- ZIP backups with SHA-256 integrity metadata.
- Independent Git and ZIP destinations. A backup can succeed in one destination
  when the other fails.
- Backup verification for Git and ZIP copies.
- Copy-only restore. Restored backups are created as new worlds.
- Per-world backup settings and stable world IDs.
- Configurable destination paths with folder selection.
- Backup labels and changed-file counts.
- Backup creation, restore, deletion, sync, and verification from the in-game
  browser.
- Client-side `/backup` commands.
- Windows, Linux, and macOS builds in GitHub Actions.

### Fixed

- Wait for the integrated server to stop before taking an exit backup.
- Save and disconnect the active world before starting a restore.
- Accept valid directory timestamp behavior on Windows and macOS.
- Check file contents when only a file timestamp changes during capture.
- Restore Git snapshots that exist only on the configured remote.
- Report missing Git or Git LFS as a Git destination failure after startup.
- Keep ZIP backups successful when the Git destination fails.
- Recover interrupted captures without deleting unrelated files.
- Preserve retry state when a remote snapshot cannot be confirmed as deleted.
- Block backup operations when configured storage paths are unsafe.
- Refresh storage warnings after settings are corrected.
- Register restored and newly discovered worlds without restarting Minecraft.
- Correct the size and position of the **Backups** button on the world list.

### Security

- Reject path traversal, symbolic links, Windows reparse points, and special
  files during backup and restore.
- Reject destination paths that overlap a world directory.
- Detect path collisions before publishing a backup or restored world.
- Verify backup contents against their manifests.
- Publish backups and restored worlds atomically.
- Serialize backup operations with process and filesystem locks.
- Reject remote URLs containing credentials.
- Redact credentials and other sensitive values from process errors.
- Require confirmation before deleting backup data.
- Leave stale Git lock files in place for manual review.
