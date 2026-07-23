# Changelog

## Unreleased

### Changed

- Keep the title and pause menus unchanged. Open WorldArchive through Mod Menu,
  or use **Backups** for a selected world in the Singleplayer menu.
- Simplify backup import labels and actions for nontechnical users, with a
  single **Import** entry and clear choose, import, and back actions.
- Let users select the exact backups to import from repository, folder, and
  stored-backup searches instead of importing every discovered backup.
- Render all text in selectable backup rows in white for better contrast.

### Fixed

- Keep manually deleted backups deleted across restarts, even when recoverable
  Git history or managed ZIP storage still exists for catalog repair.
- Preserve exact repository selections across restart repair, reject duplicate
  backup identities, and revalidate linked ZIP files when Import is clicked.
- Release abandoned import previews and superseded runtime storage states, and
  prevent completed background scans from reopening a screen the user left.
- Hide deleted worlds after their final backup is removed, and report import
  failures without claiming that earlier completed items were rolled back.
- Bound repository history inspection so unexpectedly large remotes fail safely.

### Removed

- Remove the client-side `/backup` command system; all supported operations remain
  available through the WorldArchive screens.
- Remove WorldArchive shortcut buttons from Minecraft's title and pause screens.

## 0.3.0 (2026-07-21)

### Added

- Recover complete WorldArchive Git histories from any supported Git remote or
  absolute local repository, with full-download and remote-backed modes.
- Import folders of WorldArchive ZIP archives either by copying them into
  managed storage or linking them read-only in their existing location.
- Preview every recovery operation before it changes the catalog, show exact
  additions, merges, unchanged records, conflicts, and rejected artifacts, and
  never overwrite conflicting backup identities.
- Rebuild the local backup catalog and missing Git snapshot refs from managed
  storage without contacting a network.
- Show archived worlds that no longer have a live save folder in the World
  Backups screen, so their recovered histories remain restorable.
- Add recovery workflows to the backup UI and the `/backup import` and
  `/backup rebuild` commands.

### Changed

- Track managed, imported-managed, and external artifact ownership plus durable
  import-source provenance in catalog schema 3.
- Deleting a linked ZIP or remote-backed Git backup now removes only
  WorldArchive's local reference; the source archive or remote is never deleted.
- Git imports can optionally connect existing live worlds to the imported remote
  for future backups, while archived worlds remain recovery-only.

## 0.2.1 (2026-07-21)

### Changed

- Replace the one-world-at-a-time settings page with a visible world list and
  selected-world editor.
- Allow each world to override the default ZIP folder while keeping the ZIP tab
  as the inherited default for existing and newly discovered worlds.
- Capitalize destination health labels and states in the settings footer.
- Simplify the `/backup` dashboard and chat formatting, add dedicated command
  help, expose per-backup actions directly from list rows, and add managed-folder
  access so the command surface matches the backup GUI more closely.

### Fixed

- Keep the inherited ZIP-folder hint inside its field and make the per-world
  toggle positive: selected enables a separate folder, while unselected inherits
  the ZIP-tab default.
- Clarify that the Git folder is a parent for isolated per-world repositories and
  that configured remotes synchronize automatically after each new Git backup.

## 0.2.0 (2026-07-21)

### Changed

- Add compact WorldArchive icon shortcuts beside Mod Menu on the title screen
  and beside the lower mod-icon row on the pause screen.
- Give remote backup branches readable UTC date/time names and automatically
  migrate matching legacy UUID-style branches during synchronization.
- Synchronize every newly created Git commit immediately when its world has a
  remote configured.
- Configure an ordinary Git remote URL independently for each world instead of
  using a global `{worldId}` template.
- Show automatic six-character world codes while retaining full internal UUIDs
  for collision-safe backup compatibility.
- Replace the mod icon with crisp 16-by-16-grid Minecraft-style artwork.
- Restore the visible Git remote-name field, simplify setup guidance, and use
  concise backup progress and completion notices.

### Fixed

- Keep remote setup guidance wrapped inside the settings layout instead of
  allowing long hint text to spill over the panorama.
- Remove cramped cloud-folder and automatic-world-code explanatory copy.
- Center the entire pause-menu mod-icon row after adding WorldArchive, including
  layouts with one or no existing lower-row icons.
- Remove integrity and changed-file controls from the backup browser, simplify
  each backup row, and warn that large worlds may take a while to back up.
- Tell players to keep Minecraft open while a backup runs and explicitly
  confirm when it is safe to quit after Save & Quit.

## 0.1.3 (2026-07-21)

### Fixed

- Retry world discovery while a newly created world's `level.dat` is still
  being published, so backup features become available without a restart.
- Apply Git command timeouts to blocked standard-input writes as well as the
  process itself.
- Keep Git commands non-interactive even when a caller supplies conflicting
  environment values.
- Configure the release JAR's bundled license in a way that remains compatible
  with Gradle 10.

## 0.1.2 (2026-07-20)

### Changed

- Refactor runtime lifecycle, navigation, recovery, and storage internals into
  focused components without changing supported behavior or data formats.

### Fixed

- Stabilize private-capture cancellation coverage so test teardown does not
  interrupt cleanup a second time.

## 0.1.1 (2026-07-18)

### Changed

- Store new Git backups in a separate bare repository and linear history for
  each world.
- Sync each world to its own existing remote repository with a `{worldId}` URL
  template.
- Replace the redundant General settings tab with direct Git and ZIP timing
  controls.
- Give ZIP archives readable timestamp, world, and label names while retaining
  their exact backup identity.

### Fixed

- Keep legacy shared Git repositories and remotes available after the safe
  schema 3 to schema 4 migration without moving or deleting them.
- Show wrapped, higher-contrast settings and backup-browser text.
- Preserve a visible selection border without washing out backup-row text.
- Confirm Save & Quit backup results with an in-game toast.
- Report delete, sync, and verify outcomes with operation-specific wording.
- Rename **Restore & Select** to **Restore**.
- Remove stale injected **Backups** buttons after rapid world selection and
  Escape navigation.
- Offer guidance and a Retry action when live world files change during a
  private capture.

## 0.1.0 (2026-07-18)

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
