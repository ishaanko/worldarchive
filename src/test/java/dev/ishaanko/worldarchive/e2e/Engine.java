package dev.ishaanko.worldarchive.e2e;

import dev.ishaanko.worldarchive.catalog.FileBackupCatalog;
import dev.ishaanko.worldarchive.config.DestinationTriggerConfig;
import dev.ishaanko.worldarchive.config.GitDestinationConfig;
import dev.ishaanko.worldarchive.config.StoragePolicy;
import dev.ishaanko.worldarchive.config.TriggerConfig;
import dev.ishaanko.worldarchive.config.WorldArchiveConfig;
import dev.ishaanko.worldarchive.config.WorldConfig;
import dev.ishaanko.worldarchive.config.WorldIdentityStore;
import dev.ishaanko.worldarchive.config.ZipDestinationConfig;
import dev.ishaanko.worldarchive.core.CreateBackupRequest;
import dev.ishaanko.worldarchive.core.DeleteBackupRequest;
import dev.ishaanko.worldarchive.core.FileSystemBackupCaptureFactory;
import dev.ishaanko.worldarchive.core.SerializedBackupCoordinator;
import dev.ishaanko.worldarchive.core.SourceCaptureObserver;
import dev.ishaanko.worldarchive.importing.FileBackupImportService;
import dev.ishaanko.worldarchive.importing.ImportPreview;
import dev.ishaanko.worldarchive.importing.ImportSummary;
import dev.ishaanko.worldarchive.model.BackupId;
import dev.ishaanko.worldarchive.model.BackupRecord;
import dev.ishaanko.worldarchive.model.BackupResult;
import dev.ishaanko.worldarchive.model.BackupTrigger;
import dev.ishaanko.worldarchive.model.DestinationType;
import dev.ishaanko.worldarchive.model.ProgressListener;
import dev.ishaanko.worldarchive.model.WorldId;
import dev.ishaanko.worldarchive.recovery.BackupRecoveryService;
import dev.ishaanko.worldarchive.recovery.RestoredWorldMetadataFinalizer;
import dev.ishaanko.worldarchive.runtime.RuntimeState;
import dev.ishaanko.worldarchive.runtime.RuntimeStoragePaths;
import dev.ishaanko.worldarchive.runtime.ServiceGraph;
import dev.ishaanko.worldarchive.storage.git.WorldGitSnapshotStore;
import dev.ishaanko.worldarchive.storage.management.ManagedStorageService;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * The real backup engine (capture, Git and ZIP destinations, catalog, recovery, import, and
 * cleanup), built through the production {@link ServiceGraph} and rooted in one temporary folder.
 * Closing and rebuilding an engine on the same folder models a game restart; a test that models
 * the start's catalog rebuild calls it itself.
 */
final class Engine implements AutoCloseable {
    static final Duration TIMEOUT = Duration.ofSeconds(60);

    final Path saves;

    final Path storage;

    final TestClock clock;

    final ServiceGraph graph;

    final WorldIdentityStore identities;

    final FileBackupCatalog catalog;

    final WorldGitSnapshotStore git;

    final RuntimeStoragePaths paths;

    final SerializedBackupCoordinator coordinator;

    final BackupRecoveryService recovery;

    final FileBackupImportService imports;

    final ManagedStorageService cleanup;

    private final ExecutorService executor = Executors.newThreadPerTaskExecutor(
            Thread.ofVirtual().name("e2e-worker-", 0).factory());

    Engine(Path root, TestClock clock, WorldArchiveConfig config, SourceCaptureObserver observer) {
        this.saves = root.resolve("saves");
        this.storage = root.resolve("worldarchive");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.graph = new ServiceGraph(
                storage,
                new FileSystemBackupCaptureFactory(storage.resolve("capture-temp"), Optional.empty(), observer),
                RestoredWorldMetadataFinalizer.NO_OP,
                executor,
                clock);
        RuntimeState state = graph.install(config).orElseThrow();
        try {
            await(graph.checkGitTools(state));
        } catch (Exception exception) {
            throw new IllegalStateException("Git could not be checked", exception);
        }
        this.identities = graph.identities();
        this.catalog = graph.catalog();
        this.git = state.git();
        this.paths = state.storagePaths();
        this.coordinator = state.coordinator();
        this.recovery = state.recovery();
        this.imports = state.imports();
        this.cleanup = state.storage();
    }

    /** Both destinations on, every trigger allowed, no per-world overrides. */
    static WorldArchiveConfig defaultConfig(WorldConfig... worlds) {
        return new WorldArchiveConfig(
                new TriggerConfig(true, true, true, TriggerConfig.DEFAULT_SCHEDULE_INTERVAL_MINUTES),
                GitDestinationConfig.defaults(),
                ZipDestinationConfig.defaults(),
                List.of(worlds));
    }

    /** A config with only one destination enabled. */
    static WorldArchiveConfig onlyDestination(DestinationType destination, WorldConfig... worlds) {
        WorldArchiveConfig defaults = defaultConfig(worlds);
        GitDestinationConfig git = defaults.git();
        return new WorldArchiveConfig(
                defaults.triggers(),
                new GitDestinationConfig(
                        destination == DestinationType.GIT,
                        git.repository(),
                        git.remoteName(),
                        DestinationTriggerConfig.defaults(),
                        git.lfsPatterns()),
                new ZipDestinationConfig(
                        destination == DestinationType.ZIP,
                        Optional.empty(),
                        DestinationTriggerConfig.defaults()),
                defaults.worlds());
    }

    static WorldConfig world(WorldId worldId, Path path, Optional<String> remote, StoragePolicy policy) {
        return new WorldConfig(worldId, true, path, remote, Optional.empty(), policy);
    }

    CompletionStage<BackupResult> backup(TestWorld world, BackupTrigger trigger, Optional<String> label) {
        return coordinator.createBackup(
                new CreateBackupRequest(world.id(), world.path(), world.name(), label, trigger),
                ProgressListener.NO_OP);
    }

    BackupResult backupNow(TestWorld world, BackupTrigger trigger) throws Exception {
        return await(backup(world, trigger, Optional.empty()));
    }

    List<BackupRecord> records(WorldId worldId) throws IOException {
        return catalog.list(worldId);
    }

    /** The delete a player confirms after seeing the backup as the catalog lists it now. */
    DeleteBackupRequest confirmedDelete(BackupId backupId) throws IOException {
        BackupRecord record = catalog.find(backupId).orElseThrow();
        return new DeleteBackupRequest(record.manifest().worldId(), backupId, record.result().destinations());
    }

    /** Deletes one backup the way the browser does, with the copies it lists now. */
    BackupResult delete(BackupId backupId, ProgressListener listener) throws Exception {
        return await(recovery.deleteBackups(List.of(confirmedDelete(backupId)), listener)).getFirst();
    }

    /** The backup list file, which tests damage or delete. */
    Path catalogFile() {
        return storage.resolve("catalog.json");
    }

    /** The folder that holds the world's ZIP folder, as the settings name it. */
    Path zipFolder(WorldId worldId) {
        return paths.zipDirectory(worldId);
    }

    /** Imports every backup the preview lists, as a player does who keeps the whole selection. */
    ImportSummary importAll(ImportPreview preview) throws Exception {
        return await(imports.execute(preview.token(), preview.items().stream()
                .map(item -> item.manifest().backupId())
                .collect(Collectors.toUnmodifiableSet())));
    }

    static <T> T await(CompletionStage<T> stage) throws Exception {
        return stage.toCompletableFuture().get(TIMEOUT.toSeconds(), TimeUnit.SECONDS);
    }

    @Override
    public void close() throws Exception {
        graph.close();
        executor.shutdownNow();
        if (!executor.awaitTermination(TIMEOUT.toSeconds(), TimeUnit.SECONDS)) {
            throw new IllegalStateException("Engine workers did not stop");
        }
    }

    /** A clock the test moves forward by hand, so backups land on chosen days. */
    static final class TestClock extends Clock {
        private volatile Instant now;

        TestClock(Instant start) {
            this.now = Objects.requireNonNull(start, "start");
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
