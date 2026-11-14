# Contributing

WorldArchive is a backup tool, and its first job is to not lose your worlds.
That keeps the bar for changes high and the scope narrow. Small, focused
contributions are welcome. Large unsolicited ones are not.

Issues are for bug reports. Feature requests and proposals go in
[Ideas discussions](https://github.com/ishaanko/worldarchive/discussions/categories/ideas).

## What gets merged

Most likely:

- Small bug fixes, above all in backup capture, Git snapshots, and restores
- Small performance improvements
- Tightly scoped maintenance that doesn't change the project's direction

Least likely:

- Large PRs (`size:XL` and up). A bot labels each PR by changed lines, not
  counting tests in mixed PRs. A 1,000-line PR of new features will probably
  be closed.
- Drive-by features, opinionated rewrites, and anything that grows the scope
  without a discussion first

## Before you open a PR

- Discuss anything non-trivial in
  [Discussions](https://github.com/ishaanko/worldarchive/discussions) first, so
  you don't build something that won't be merged.
- One PR does one thing. Don't bundle unrelated fixes.
- Say what changed and why it should.
- If it changes a screen, include before and after screenshots.

## Development setup

You need Java 25 (the Gradle toolchain enforces it), plus Git and Git LFS on
`PATH` if you want to test Git backups in game.

```
./gradlew build
```

That one command compiles, runs the unit tests and Checkstyle, and runs the
quality gates for formatting, license headers, provenance, and code structure.
CI runs the same command, so green locally means green in CI.

To test in game:

```
./gradlew runClient
```

## Code guidelines

- Keep it simple. Don't add abstraction for a future that may not come.
- Match the surrounding code. Checkstyle and the formatting gate enforce the
  baseline.
- Test real behavior, not implementation detail. Backup and restore logic
  needs tests; UI wiring usually doesn't.
- Never make a change that can delete or modify a user's world or backups
  without an explicit user action.

## Commit messages

Use conventional commits, `<type>(optional scope): <description>`, for example
`fix(git): retarget main before deleting snapshot refs`. Mark breaking changes
with `!` or a `BREAKING CHANGE:` footer.

## Be realistic

Opening a PR doesn't oblige anyone to merge it. It may be closed, deferred, or
sent back to be split up.
