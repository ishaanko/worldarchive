package dev.ishaankot.worldarchive.storage.zip;

import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ZipSourceScannerTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void ignoresDirectoryTimestampDriftWhileTreeRemainsStable() throws Exception {
        Path world = Files.createDirectory(temporaryDirectory.resolve("world-directory-time"));
        Path region = Files.createDirectory(world.resolve("region"));
        Files.writeString(region.resolve("r.0.0.mca"), "contents", StandardCharsets.UTF_8);
        ZipSourceScanner.SourceSnapshot snapshot = ZipSourceScanner.snapshot(world);

        Files.setLastModifiedTime(
                world,
                FileTime.from(Instant.parse("2026-07-17T12:00:00Z")));
        Files.setLastModifiedTime(
                region,
                FileTime.from(Instant.parse("2026-07-17T12:01:00Z")));

        snapshot.requireUnchanged();
    }

    @Test
    void stillDetectsEmptyDirectoryMembershipChanges() throws Exception {
        Path world = Files.createDirectory(temporaryDirectory.resolve("world-membership"));
        Files.writeString(world.resolve("level.dat"), "contents", StandardCharsets.UTF_8);
        FileTime originalRootTime = Files.getLastModifiedTime(world);
        ZipSourceScanner.SourceSnapshot snapshot = ZipSourceScanner.snapshot(world);

        Files.createDirectory(world.resolve("late-empty-directory"));
        Files.setLastModifiedTime(world, originalRootTime);

        assertThrows(ZipBackupException.class, snapshot::requireUnchanged);
    }
}
