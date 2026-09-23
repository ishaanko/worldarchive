package dev.ishaanko.worldarchive.storage.management;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.ishaanko.worldarchive.catalog.BackupCatalog;
import dev.ishaanko.worldarchive.catalog.FileBackupCatalog;
import dev.ishaanko.worldarchive.catalog.FileBackupDeletionRegistry;
import dev.ishaanko.worldarchive.config.FolderOrigin;
import dev.ishaanko.worldarchive.config.GitDestinationConfig;
import dev.ishaanko.worldarchive.config.StoragePolicy;
import dev.ishaanko.worldarchive.config.TriggerConfig;
import dev.ishaanko.worldarchive.config.WorldArchiveConfig;
import dev.ishaanko.worldarchive.config.WorldConfig;
import dev.ishaanko.worldarchive.config.ZipDestinationConfig;
import dev.ishaanko.worldarchive.core.LockingWorldOperationGate;
import dev.ishaanko.worldarchive.core.TestCaptures;
import dev.ishaanko.worldarchive.core.WorldInventory;
import dev.ishaanko.worldarchive.model.BackupId;
import dev.ishaanko.worldarchive.model.BackupManifest;
import dev.ishaanko.worldarchive.model.BackupRecord;
import dev.ishaanko.worldarchive.model.BackupResult;
import dev.ishaanko.worldarchive.model.BackupTrigger;
import dev.ishaanko.worldarchive.model.DestinationResult;
import dev.ishaanko.worldarchive.model.DestinationType;
import dev.ishaanko.worldarchive.model.VerificationStatus;
import dev.ishaanko.worldarchive.model.WorldId;
import dev.ishaanko.worldarchive.storage.git.GitBackendSettings;
import dev.ishaanko.worldarchive.storage.git.SystemGitCommandRunner;
import dev.ishaanko.worldarchive.storage.git.WorldGitSnapshotStore;
import dev.ishaanko.worldarchive.storage.zip.ZipBackupArtifact;
import dev.ishaanko.worldarchive.storage.zip.ZipBackupStore;
import dev.ishaanko.worldarchive.storage.zip.ZipBackupStoreResolver;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Storage measurement, previews and cleanup failures on real ZIP archives and catalogs. */
final class ManagedStorageServiceTest {
    private static final Instant NOW = Instant.parse("2026-07-31T12:00:00Z");

    /** One byte of budget: cleanup goes as far as the keep settings allow. */
    private static final StoragePolicy OVER_BUDGET = new StoragePolicy(1, 0, 0, 0);

    @TempDir
    Path temporaryDirectory;

    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    private final WorldId worldId = WorldId.create();

    private final MutableClock clock = new MutableClock(NOW);

    @AfterEach
    void stopExecutor() {
        executor.shutdownNow();
    }

    @Test
    void nothingIsDeletedWhenTheBackupListCannotBeSaved() throws Exception {
        ZipBackupStore zips = new ZipBackupStore(temporaryDirectory.resolve("archives"), FolderOrigin.DEFAULT);
        CatalogThatCannotSave catalog = new CatalogThatCannotSave(temporaryDirectory.resolve("catalog.json"));
        ZipBackupArtifact old = backup(zips, catalog, NOW.minus(Duration.ofDays(2)));
        ZipBackupArtifact newest = backup(zips, catalog, NOW.minus(Duration.ofDays(1)));
        FileBackupDeletionRegistry deletions = new FileBackupDeletionRegistry(temporaryDirectory.resolve("deleted.txt"));
        ManagedStorageService service = service(OVER_BUDGET, catalog, deletions, zips);
        CleanupPlan plan = await(service.prepareCleanup(worldId));
        catalog.failSaving = true;

        ExecutionException failure = assertThrows(ExecutionException.class, () -> await(service.applyCleanup(
                new CleanupRequest(plan.confirmationToken(), Set.of(old.manifest().backupId())))));

        assertInstanceOf(IOException.class, failure.getCause());
        assertTrue(Files.exists(old.archivePath()) && Files.exists(newest.archivePath()));
        assertEquals(2, catalog.list(worldId).size());
        assertEquals(Set.of(), deletions.marked());
    }

    @Test
    void onlyTheNewestPreviewOfAWorldCanBeAppliedAndOnlyBeforeItExpires() throws Exception {
        ZipBackupStore zips = new ZipBackupStore(temporaryDirectory.resolve("archives"), FolderOrigin.DEFAULT);
        FileBackupCatalog catalog = new FileBackupCatalog(temporaryDirectory.resolve("catalog.json"));
        ZipBackupArtifact old = backup(zips, catalog, NOW.minus(Duration.ofDays(2)));
        backup(zips, catalog, NOW.minus(Duration.ofDays(1)));
        ManagedStorageService service = service(OVER_BUDGET, catalog, deletions(), zips);
        CleanupPlan replaced = await(service.prepareCleanup(worldId));
        CleanupPlan expired = await(service.prepareCleanup(worldId));
        clock.advance(Duration.ofMinutes(16));

        for (CleanupPlan stale : List.of(replaced, expired)) {
            ExecutionException refused = assertThrows(ExecutionException.class, () -> await(service.applyCleanup(
                    new CleanupRequest(stale.confirmationToken(), Set.of(old.manifest().backupId())))));
            assertTrue(refused.getCause().getMessage().contains("invalid, expired"), refused.getCause().getMessage());
        }
        assertTrue(Files.exists(old.archivePath()));
        CleanupPlan current = await(service.prepareCleanup(worldId));
        CleanupResult result = await(service.applyCleanup(
                new CleanupRequest(current.confirmationToken(), Set.of(old.manifest().backupId()))));

        assertEquals(Map.of(), result.failures());
        assertFalse(Files.exists(old.archivePath()));
        assertTrue(catalog.find(old.manifest().backupId()).isEmpty());
    }

    @Test
    void theOverviewCountsArchivesThatTheCatalogDoesNotList() throws Exception {
        ZipBackupStore zips = new ZipBackupStore(temporaryDirectory.resolve("archives"), FolderOrigin.DEFAULT);
        FileBackupCatalog unlisted = new FileBackupCatalog(temporaryDirectory.resolve("other-catalog.json"));
        ZipBackupArtifact orphan = backup(zips, unlisted, NOW.minus(Duration.ofDays(1)));
        FileBackupCatalog catalog = new FileBackupCatalog(temporaryDirectory.resolve("catalog.json"));

        StorageOverview overview = await(service(StoragePolicy.defaults(), catalog, deletions(), zips).overview(worldId));

        long expected = Files.size(orphan.archivePath())
                + Files.size(orphan.archivePath().resolveSibling(orphan.archivePath().getFileName() + ".sha256"));
        assertEquals(expected, overview.zipBytes());
        assertEquals(expected, overview.totalBytes());
    }

    @Test
    void theReviewNoticeNeedsABudgetAndCostsNoDiskAccessWithoutOne() throws Exception {
        ZipBackupStore zips = new ZipBackupStore(temporaryDirectory.resolve("archives"), FolderOrigin.DEFAULT);
        CatalogThatCannotSave catalog = new CatalogThatCannotSave(temporaryDirectory.resolve("catalog.json"));
        backup(zips, catalog, NOW.minus(Duration.ofDays(1)));
        catalog.failReading = true;

        assertFalse(await(service(StoragePolicy.defaults(), catalog, deletions(), zips).claimReviewNotice(worldId)));

        catalog.failReading = false;
        assertTrue(await(service(OVER_BUDGET, catalog, deletions(), zips).claimReviewNotice(worldId)));
    }

    @Test
    void theForecastHistoryKeepsOneSamplePerLocalDay() throws Exception {
        FileStorageHistoryStore history = new FileStorageHistoryStore(temporaryDirectory.resolve("history"));
        ZoneId newYork = ZoneId.of("America/New_York");
        Instant eveningBefore = Instant.parse("2026-07-31T01:00:00Z");
        Instant morning = Instant.parse("2026-07-31T13:00:00Z");
        Instant evening = Instant.parse("2026-08-01T02:00:00Z");

        history.record(worldId, new StorageSample(eveningBefore, 10), newYork);
        history.record(worldId, new StorageSample(morning, 20), newYork);
        history.record(worldId, new StorageSample(evening, 30), newYork);

        assertEquals(List.of(new StorageSample(eveningBefore, 10), new StorageSample(evening, 30)),
                history.load(worldId));
    }

    private ManagedStorageService service(
            StoragePolicy policy,
            BackupCatalog catalog,
            FileBackupDeletionRegistry deletions,
            ZipBackupStore zips) {
        WorldConfig world = new WorldConfig(
                worldId, true, temporaryDirectory.resolve("world"), Optional.empty(), Optional.empty(), policy);
        WorldArchiveConfig config = new WorldArchiveConfig(
                TriggerConfig.defaults(), GitDestinationConfig.defaults(), ZipDestinationConfig.defaults(), List.of(world));
        WorldGitSnapshotStore git = new WorldGitSnapshotStore(
                new GitBackendSettings(true, temporaryDirectory.resolve("git"), FolderOrigin.DEFAULT, "git", "origin",
                        Optional.empty(), GitDestinationConfig.DEFAULT_LFS_PATTERNS,
                        GitBackendSettings.DEFAULT_COMMAND_TIMEOUT, GitBackendSettings.DEFAULT_MAXIMUM_OUTPUT_BYTES),
                Map.of(),
                new SystemGitCommandRunner(),
                executor);
        ZipBackupStoreResolver stores = ignored -> zips;
        return new ManagedStorageService(
                () -> config,
                catalog,
                deletions,
                git,
                stores,
                new FileStorageHistoryStore(temporaryDirectory.resolve("history")),
                new LockingWorldOperationGate(),
                executor,
                clock,
                ZoneOffset.UTC);
    }

    private FileBackupDeletionRegistry deletions() {
        return new FileBackupDeletionRegistry(temporaryDirectory.resolve("deleted.txt"));
    }

    /** A verified ZIP backup of the world, listed in the catalog. */
    private ZipBackupArtifact backup(ZipBackupStore zips, BackupCatalog catalog, Instant createdAt) throws Exception {
        BackupId backupId = BackupId.create();
        Path source = Files.createDirectories(temporaryDirectory.resolve("source-" + backupId));
        Files.writeString(source.resolve("level.dat"), "world at " + createdAt);
        WorldInventory inventory = TestCaptures.inventoryOf(source);
        BackupManifest manifest = new BackupManifest(BackupManifest.CURRENT_FORMAT_VERSION, backupId, worldId,
                "Storage Test World", Optional.empty(), createdAt, BackupTrigger.SCHEDULED, inventory.fileCount(),
                inventory.byteCount(), inventory.fileCount(), inventory.contentSha256(), inventory.inventorySha256(),
                Optional.empty());
        ZipBackupArtifact artifact = zips.create(TestCaptures.of(source, manifest), bytes -> {
        });
        catalog.add(new BackupRecord(manifest, new BackupResult(backupId, worldId, List.of(
                DestinationResult.success(DestinationType.ZIP, artifact.artifactId())
                        .withVerification(VerificationStatus.VERIFIED)), createdAt.plusSeconds(1))));
        return artifact;
    }

    private static <T> T await(CompletionStage<T> stage) throws Exception {
        return stage.toCompletableFuture().get(30, TimeUnit.SECONDS);
    }

    /** The real catalog, which a test can make fail to save or to read, as a full or failing disk does. */
    private static final class CatalogThatCannotSave implements BackupCatalog {
        private final FileBackupCatalog catalog;

        private volatile boolean failSaving;

        private volatile boolean failReading;

        private CatalogThatCannotSave(Path file) {
            this.catalog = new FileBackupCatalog(file);
        }

        @Override
        public void add(BackupRecord record) throws IOException {
            catalog.add(record);
        }

        @Override
        public Optional<BackupRecord> find(BackupId backupId) throws IOException {
            requireReadable();
            return catalog.find(backupId);
        }

        @Override
        public List<BackupRecord> listAll() throws IOException {
            requireReadable();
            return catalog.listAll();
        }

        @Override
        public List<BackupRecord> list(WorldId worldId) throws IOException {
            requireReadable();
            return catalog.list(worldId);
        }

        @Override
        public boolean lostRecords() {
            return catalog.lostRecords();
        }

        @Override
        public Map<BackupId, Optional<BackupRecord>> updateAll(
                Map<BackupId, UnaryOperator<Optional<BackupRecord>>> changes) throws IOException {
            if (failSaving) {
                throw new IOException("The drive is full");
            }
            return catalog.updateAll(changes);
        }

        private void requireReadable() throws IOException {
            if (failReading) {
                throw new IOException("The catalog was read");
            }
        }
    }

    /** A clock the test moves by hand. */
    private static final class MutableClock extends Clock {
        private volatile Instant current;

        private MutableClock(Instant current) {
            this.current = current;
        }

        void advance(Duration duration) {
            current = current.plus(duration);
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
            return current;
        }
    }
}
