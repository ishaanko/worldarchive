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
import dev.ishaankot.worldarchive.storage.management.FileStorageHistoryStore;
import dev.ishaankot.worldarchive.storage.management.FileStorageReviewStore;
import dev.ishaankot.worldarchive.storage.management.ManagedStorageService;
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

    RuntimeState build(WorldArchiveConfig config, RuntimeServices services) {
        RuntimeStoragePaths storagePaths = RuntimeStoragePaths.from(
                config, services.storageRoot());
        WorldGitSnapshotStore gitBackend = gitBackend(config, storagePaths, services);
        ZipBackupStoreResolver zipStores = new RuntimeZipBackupStores(storagePaths);
        FileImportSourceRegistry importSources = new FileImportSourceRegistry(
                services.storageRoot().resolve("import-sources.json"));
        FileBackupDeletionRegistry deletions = new FileBackupDeletionRegistry(
                services.storageRoot().resolve("deleted-backups.txt"));
        Set<WorldId> configuredWorldIds = config.worlds().stream()
                .map(WorldConfig::worldId)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        FileBackupImportService imports = new FileBackupImportService(
                services.catalog(),
                importSources,
                deletions,
                gitBackend,
                zipStores,
                () -> configuredWorldIds,
                services.workerExecutor());
        RuntimeDestinationSelector selector = selector(config, gitBackend, zipStores, services);
        BackupRecoveryService recovery = new BackupRecoveryService(
                services.catalog(),
                Optional.of(gitBackend),
                Optional.of(zipStores),
                importSources,
                deletions,
                services.identityStore(),
                new MinecraftRestoredWorldMetadataFinalizer(),
                services.workerExecutor(),
                services.operationGate(),
                services.clock());
        SerializedBackupCoordinator coordinator = new SerializedBackupCoordinator(
                services.catalog(),
                services.captureFactory(),
                services.inventoryStore(),
                selector,
                recovery,
                BackupCaptureGate.DIRECT,
                services.captureMutex(),
                services.operationGate(),
                services.workerExecutor(),
                services.clock());
        ManagedStorageService storage = new ManagedStorageService(
                () -> config,
                services.catalog(),
                deletions,
                gitBackend,
                zipStores,
                new FileStorageHistoryStore(
                        services.storageRoot().resolve("storage-history")),
                new FileStorageReviewStore(
                        services.storageRoot().resolve("storage-reviews")),
                services.operationGate(),
                services.workerExecutor(),
                services.clock(),
                java.time.ZoneId.systemDefault());
        return new RuntimeState(
                config,
                storagePaths,
                gitBackend,
                selector,
                coordinator,
                imports,
                storage);
    }

    private WorldGitSnapshotStore gitBackend(
            WorldArchiveConfig config,
            RuntimeStoragePaths storagePaths,
            RuntimeServices services) {
        return new WorldGitSnapshotStore(
                GitBackendSettings.from(config.git(), storagePaths.gitRepository()),
                GitBackendSettings.legacyFrom(config.git()),
                config.worlds().stream()
                        .filter(world -> world.remoteUrl().isPresent())
                        .collect(java.util.stream.Collectors.toUnmodifiableMap(
                                WorldConfig::worldId,
                                world -> world.remoteUrl().orElseThrow())),
                new SystemGitCommandRunner(),
                services.workerExecutor());
    }

    private RuntimeDestinationSelector selector(
            WorldArchiveConfig config,
            WorldGitSnapshotStore gitBackend,
            ZipBackupStoreResolver zipStores,
            RuntimeServices services) {
        ZipBackupBackend zipBackend = new ZipBackupBackend(
                zipStores, services.workerExecutor());
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
