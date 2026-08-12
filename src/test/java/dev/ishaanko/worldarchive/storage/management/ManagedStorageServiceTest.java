package dev.ishaanko.worldarchive.storage.management;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.ishaanko.worldarchive.catalog.BackupCatalog;
import dev.ishaanko.worldarchive.catalog.BackupDeletionRegistry;
import dev.ishaanko.worldarchive.catalog.FileBackupDeletionRegistry;
import dev.ishaanko.worldarchive.config.GitDestinationConfig;
import dev.ishaanko.worldarchive.config.StoragePolicy;
import dev.ishaanko.worldarchive.config.TriggerConfig;
import dev.ishaanko.worldarchive.config.WorldArchiveConfig;
import dev.ishaanko.worldarchive.config.WorldConfig;
import dev.ishaanko.worldarchive.config.ZipDestinationConfig;
import dev.ishaanko.worldarchive.core.BackupCapture;
import dev.ishaanko.worldarchive.core.WorldInventory;
import dev.ishaanko.worldarchive.core.WorldOperationGate;
import dev.ishaanko.worldarchive.model.BackupId;
import dev.ishaanko.worldarchive.model.BackupManifest;
import dev.ishaanko.worldarchive.model.BackupRecord;
import dev.ishaanko.worldarchive.model.BackupResult;
import dev.ishaanko.worldarchive.model.BackupTrigger;
import dev.ishaanko.worldarchive.model.DestinationResult;
import dev.ishaanko.worldarchive.model.DestinationType;
import dev.ishaanko.worldarchive.model.SensitiveDataRedactor;
import dev.ishaanko.worldarchive.model.SyncStatus;
import dev.ishaanko.worldarchive.model.VerificationStatus;
import dev.ishaanko.worldarchive.model.WorldId;
import dev.ishaanko.worldarchive.storage.git.GitBackendSettings;
import dev.ishaanko.worldarchive.storage.git.GitCommandResult;
import dev.ishaanko.worldarchive.storage.git.GitCommandRunner;
import dev.ishaanko.worldarchive.storage.git.GitSnapshot;
import dev.ishaanko.worldarchive.storage.git.WorldGitSnapshotStore;
import dev.ishaanko.worldarchive.storage.zip.ZipBackupArtifact;
import dev.ishaanko.worldarchive.storage.zip.ZipBackupStore;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class ManagedStorageServiceTest {
    private static final Instant NOW = Instant.parse("2026-07-31T12:00:00Z");

    private static final StoragePolicy CLEANUP_POLICY =
            new StoragePolicy(1, 0, 0, 0);

    private static final String SECRET = "top-secret-value";

    private static final GitCommandRunner EMPTY_GIT = command ->
            new GitCommandResult(0, "", "", false, false);

    @TempDir
    Path temporaryDirectory;

    @Test
    void overviewSurvivesStorageHistoryAppendFailure() throws Exception {
        WorldId worldId = WorldId.create();
        Path blockedHistory = temporaryDirectory.resolve("blocked-history");
        Files.writeString(blockedHistory, "not a directory", StandardCharsets.UTF_8);
        ZipBackupStore zipStore = new ZipBackupStore(
                temporaryDirectory.resolve("archives"));

        try (Fixture fixture = fixture(
                worldId,
                StoragePolicy.defaults(),
                new InMemoryCatalog(),
                zipStore,
                blockedHistory,
                Clock.fixed(NOW, ZoneOffset.UTC),
                EMPTY_GIT)) {
            StorageOverview overview = await(fixture.service().overview(worldId));

            assertEquals(0, overview.totalBytes());
            assertEquals(StorageForecast.State.DISABLED, overview.forecast().state());
        }
    }

    @Test
    void cleanupPreservesZipWhenCatalogRemovalFails()
            throws Exception {
        WorldId worldId = WorldId.create();
        ZipBackupStore zipStore = new ZipBackupStore(
                temporaryDirectory.resolve("archives"));
        CreatedBackup old = createBackup(
                zipStore,
                worldId,
                NOW.minus(Duration.ofDays(2)),
                "old world");
        CreatedBackup safety = createBackup(
                zipStore,
                worldId,
                NOW.minus(Duration.ofDays(1)),
                "safe world");
        InMemoryCatalog catalog = new InMemoryCatalog(old.record(), safety.record());
        catalog.failRemoval(old.backupId());
        FileBackupDeletionRegistry deletions = new FileBackupDeletionRegistry(
                temporaryDirectory.resolve("deleted-backups.txt"));
        Path blockedHistory = temporaryDirectory.resolve("blocked-history");
        Files.writeString(blockedHistory, "not a directory", StandardCharsets.UTF_8);

        try (Fixture fixture = fixture(
                worldId,
                CLEANUP_POLICY,
                catalog,
                deletions,
                zipStore,
                blockedHistory,
                Clock.fixed(NOW, ZoneOffset.UTC),
                EMPTY_GIT)) {
            CleanupPlan plan = await(fixture.service().prepareCleanup(worldId));
            CleanupResult result = await(fixture.service().applyCleanup(
                    new CleanupRequest(
                            plan.confirmationToken(),
                            Set.of(old.backupId()))));

            String failure = result.failures().get(old.backupId());
            assertTrue(failure.contains(SensitiveDataRedactor.REDACTED));
            assertFalse(failure.contains(SECRET));
            assertTrue(Files.exists(old.artifact().archivePath()));
            assertTrue(Files.exists(safety.artifact().archivePath()));
            assertTrue(catalog.find(old.backupId()).isPresent());
            assertFalse(deletions.contains(old.backupId()));
        }
    }

    @Test
    void newPreviewInvalidatesThePreviousPreviewForTheSameWorld()
            throws Exception {
        WorldId worldId = WorldId.create();
        ZipBackupStore zipStore = new ZipBackupStore(
                temporaryDirectory.resolve("archives"));
        CreatedBackup old = createBackup(
                zipStore,
                worldId,
                NOW.minus(Duration.ofDays(2)),
                "old world");
        CreatedBackup safety = createBackup(
                zipStore,
                worldId,
                NOW.minus(Duration.ofDays(1)),
                "safe world");
        InMemoryCatalog catalog = new InMemoryCatalog(old.record(), safety.record());

        try (Fixture fixture = fixture(
                worldId,
                CLEANUP_POLICY,
                catalog,
                zipStore,
                temporaryDirectory.resolve("history"),
                Clock.fixed(NOW, ZoneOffset.UTC),
                EMPTY_GIT)) {
            CleanupPlan first = await(fixture.service().prepareCleanup(worldId));
            CleanupPlan second = await(fixture.service().prepareCleanup(worldId));

            assertNotEquals(first.confirmationToken(), second.confirmationToken());
            IOException failure = awaitIoFailure(fixture.service().applyCleanup(
                    new CleanupRequest(
                            first.confirmationToken(),
                            Set.of(old.backupId()))));
            assertTrue(failure.getMessage().contains("invalid"));
            assertTrue(Files.exists(old.artifact().archivePath()));

            CleanupResult result = await(fixture.service().applyCleanup(
                    new CleanupRequest(
                            second.confirmationToken(),
                            Set.of(old.backupId()))));
            assertTrue(result.failures().isEmpty());
            assertFalse(Files.exists(old.artifact().archivePath()));
        }
    }

    @Test
    void expiredPreviewIsRejectedBeforeCleanupStarts() throws Exception {
        WorldId worldId = WorldId.create();
        ZipBackupStore zipStore = new ZipBackupStore(
                temporaryDirectory.resolve("archives"));
        CreatedBackup old = createBackup(
                zipStore,
                worldId,
                NOW.minus(Duration.ofDays(2)),
                "old world");
        CreatedBackup safety = createBackup(
                zipStore,
                worldId,
                NOW.minus(Duration.ofDays(1)),
                "safe world");
        MutableClock clock = new MutableClock(NOW, ZoneOffset.UTC);

        try (Fixture fixture = fixture(
                worldId,
                CLEANUP_POLICY,
                new InMemoryCatalog(old.record(), safety.record()),
                zipStore,
                temporaryDirectory.resolve("history"),
                clock,
                EMPTY_GIT)) {
            CleanupPlan plan = await(fixture.service().prepareCleanup(worldId));
            clock.advance(Duration.ofMinutes(16));

            IOException failure = awaitIoFailure(fixture.service().applyCleanup(
                    new CleanupRequest(
                            plan.confirmationToken(),
                            Set.of(old.backupId()))));

            assertTrue(failure.getMessage().contains("expired"));
            assertTrue(Files.exists(old.artifact().archivePath()));
        }
    }

    @Test
    void overviewCountsCompleteZipArchiveWithoutCatalogRecord()
            throws Exception {
        WorldId worldId = WorldId.create();
        ZipBackupStore zipStore = new ZipBackupStore(
                temporaryDirectory.resolve("archives"));
        CreatedBackup orphan = createBackup(
                zipStore,
                worldId,
                NOW.minus(Duration.ofDays(1)),
                "orphan world");
        long expectedBytes = Math.addExact(
                Files.size(orphan.artifact().archivePath()),
                Files.size(orphan.artifact().checksumPath()));

        try (Fixture fixture = fixture(
                worldId,
                StoragePolicy.defaults(),
                new InMemoryCatalog(),
                zipStore,
                temporaryDirectory.resolve("history"),
                Clock.fixed(NOW, ZoneOffset.UTC),
                EMPTY_GIT)) {
            StorageOverview overview = await(fixture.service().overview(worldId));

            assertEquals(expectedBytes, overview.zipBytes());
            assertEquals(expectedBytes, overview.totalBytes());
        }
    }

    @Test
    void orphanGitRefDisablesWholeGitHistoryCleanup() throws Exception {
        WorldId worldId = WorldId.create();
        BackupId orphanId = BackupId.create();
        ZipBackupStore zipStore = new ZipBackupStore(
                temporaryDirectory.resolve("archives"));
        CreatedBackup safety = createBackup(
                zipStore,
                worldId,
                NOW.minus(Duration.ofDays(1)),
                "safe world");
        String listing = GitSnapshot.refName(worldId, orphanId)
                + "\t" + "a".repeat(40)
                + "\t" + NOW.getEpochSecond()
                + System.lineSeparator();
        GitCommandRunner orphanGit = command -> {
            if (command.arguments().contains("rev-parse")) {
                return new GitCommandResult(0, "true\n", "", false, false);
            }
            if (command.arguments().contains("for-each-ref")) {
                return new GitCommandResult(0, listing, "", false, false);
            }
            return new GitCommandResult(0, "", "", false, false);
        };

        try (Fixture fixture = fixture(
                worldId,
                CLEANUP_POLICY,
                new InMemoryCatalog(safety.record()),
                zipStore,
                temporaryDirectory.resolve("history"),
                Clock.fixed(NOW, ZoneOffset.UTC),
                orphanGit)) {
            Path repository = Files.createDirectories(
                    fixture.git().repositoryFor(worldId));
            Files.write(repository.resolve("objects.bin"), new byte[4_096]);

            CleanupPlan plan = await(fixture.service().prepareCleanup(worldId));

            assertTrue(plan.items().isEmpty());
            assertFalse(plan.targetReachable());
        }
    }

    @Test
    void staleSynchronizedStatusCannotAuthorizeLastLocalGitCopyRemoval()
            throws Exception {
        WorldId worldId = WorldId.create();
        BackupId gitBackupId = BackupId.create();
        ZipBackupStore zipStore = new ZipBackupStore(
                temporaryDirectory.resolve("archives"));
        CreatedBackup safety = createBackup(
                zipStore,
                worldId,
                NOW.minus(Duration.ofDays(1)),
                "safe world");
        BackupRecord gitRecord = gitRecord(
                worldId,
                gitBackupId,
                NOW.minus(Duration.ofDays(2)));
        String listing = GitSnapshot.refName(worldId, gitBackupId)
                + "\t" + "a".repeat(40)
                + "\t" + gitRecord.manifest().createdAt().getEpochSecond()
                + System.lineSeparator();
        GitCommandRunner localGit = command -> {
            if (command.arguments().contains("rev-parse")) {
                return new GitCommandResult(0, "true\n", "", false, false);
            }
            if (command.arguments().contains("for-each-ref")) {
                return new GitCommandResult(0, listing, "", false, false);
            }
            return new GitCommandResult(0, "", "", false, false);
        };

        try (Fixture fixture = fixture(
                worldId,
                new StoragePolicy(1, 2, 0, 0),
                new InMemoryCatalog(safety.record(), gitRecord),
                zipStore,
                temporaryDirectory.resolve("history"),
                Clock.fixed(NOW, ZoneOffset.UTC),
                localGit)) {
            Path repository = Files.createDirectories(
                    fixture.git().repositoryFor(worldId));
            Files.write(repository.resolve("objects.bin"), new byte[4_096]);

            CleanupPlan plan = await(fixture.service().prepareCleanup(worldId));

            assertTrue(plan.items().stream()
                    .noneMatch(CleanupItem::removeLocalGit));
            assertTrue(Files.exists(repository.resolve("objects.bin")));
        }
    }

    private Fixture fixture(
            WorldId worldId,
            StoragePolicy policy,
            BackupCatalog catalog,
            ZipBackupStore zipStore,
            Path historyDirectory,
            Clock clock,
            GitCommandRunner gitRunner) {
        return fixture(
                worldId,
                policy,
                catalog,
                BackupDeletionRegistry.NONE,
                zipStore,
                historyDirectory,
                clock,
                gitRunner);
    }

    private Fixture fixture(
            WorldId worldId,
            StoragePolicy policy,
            BackupCatalog catalog,
            BackupDeletionRegistry deletions,
            ZipBackupStore zipStore,
            Path historyDirectory,
            Clock clock,
            GitCommandRunner gitRunner) {
        ExecutorService gitExecutor = Executors.newSingleThreadExecutor();
        WorldGitSnapshotStore git = new WorldGitSnapshotStore(
                new GitBackendSettings(
                        true,
                        temporaryDirectory.resolve("git"),
                        "git",
                        "origin",
                        Optional.empty(),
                        GitDestinationConfig.DEFAULT_LFS_PATTERNS,
                        GitBackendSettings.DEFAULT_COMMAND_TIMEOUT,
                        GitBackendSettings.DEFAULT_MAXIMUM_OUTPUT_BYTES),
                Optional.empty(),
                gitRunner,
                gitExecutor);
        WorldConfig world = new WorldConfig(
                worldId,
                true,
                temporaryDirectory.resolve("configured-world"),
                Optional.empty(),
                Optional.empty(),
                policy);
        WorldArchiveConfig configuration = new WorldArchiveConfig(
                WorldArchiveConfig.CURRENT_SCHEMA_VERSION,
                TriggerConfig.defaults(),
                GitDestinationConfig.defaults(),
                ZipDestinationConfig.defaults(),
                List.of(world));
        ManagedStorageService service = new ManagedStorageService(
                () -> configuration,
                catalog,
                deletions,
                git,
                zipStore,
                new FileStorageHistoryStore(historyDirectory),
                new FileStorageReviewStore(temporaryDirectory.resolve("reviews")),
                noOpGate(),
                Runnable::run,
                clock,
                ZoneOffset.UTC);
        return new Fixture(service, git, gitExecutor);
    }

    private CreatedBackup createBackup(
            ZipBackupStore store,
            WorldId worldId,
            Instant createdAt,
            String contents) throws Exception {
        BackupId backupId = BackupId.create();
        Path source = Files.createDirectories(
                temporaryDirectory.resolve("source-" + backupId));
        byte[] bytes = contents.getBytes(StandardCharsets.UTF_8);
        Files.write(source.resolve("level.dat"), bytes);
        WorldInventory inventory = WorldInventory.create(List.of(
                new WorldInventory.Entry("level.dat", bytes.length, sha256(bytes))));
        BackupManifest manifest = BackupManifest.create(
                backupId,
                worldId,
                "Storage Test World",
                Optional.empty(),
                createdAt,
                BackupTrigger.SCHEDULED,
                inventory.fileCount(),
                inventory.byteCount(),
                inventory.fileCount(),
                inventory.contentSha256(),
                inventory.inventorySha256());
        ZipBackupArtifact artifact = store.create(
                new BackupCapture(source, manifest));
        DestinationResult destination = DestinationResult.success(
                        DestinationType.ZIP,
                        artifact.artifactId())
                .withVerification(VerificationStatus.VERIFIED);
        BackupRecord record = new BackupRecord(
                manifest,
                BackupResult.aggregate(
                        backupId,
                        worldId,
                        List.of(destination),
                        createdAt.plusSeconds(1)));
        return new CreatedBackup(record, artifact);
    }

    private static BackupRecord gitRecord(
            WorldId worldId,
            BackupId backupId,
            Instant createdAt) {
        BackupManifest manifest = BackupManifest.create(
                backupId,
                worldId,
                "Git Storage Test World",
                Optional.empty(),
                createdAt,
                BackupTrigger.SCHEDULED,
                1,
                10,
                1,
                "c".repeat(64),
                "d".repeat(64));
        DestinationResult destination = DestinationResult.success(
                        DestinationType.GIT,
                        GitSnapshot.refName(worldId, backupId))
                .withSync(SyncStatus.SYNCED);
        return new BackupRecord(
                manifest,
                BackupResult.aggregate(
                        backupId,
                        worldId,
                        List.of(destination),
                        createdAt.plusSeconds(1)));
    }

    private static WorldOperationGate noOpGate() {
        return worldId -> () -> {
        };
    }

    private static String sha256(byte[] bytes) {
        try {
            return java.util.HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(
                    "The Java runtime does not provide SHA-256",
                    exception);
        }
    }

    private static <T> T await(CompletionStage<T> stage) throws Exception {
        return stage.toCompletableFuture().get(10, TimeUnit.SECONDS);
    }

    private static IOException awaitIoFailure(CompletionStage<?> stage) {
        ExecutionException failure = assertThrows(
                ExecutionException.class,
                () -> stage.toCompletableFuture().get(10, TimeUnit.SECONDS));
        return assertInstanceOf(IOException.class, failure.getCause());
    }

    private record CreatedBackup(
            BackupRecord record,
            ZipBackupArtifact artifact) {
        BackupId backupId() {
            return record.manifest().backupId();
        }
    }

    private record Fixture(
            ManagedStorageService service,
            WorldGitSnapshotStore git,
            ExecutorService gitExecutor) implements AutoCloseable {
        @Override
        public void close() {
            git.close();
            gitExecutor.shutdownNow();
        }
    }

    private static final class MutableClock extends Clock {
        private Instant current;

        private final ZoneId zone;

        private MutableClock(Instant current, ZoneId zone) {
            this.current = current;
            this.zone = zone;
        }

        void advance(Duration duration) {
            current = current.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return zone;
        }

        @Override
        public Clock withZone(ZoneId requestedZone) {
            return new MutableClock(current, requestedZone);
        }

        @Override
        public Instant instant() {
            return current;
        }
    }

    private static final class InMemoryCatalog implements BackupCatalog {
        private final Map<BackupId, BackupRecord> records = new LinkedHashMap<>();

        private BackupId failingRemoval;

        private InMemoryCatalog(BackupRecord... records) {
            for (BackupRecord record : records) {
                this.records.put(record.manifest().backupId(), record);
            }
        }

        void failRemoval(BackupId backupId) {
            failingRemoval = backupId;
        }

        @Override
        public synchronized void add(BackupRecord record) throws IOException {
            BackupRecord previous = records.putIfAbsent(
                    record.manifest().backupId(),
                    record);
            if (previous != null && !previous.equals(record)) {
                throw new IOException("Catalog record conflict");
            }
        }

        @Override
        public synchronized Optional<BackupRecord> find(BackupId backupId) {
            return Optional.ofNullable(records.get(backupId));
        }

        @Override
        public synchronized List<BackupRecord> listAll() {
            return List.copyOf(records.values());
        }

        @Override
        public synchronized List<BackupRecord> list(WorldId worldId) {
            return records.values().stream()
                    .filter(record -> record.manifest().worldId().equals(worldId))
                    .toList();
        }

        @Override
        public synchronized Optional<BackupRecord> update(
                BackupId backupId,
                UnaryOperator<BackupRecord> update) {
            BackupRecord current = records.get(backupId);
            if (current == null) {
                return Optional.empty();
            }
            BackupRecord replacement = update.apply(current);
            records.put(backupId, replacement);
            return Optional.of(replacement);
        }

        @Override
        public synchronized boolean remove(BackupId backupId) throws IOException {
            if (backupId.equals(failingRemoval)) {
                throw new IOException("token=" + SECRET);
            }
            return records.remove(backupId) != null;
        }
    }
}
