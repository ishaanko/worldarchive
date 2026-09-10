package dev.ishaanko.worldarchive.storage.management;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.ishaanko.worldarchive.catalog.FileBackupCatalog;
import dev.ishaanko.worldarchive.catalog.FileBackupDeletionRegistry;
import dev.ishaanko.worldarchive.config.GitDestinationConfig;
import dev.ishaanko.worldarchive.config.StoragePolicy;
import dev.ishaanko.worldarchive.config.TriggerConfig;
import dev.ishaanko.worldarchive.config.WorldArchiveConfig;
import dev.ishaanko.worldarchive.config.WorldConfig;
import dev.ishaanko.worldarchive.config.ZipDestinationConfig;
import dev.ishaanko.worldarchive.core.BackupCapture;
import dev.ishaanko.worldarchive.core.ProgressListener;
import dev.ishaanko.worldarchive.core.WorldInventory;
import dev.ishaanko.worldarchive.importing.FileBackupImportService;
import dev.ishaanko.worldarchive.importing.FileImportSourceRegistry;
import dev.ishaanko.worldarchive.model.BackupId;
import dev.ishaanko.worldarchive.model.BackupManifest;
import dev.ishaanko.worldarchive.model.BackupRecord;
import dev.ishaanko.worldarchive.model.BackupResult;
import dev.ishaanko.worldarchive.model.BackupTrigger;
import dev.ishaanko.worldarchive.model.DestinationResult;
import dev.ishaanko.worldarchive.model.DestinationType;
import dev.ishaanko.worldarchive.model.SyncStatus;
import dev.ishaanko.worldarchive.model.VerificationStatus;
import dev.ishaanko.worldarchive.model.WorldId;
import dev.ishaanko.worldarchive.storage.git.GitBackendSettings;
import dev.ishaanko.worldarchive.storage.git.SystemGitCommandRunner;
import dev.ishaanko.worldarchive.storage.git.WorldGitSnapshotStore;
import dev.ishaanko.worldarchive.storage.zip.ZipBackupArtifact;
import dev.ishaanko.worldarchive.storage.zip.ZipBackupStore;
import dev.ishaanko.worldarchive.storage.zip.ZipBackupStoreResolver;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Cleanup against real Git and Git LFS: what is deleted, what stays, what survives a restart. */
final class CleanupIntegrationTest {
    private static final Instant NOW = Instant.parse("2026-09-10T12:00:00Z");

    /** One byte of budget forces cleanup to go as far as the keep settings allow. */
    private static final StoragePolicy KEEP_ONE_DAILY = new StoragePolicy(1, 1, 0, 0);

    private static final StoragePolicy KEEP_ONLY_SAFETY_FLOOR = new StoragePolicy(1, 0, 0, 0);

    @TempDir
    Path temporaryDirectory;

    private final ExecutorService executor = Executors.newCachedThreadPool();

    private WorldId worldId;

    private Path remote;

    private WorldGitSnapshotStore git;

    private ZipBackupStore zipStore;

    private FileBackupCatalog catalog;

    private FileBackupDeletionRegistry deletions;

    @BeforeEach
    void setUp() throws Exception {
        worldId = WorldId.create();
        remote = temporaryDirectory.resolve("remote.git");
        nativeGit("init", "--bare", remote.toString());
        git = new WorldGitSnapshotStore(
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
                Map.of(worldId, remote.toUri().toString()),
                new SystemGitCommandRunner(),
                executor);
        Assumptions.assumeTrue(await(git.probeTools()).available(), "git and git-lfs required");
        zipStore = new ZipBackupStore(temporaryDirectory.resolve("archives"));
        catalog = new FileBackupCatalog(temporaryDirectory.resolve("catalog.json"));
        deletions = new FileBackupDeletionRegistry(temporaryDirectory.resolve("deleted.txt"));
    }

    @AfterEach
    void tearDown() {
        git.close();
        executor.shutdownNow();
    }

    @Test
    void unprotectedBackupsAreDeletedEverywhereAndTheirLfsObjectsFreed() throws Exception {
        BackupId oldest = backup(3, true);
        BackupId middle = backup(2, true);
        BackupId newest = backup(1, true);
        assertEquals(6, lfsObjectCount());

        ManagedStorageService service = service(KEEP_ONE_DAILY);
        CleanupPlan plan = await(service.prepareCleanup(worldId));
        assertEquals(Set.of(newest), plan.protectedBackups());
        Map<BackupId, CleanupItem> items = plan.items().stream()
                .collect(Collectors.toMap(CleanupItem::backupId, item -> item));
        for (BackupId deleted : List.of(oldest, middle)) {
            assertTrue(items.get(deleted).removeGit() && items.get(deleted).removeZip());
            assertTrue(items.get(deleted).removesRestorePoint());
        }
        assertFalse(items.get(newest).removesRestorePoint(),
                "the protected backup is only offered as a local Git eviction");

        CleanupResult result = await(service.applyCleanup(
                new CleanupRequest(plan.confirmationToken(), Set.of(oldest, middle))));
        assertEquals(Map.of(), result.failures());

        assertEquals(List.of(newest), catalog.list(worldId).stream()
                .map(record -> record.manifest().backupId()).toList());
        assertEquals(Set.of(newest), await(git.listCurrentSnapshots(worldId)).stream()
                .map(snapshot -> snapshot.backupId()).collect(Collectors.toSet()));
        assertEquals(Set.of(newest), remoteBackupIds());
        assertEquals(1, zipStore.listArchives().size());
        assertTrue(deletions.contains(oldest) && deletions.contains(middle));
        assertEquals(2, lfsObjectCount(), "only the newest snapshot's objects remain");
        assertTrue(await(git.verifyCurrentSnapshot(worldId, newest)).valid());
        assertTrue(result.reclaimedBytes() > 0);

        imports().rebuildLocal().toCompletableFuture().get(30, TimeUnit.SECONDS);
        assertEquals(List.of(newest), catalog.list(worldId).stream()
                .map(record -> record.manifest().backupId()).toList());
    }

    @Test
    void protectedBackupKeepsItsRemoteCopyWhenLocalGitIsEvicted() throws Exception {
        BackupId older = backup(2, true);
        BackupId newest = backup(1, false);

        ManagedStorageService service = service(KEEP_ONLY_SAFETY_FLOOR);
        CleanupPlan plan = await(service.prepareCleanup(worldId));
        assertEquals(Set.of(older), plan.protectedBackups(),
                "the verified safety floor is the only backup with a ZIP");
        Map<BackupId, CleanupItem> items = plan.items().stream()
                .collect(Collectors.toMap(CleanupItem::backupId, item -> item));
        assertTrue(items.get(newest).removeGit() && !items.get(newest).removeZip());
        assertTrue(items.get(newest).removesRestorePoint());
        assertTrue(items.get(older).removeGit() && !items.get(older).removeZip());
        assertFalse(items.get(older).removesRestorePoint());

        CleanupResult result = await(service.applyCleanup(
                new CleanupRequest(plan.confirmationToken(), items.keySet())));
        assertEquals(Map.of(), result.failures());

        assertEquals(Set.of(older), remoteBackupIds(), "the unprotected backup left the remote");
        assertTrue(await(git.listCurrentSnapshots(worldId)).isEmpty());
        assertEquals(0, lfsObjectCount());
        BackupRecord kept = catalog.find(older).orElseThrow();
        assertEquals(
                List.of(DestinationType.GIT, DestinationType.ZIP),
                kept.result().destinations().stream()
                        .map(DestinationResult::destination).toList());
        assertFalse(catalog.find(newest).isPresent());
    }

    private BackupId backup(int daysAgo, boolean withZip) throws Exception {
        BackupId backupId = BackupId.create();
        Path source = Files.createDirectories(temporaryDirectory.resolve("world-" + backupId));
        byte[] level = ("level " + backupId).getBytes(StandardCharsets.UTF_8);
        byte[] region = ("region " + backupId).getBytes(StandardCharsets.UTF_8);
        Files.write(source.resolve("level.dat"), level);
        Files.write(source.resolve("r.0.0.mca"), region);
        WorldInventory inventory = WorldInventory.create(List.of(
                new WorldInventory.Entry("level.dat", level.length, sha256(level)),
                new WorldInventory.Entry("r.0.0.mca", region.length, sha256(region))));
        BackupManifest manifest = BackupManifest.create(
                backupId,
                worldId,
                "Cleanup World",
                Optional.empty(),
                NOW.minus(Duration.ofDays(daysAgo)),
                BackupTrigger.SCHEDULED,
                inventory.fileCount(),
                inventory.byteCount(),
                inventory.fileCount(),
                inventory.contentSha256(),
                inventory.inventorySha256());
        BackupCapture capture = new BackupCapture(source, manifest);
        DestinationResult gitResult = await(git.createBackup(capture, ProgressListener.NO_OP));
        assertEquals(SyncStatus.SYNCED, gitResult.syncStatus());
        List<DestinationResult> destinations = new ArrayList<>();
        destinations.add(gitResult);
        if (withZip) {
            ZipBackupArtifact artifact = zipStore.create(capture);
            destinations.add(DestinationResult.success(DestinationType.ZIP, artifact.artifactId())
                    .withVerification(VerificationStatus.VERIFIED));
        }
        catalog.add(new BackupRecord(
                manifest,
                BackupResult.aggregate(backupId, worldId, destinations, NOW)));
        return backupId;
    }

    private ManagedStorageService service(StoragePolicy policy) {
        WorldConfig world = new WorldConfig(
                worldId,
                true,
                temporaryDirectory.resolve("live-world"),
                Optional.of(remote.toUri().toString()),
                Optional.empty(),
                policy);
        WorldArchiveConfig config = new WorldArchiveConfig(
                WorldArchiveConfig.CURRENT_SCHEMA_VERSION,
                TriggerConfig.defaults(),
                GitDestinationConfig.defaults(),
                ZipDestinationConfig.defaults(),
                List.of(world));
        return new ManagedStorageService(
                () -> config,
                catalog,
                deletions,
                git,
                zipStore,
                new FileStorageHistoryStore(temporaryDirectory.resolve("history")),
                new FileStorageReviewStore(temporaryDirectory.resolve("reviews")),
                ignored -> () -> {
                },
                executor,
                Clock.fixed(NOW, ZoneOffset.UTC),
                ZoneOffset.UTC);
    }

    /** The catalog rebuild that runs at every game start. */
    private FileBackupImportService imports() {
        ZipBackupStoreResolver stores = new ZipBackupStoreResolver() {
            @Override
            public ZipBackupStore store(WorldId ignored) {
                return zipStore;
            }

            @Override
            public ZipBackupStore defaultStore() {
                return zipStore;
            }
        };
        return new FileBackupImportService(
                catalog,
                new FileImportSourceRegistry(temporaryDirectory.resolve("sources.json")),
                deletions,
                git,
                stores,
                () -> Set.of(worldId),
                executor);
    }

    private Set<BackupId> remoteBackupIds() throws Exception {
        return nativeGit("--git-dir=" + remote, "for-each-ref", "--format=%(refname)",
                        "refs/heads/backups/")
                .lines()
                .filter(line -> !line.isBlank())
                .map(line -> BackupId.parse(line.substring(line.length() - 36)))
                .collect(Collectors.toSet());
    }

    private long lfsObjectCount() throws Exception {
        Path objects = git.repositoryFor(worldId).resolve("lfs").resolve("objects");
        if (!Files.isDirectory(objects)) {
            return 0;
        }
        try (Stream<Path> paths = Files.walk(objects)) {
            return paths.filter(Files::isRegularFile).count();
        }
    }

    private String nativeGit(String... arguments) throws Exception {
        List<String> command = new ArrayList<>();
        command.add("git");
        command.addAll(List.of(arguments));
        Process process = new ProcessBuilder(command)
                .directory(temporaryDirectory.toFile())
                .redirectErrorStream(true)
                .start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(process.waitFor(30, TimeUnit.SECONDS));
        assertEquals(0, process.exitValue(), output);
        return output;
    }

    private static <T> T await(CompletionStage<T> stage) throws Exception {
        return stage.toCompletableFuture().get(60, TimeUnit.SECONDS);
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
