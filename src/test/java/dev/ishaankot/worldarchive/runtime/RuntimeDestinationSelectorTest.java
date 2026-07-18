package dev.ishaankot.worldarchive.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.ishaankot.worldarchive.config.WorldArchiveConfig;
import dev.ishaankot.worldarchive.core.BackupBackend;
import dev.ishaankot.worldarchive.core.BackupCapture;
import dev.ishaankot.worldarchive.core.ConfiguredBackupDestinationSelector;
import dev.ishaankot.worldarchive.core.CreateBackupRequest;
import dev.ishaankot.worldarchive.core.ProgressListener;
import dev.ishaankot.worldarchive.model.BackupTrigger;
import dev.ishaankot.worldarchive.model.DestinationResult;
import dev.ishaankot.worldarchive.model.DestinationType;
import dev.ishaankot.worldarchive.model.WorldId;
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

        selector.gitToolsAvailable(true);
        assertEquals(
                List.of(DestinationType.GIT, DestinationType.ZIP),
                types(selector.select(request)));

        selector.gitToolsAvailable(false);
        assertEquals(List.of(DestinationType.ZIP), types(selector.select(request)));
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
