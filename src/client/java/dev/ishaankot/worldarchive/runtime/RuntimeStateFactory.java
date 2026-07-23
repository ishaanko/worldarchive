package dev.ishaankot.worldarchive.runtime;

import dev.ishaankot.worldarchive.config.WorldArchiveConfig;
import dev.ishaankot.worldarchive.catalog.FileBackupDeletionRegistry;
import dev.ishaankot.worldarchive.config.WorldConfig;
import dev.ishaankot.worldarchive.core.BackupBackend;
import dev.ishaankot.worldarchive.core.BackupCaptureGate;
import dev.ishaankot.worldarchive.core.ConfiguredBackupDestinationSelector;
import dev.ishaankot.worldarchive.core.SerializedBackupCoordinator;
import dev.ishaankot.worldarchive.importing.FileBackupImportService;
import dev.ishaankot.worldarchive.importing.FileImportSourceRegistry;
import dev.ishaankot.worldarchive.model.WorldId;
import dev.ishaankot.worldarchive.recovery.BackupRecoveryService;
import dev.ishaankot.worldarchive.storage.git.GitBackendSettings;
import dev.ishaankot.worldarchive.storage.git.SystemGitCommandRunner;
import dev.ishaankot.worldarchive.storage.git.WorldGitSnapshotStore;
import dev.ishaankot.worldarchive.storage.zip.ZipBackupBackend;
import dev.ishaankot.worldarchive.storage.zip.ZipBackupStoreResolver;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/** Builds and primes one immutable runtime storage/service graph. */
final class RuntimeStateFactory {
    private final WorldArchiveRuntime runtime;

    RuntimeStateFactory(WorldArchiveRuntime runtime) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
    }

    RuntimeState build(WorldArchiveConfig config) {
        RuntimeStoragePaths storagePaths = RuntimeStoragePaths.from(
                config, runtime.storageRoot());
        WorldGitSnapshotStore gitBackend = gitBackend(config, storagePaths);
        ZipBackupStoreResolver zipStores = new RuntimeZipBackupStores(storagePaths);
        FileImportSourceRegistry importSources = new FileImportSourceRegistry(
                runtime.storageRoot().resolve("import-sources.json"));
        FileBackupDeletionRegistry deletions = new FileBackupDeletionRegistry(
                runtime.storageRoot().resolve("deleted-backups.txt"));
        Set<WorldId> configuredWorldIds = config.worlds().stream()
                .map(WorldConfig::worldId)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        FileBackupImportService imports = new FileBackupImportService(
                runtime.catalog(),
                importSources,
                deletions,
                gitBackend,
                zipStores,
                () -> configuredWorldIds,
                runtime.workerExecutor());
        RuntimeDestinationSelector selector = selector(config, gitBackend, zipStores);
        BackupRecoveryService recovery = new BackupRecoveryService(
                runtime.catalog(),
                Optional.of(gitBackend),
                Optional.of(zipStores),
                importSources,
                deletions,
                runtime.identityStore(),
                new MinecraftRestoredWorldMetadataFinalizer(),
                runtime.workerExecutor(),
                runtime.operationGate());
        SerializedBackupCoordinator coordinator = new SerializedBackupCoordinator(
                runtime.catalog(),
                runtime.captureFactory(),
                runtime.inventoryStore(),
                selector,
                recovery,
                BackupCaptureGate.DIRECT,
                runtime.captureMutex(),
                runtime.operationGate(),
                runtime.workerExecutor(),
                runtime.clock());
        return new RuntimeState(
                config, storagePaths, gitBackend, selector, coordinator, imports);
    }

    private WorldGitSnapshotStore gitBackend(
            WorldArchiveConfig config,
            RuntimeStoragePaths storagePaths) {
        return new WorldGitSnapshotStore(
                GitBackendSettings.from(config.git(), storagePaths.gitRepository()),
                GitBackendSettings.legacyFrom(config.git()),
                config.worlds().stream()
                        .filter(world -> world.remoteUrl().isPresent())
                        .collect(java.util.stream.Collectors.toUnmodifiableMap(
                                WorldConfig::worldId,
                                world -> world.remoteUrl().orElseThrow())),
                new SystemGitCommandRunner(),
                runtime.workerExecutor());
    }

    private RuntimeDestinationSelector selector(
            WorldArchiveConfig config,
            WorldGitSnapshotStore gitBackend,
            ZipBackupStoreResolver zipStores) {
        ZipBackupBackend zipBackend = new ZipBackupBackend(
                zipStores, runtime.workerExecutor());
        List<BackupBackend> backends = List.of(gitBackend, zipBackend);
        return new RuntimeDestinationSelector(new ConfiguredBackupDestinationSelector(
                () -> config, backends));
    }

    CompletionStage<Void> prime(RuntimeState state) {
        CompletionStage<Void> rebuild = state.imports().rebuildLocal().handle((summary, throwable) -> {
            if (throwable != null) {
                runtime.logFailure("Local backup catalog rebuild failed", throwable);
            } else if (summary != null && (summary.conflicts() > 0 || summary.issues() > 0)) {
                runtime.logFailure(
                        "Local backup catalog rebuild found conflicts or unreadable artifacts",
                        new IllegalStateException(summary.message()));
            }
            return null;
        });
        if (!state.config().git().enabled()) {
            state.selector().gitDisabled();
            return rebuild;
        }
        CompletionStage<Void> probe = state.gitBackend().probeTools().handle((health, throwable) -> {
            if (throwable != null || health == null) {
                state.selector().gitToolProbeFailed();
            } else {
                state.selector().gitToolsAvailable(health.available());
            }
            return null;
        });
        return CompletableFuture.allOf(
                rebuild.toCompletableFuture(),
                probe.toCompletableFuture());
    }
}
