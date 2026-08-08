package dev.ishaankot.worldarchive.storage.git;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.ishaankot.worldarchive.core.BackupCapture;
import dev.ishaankot.worldarchive.core.ProgressListener;
import dev.ishaankot.worldarchive.model.BackupId;
import dev.ishaankot.worldarchive.model.BackupManifest;
import dev.ishaankot.worldarchive.model.BackupTrigger;
import dev.ishaankot.worldarchive.model.DestinationResult;
import dev.ishaankot.worldarchive.model.DestinationStatus;
import dev.ishaankot.worldarchive.model.WorldId;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GitBackupBackendToolAvailabilityTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void enabledDestinationFailsWhenToolsDisappearAfterInitialProbe() throws Exception {
        AtomicInteger probes = new AtomicInteger();
        GitCommandRunner disappearingTools = command -> {
            int probe = probes.getAndIncrement();
            if (probe < 2) {
                String version = command.arguments().contains("lfs")
                        ? "git-lfs/3.7.1"
                        : "git version 2.50.1";
                return new GitCommandResult(0, version, "", false, false);
            }
            return new GitCommandResult(1, "", "tool unavailable", false, false);
        };
        Path repository = temporaryDirectory.resolve("missing-tools.git");
        GitBackendSettings settings = new GitBackendSettings(
                true,
                repository,
                "git",
                "origin",
                Optional.empty(),
                GitBackendSettings.DEFAULT_LFS_PATTERNS,
                Duration.ofSeconds(5),
                1_024 * 1_024);
        Path world = Files.createDirectory(temporaryDirectory.resolve("world"));
        BackupManifest manifest = BackupManifest.create(
                BackupId.create(),
                WorldId.create(),
                "Tool Loss World",
                Instant.parse("2026-07-18T08:00:00Z"),
                BackupTrigger.MANUAL,
                0L,
                0L,
                "00".repeat(32));

        try (ExecutorService executor = Executors.newSingleThreadExecutor();
                GitBackupBackend backend = new GitBackupBackend(
                        settings, disappearingTools, executor)) {
            assertTrue(backend.probeTools().toCompletableFuture().get(
                    10, TimeUnit.SECONDS).available());

            DestinationResult result = backend.createBackup(
                            new BackupCapture(world, manifest),
                            ProgressListener.NO_OP)
                    .toCompletableFuture()
                    .get(10, TimeUnit.SECONDS);

            assertEquals(DestinationStatus.FAILED, result.status());
            assertEquals("Git and Git LFS are unavailable", result.message().orElseThrow());
            assertFalse(Files.exists(repository, LinkOption.NOFOLLOW_LINKS));
            assertEquals(4, probes.get());
        }
    }
}
