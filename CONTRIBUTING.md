# Contributing

## Read This First

WorldArchive is a backup tool. Its first job is to not lose your worlds. Because of that, the bar for changes is high and the scope is kept under tight control.

Small, focused contributions are welcome. Large, unsolicited ones are not.

Feature requests and proposals belong in [Ideas discussions](https://github.com/ishaanko/worldarchive/discussions/categories/ideas), not issues. Issues are reserved for bug reports.

PRs are automatically labeled with a `size:*` label based on effective changed lines (test files are excluded in mixed PRs).

## What Is Most Likely To Be Accepted

Small, focused bug fixes.

Small reliability fixes, especially around backup capture, Git snapshots, and restores.

Small performance improvements.

Tightly scoped maintenance work that clearly improves the project without changing its direction.

## What Is Least Likely To Be Accepted

Large PRs (`size:XL` and up).

Drive-by feature work.

Opinionated rewrites.

Anything that expands product scope without discussion first.

A 1,000+ line PR full of new features will probably be closed quickly.

## Before You Open A PR

Discuss non-trivial changes first in [Discussions](https://github.com/ishaanko/worldarchive/discussions). This gives you a chance to not waste your time.

Keep the PR small. One PR does one thing. Do not mix unrelated fixes together.

Explain exactly what changed and exactly why the change should exist.

If the PR changes a screen, include clear before/after screenshots.

## Development Setup

You need:

- Java 25 (the Gradle toolchain enforces this)
- Git and Git LFS on `PATH` (only needed to test Git backups in game)

Build and verify everything with one command:

```
./gradlew build
```

This runs compilation, unit tests, Checkstyle, and the custom quality gates (formatting, license, provenance, code structure). CI runs the same command. If it passes locally, CI will pass.

To test in game, run the client through Fabric Loom:

```
./gradlew runClient
```

## Code Guidelines

Keep things simple. Do not add abstraction for a future that may not come.

Match the style of the surrounding code. Checkstyle and the formatting gate enforce the baseline.

Tests must be focused. Test real behavior, not implementation detail. Backup and restore logic needs tests; UI wiring usually does not.

Never make a change that can delete or modify a user's world or backups without an explicit user action.

## Commit Messages

Use conventional commits: `<type>(optional scope): <description>`.

Examples: `fix(git): retarget main before deleting snapshot refs`, `feat(ui): add storage forecast to the backup screen`.

Use `!` or a `BREAKING CHANGE:` footer for breaking changes.

## Be Realistic

Opening a PR does not create an obligation to merge it. It may be closed, deferred, or sent back to be split into smaller pieces. If you are fine with that, proceed.
