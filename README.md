# WorldArchive

WorldArchive is an independently developed Fabric client mod for dependable
single-player world backups.

The repository currently contains the clean project foundation. Backup storage,
recovery, commands, and in-game screens are being built as one coordinated
release and are not available yet.

## Target

- Minecraft 26.2
- Fabric Loader 0.19.3
- Java 25
- Fabric API 0.155.2+26.2
- Mod Menu 20.0.1 (required)

## Build

Install Java 25 and point `JAVA_HOME` to it before building. The wrapper
downloads the pinned Gradle distribution automatically.

~~~powershell
.\gradlew.bat check build
~~~

The mod JAR is written to `build/libs/`.

## Development

- Production code uses `dev.ishaankot.worldarchive`.
- Mod ID: `worldarchive`.
- The project is client-only for its first release.
- `check` runs unit tests, Checkstyle, formatting, license, and clean-room
  provenance checks.

## License

Copyright 2026 Ishaan Kothari. Licensed under the
[Apache License 2.0](LICENSE).
