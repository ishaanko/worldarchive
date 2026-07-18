# Changelog

All notable changes to WorldArchive will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project uses [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added

- Clean-room Apache-2.0 Fabric 26.2 client-mod foundation for Java 25, with
  required Fabric API and Mod Menu integration.
- Versioned configuration, per-world identities, atomic catalog persistence,
  destination health checks, editable/native folder selection, and per-world
  controls.
- Immutable save-gated world capture, change inventories, serialized lifecycle
  coordination, independent destination results, cancellation, and no-catch-up
  scheduled-backup logic, wired to integrated-server manual, final-exit, and
  scheduled triggers with bounded client shutdown.
- Shared bare Git storage with parentless per-backup refs, Git LFS handling,
  optional remote synchronization, pending-sync recovery, verification, and
  independent snapshot deletion.
- Atomic ZIP archives with embedded manifests and inventories, SHA-256
  sidecars, crash recovery, corruption detection, verification, and exact
  extraction.
- Copy-only restore with destination fallback, unique target naming, fresh
  world identity and source provenance, plus confirmed partial deletion and
  persisted verify/sync results.
- Native Select World **Backups** integration and a browser with rich backup
  rows, create, restore, delete, sync, verify, open-folder, and settings actions.
- Complete client-side `/backup` command tree with clickable help, pagination,
  ID suggestions, progress, destination health, confirmations, and redacted
  outcome summaries.
- Java 25 unit and Git/LFS/ZIP integration coverage, Checkstyle, formatting,
  license and clean-room checks, Gradle wrapper, and GitHub Actions builds on
  Windows and Linux.

### Fixed

- Accepted deferred Windows directory timestamps during core, Git, and ZIP
  capture while retaining membership and filesystem-identity checks, and
  accepted file timestamp-only drift only after the live bytes hash-match the
  private capture.
- Deferred final-exit capture until the matching integrated server has stopped,
  after observing its final save, so late region and entity writes cannot race
  the private capture.
- Fully saved and disconnected an active world before restore navigation, and
  corrected the Select World **Backups** action size and position.
- Blocked backup operations under unsafe persisted storage paths, refreshed the
  visible warning state after safe settings changes, and registered newly
  discovered or restored world paths without a restart.
- Kept destructive destination deletion and its catalog repair cancellation-safe,
  reconciled exact already-absent artifacts, and preserved retryable state when
  a configured remote snapshot cannot be proven absent.
- Reconciled ambiguous atomic restore publication only when the published copy
  retains the exact private-staging identity, and drained interrupted Git/LFS
  materialization before cleaning its staging directory.
- Recovered abandoned private captures conservatively and closed prepared-capture,
  configuration-transaction, and live-startup ownership races.

### Security

- Added atomic publication and rollback, manifest-bound content verification,
  traversal/link/reparse/path-collision defenses, destination-overlap checks,
  process and filesystem locking, credential-free remote validation, and
  sensitive-output redaction.
- Bound each world UUID to one normalized source folder and prevent destination
  path changes from racing active backup publication or orphaning cataloged
  artifacts.
- Destructive deletion requires a short-lived one-use confirmation, and stale
  Git lock files are never removed automatically.

[Unreleased]: https://github.com/ishaankot/worldarchive
