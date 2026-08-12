package dev.ishaanko.worldarchive.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.ishaanko.worldarchive.config.WorldArchiveConfig;
import dev.ishaanko.worldarchive.core.BackupBackend;
import dev.ishaanko.worldarchive.core.BackupCapture;
import dev.ishaanko.worldarchive.core.ConfiguredBackupDestinationSelector;
import dev.ishaanko.worldarchive.core.CreateBackupRequest;
import dev.ishaanko.worldarchive.core.ProgressListener;
import dev.ishaanko.worldarchive.model.BackupTrigger;
import dev.ishaanko.worldarchive.model.DestinationResult;
import dev.ishaanko.worldarchive.model.DestinationType;
import dev.ishaanko.worldarchive.model.WorldId;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class RuntimeDestinationSelectorTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void keepsZipAvailableWhileGitToolsAreMissingOrStillBeingChecked() {
        BackupBackend git = backend(DestinationType.GIT);
        BackupBackend zip = backend(DestinationType.ZIP);
        RuntimeDestinationSelector selector = new RuntimeDestinationSelector(
                new ConfiguredBackupDestinationSelector(
                        WorldArchiveConfig::defaults,
                        List.of(git, zip)));
        CreateBackupRequest request = new CreateBackupRequest(
                WorldId.create(),
                temporaryDirectory.resolve("world"),
                "World",
                Optional.empty(),
                BackupTrigger.MANUAL);

        assertEquals(List.of(DestinationType.ZIP), types(selector.select(request)));
        assertTrue(selector.warning().isEmpty());

        selector.gitToolsAvailable(false);
        assertEquals(List.of(DestinationType.ZIP), types(selector.select(request)));
        assertTrue(selector.hasConfiguredDestination(request));
        assertTrue(selector.warning().isPresent());

        selector.gitToolsAvailable(true);
        assertEquals(
                List.of(DestinationType.GIT, DestinationType.ZIP),
                types(selector.select(request)));
        assertTrue(selector.warning().isEmpty());

        selector.gitToolsAvailable(false);
        assertEquals(List.of(DestinationType.ZIP), types(selector.select(request)));
        assertTrue(selector.warning().isPresent());

        selector.gitDisabled();
        assertEquals(List.of(DestinationType.ZIP), types(selector.select(request)));
        assertFalse(selector.warning().isPresent());
    }

    private static List<DestinationType> types(List<BackupBackend> backends) {
        return backends.stream().map(BackupBackend::destinationType).toList();
    }

    private static BackupBackend backend(DestinationType destination) {
        return new BackupBackend() {
            @Override
            public DestinationType destinationType() {
                return destination;
            }

            @Override
            public CompletionStage<DestinationResult> createBackup(
                    BackupCapture capture,
                    ProgressListener progressListener) {
                return CompletableFuture.completedFuture(DestinationResult.success(
                        destination,
                        destination.name().toLowerCase()));
            }
        };
    }
}
