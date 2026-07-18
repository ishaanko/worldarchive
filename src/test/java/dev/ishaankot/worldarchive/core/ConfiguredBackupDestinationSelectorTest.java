package dev.ishaankot.worldarchive.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.ishaankot.worldarchive.config.WorldArchiveConfig;
import dev.ishaankot.worldarchive.config.WorldConfig;
import dev.ishaankot.worldarchive.model.BackupTrigger;
import dev.ishaankot.worldarchive.model.DestinationResult;
import dev.ishaankot.worldarchive.model.DestinationType;
import dev.ishaankot.worldarchive.model.WorldId;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class ConfiguredBackupDestinationSelectorTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void appliesCurrentGlobalWorldAndDestinationGates() {
        WorldArchiveConfig defaults = WorldArchiveConfig.defaults();
        AtomicReference<WorldArchiveConfig> configuration = new AtomicReference<>(defaults);
        BackupBackend git = backend(DestinationType.GIT);
        BackupBackend zip = backend(DestinationType.ZIP);
        ConfiguredBackupDestinationSelector selector = new ConfiguredBackupDestinationSelector(
                configuration::get,
                List.of(zip, git));
        WorldId worldId = WorldId.create();
        Path world = temporaryDirectory.resolve("world");

        assertEquals(
                List.of(DestinationType.GIT, DestinationType.ZIP),
                types(selector.select(request(worldId, world, BackupTrigger.MANUAL))));
        assertEquals(
                List.of(),
                selector.select(request(worldId, world, BackupTrigger.SCHEDULED)));

        configuration.set(new WorldArchiveConfig(
                WorldArchiveConfig.CURRENT_SCHEMA_VERSION,
                defaults.triggers(),
                defaults.git(),
                defaults.zip(),
                List.of(new WorldConfig(worldId, false, world))));
        assertEquals(
                List.of(),
                selector.select(request(worldId, world, BackupTrigger.MANUAL)));
    }

    private static List<DestinationType> types(List<BackupBackend> backends) {
        return backends.stream().map(BackupBackend::destinationType).toList();
    }

    private static CreateBackupRequest request(
            WorldId worldId,
            Path world,
            BackupTrigger trigger) {
        return new CreateBackupRequest(worldId, world, "World", Optional.empty(), trigger);
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
