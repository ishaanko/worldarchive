# Thermo-Nuclear Code Quality TODO

Scope: the complete tracked WorldArchive codebase, reviewed on 2026-07-18.
The inventory covered 200 production Java files, 59 test Java files, build and
quality configuration, resources, scripts, workflows, and project
documentation.

## Confirmed findings

- [x] **CQ-001 — Decompose every Java file above 1,000 lines.** The current
  tree has eight: `WorldArchiveRuntime` (2,141), `GitBackupBackend` (1,814),
  `BackupRecoveryService` (1,398), `BackupRecoveryServiceTest` (1,492),
  `GitBackupBackendIntegrationTest` (1,272), `ZipBackupStore` (1,203),
  `WorldArchiveSettingsScreen` (1,184), and
  `FileSystemBackupCaptureFactory` (1,074). The production classes combine
  unrelated lifecycle, presentation, publication, verification, filesystem,
  and transport responsibilities. Extract cohesive collaborators, keep the
  public APIs stable, and split the two test suites by behavior.
  - Resolution: all eight files are now at or below 1,000 lines. Runtime,
    Git, recovery, capture, ZIP, and settings responsibilities have explicit
    owners; the recovery and Git integration suites are split without losing
    any of their 28 and 26 tests, respectively.

- [x] **CQ-002 — Establish one canonical filesystem-entry safety boundary.**
  Windows reparse-point detection and no-follow file-type checks are
  independently implemented in capture, recovery, Git source scanning, Git
  repository guarding, and ZIP storage. Move the platform rule to a shared
  core utility and make each subsystem express only its domain-specific error.
  - Resolution: `FileSystemSafety` now owns no-follow ordinary-file,
    ordinary-directory, and Windows reparse-point classification for every
    filesystem-backed destination.

- [x] **CQ-003 — Flatten the remaining orchestration hot spots.**
  `BackupRecoveryService.restoreBlocking` is 158 lines with 21 decision
  points, `GitBackupBackend.createLocalSnapshot` is 101 lines, and
  `SerializedBackupCoordinator.finish` has 16 decision points. Extract explicit
  phase/result helpers so cleanup, rollback, persistence, and reporting do not
  remain interleaved in single methods.
  - Resolution: restore, Git snapshot creation, and coordinator completion are
    decomposed into named phases with isolated cleanup and publication logic.

- [x] **CQ-004 — Make structural regressions fail the standard quality suite.**
  Add production/test source-size and method-complexity checks to the existing
  Gradle `check` lifecycle after the current violations are removed. The
  quality gate must reject a Java file above 1,000 lines and the method-size or
  branching patterns addressed by CQ-003.
  - Resolution: Gradle `check` now rejects Java files above 1,000 lines, while
    Checkstyle rejects methods above 100 non-empty lines or complexity above
    15 across main, client, and test sources.

- [x] **CQ-005 — Remove Gradle 10-incompatible task-time project access.**
  The repository quality tasks access `Task.project` while executing, which
  Gradle 9.5 deprecates and Gradle 10 will reject. Capture repository paths and
  the project directory through configuration-safe providers instead.
  - Resolution: all four repository quality tasks now use a shared provider
    and `Directory` captured during configuration; the deprecation report is
    clean under `--warning-mode all`.

## Completion checks

- [x] All applicable automated tests pass under Java 25.
- [x] Checkstyle, formatting, license, provenance, compilation, packaging, and
  build tasks pass.
- [x] No Computer Use test or interactive UI automation is run.
- [x] Final diff and this TODO are re-reviewed against the supplied
  thermo-nuclear review standard.
