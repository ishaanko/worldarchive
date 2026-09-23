package dev.ishaanko.worldarchive.importing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.ishaanko.worldarchive.catalog.FileBackupCatalog;
import dev.ishaanko.worldarchive.catalog.FileBackupDeletionRegistry;
import dev.ishaanko.worldarchive.config.FolderOrigin;
import dev.ishaanko.worldarchive.core.LockingWorldOperationGate;
import dev.ishaanko.worldarchive.core.TestCaptures;
import dev.ishaanko.worldarchive.core.WorldInventory;
import dev.ishaanko.worldarchive.model.ArtifactOwnership;
import dev.ishaanko.worldarchive.model.BackupId;
import dev.ishaanko.worldarchive.model.BackupManifest;
import dev.ishaanko.worldarchive.model.BackupTrigger;
import dev.ishaanko.worldarchive.model.DestinationResult;
import dev.ishaanko.worldarchive.model.VerificationStatus;
import dev.ishaanko.worldarchive.model.WorldId;
import dev.ishaanko.worldarchive.storage.git.GitBackendSettings;
import dev.ishaanko.worldarchive.storage.git.SystemGitCommandRunner;
import dev.ishaanko.worldarchive.storage.git.WorldGitSnapshotStore;
import dev.ishaanko.worldarchive.storage.zip.ZipBackupArtifact;
import dev.ishaanko.worldarchive.storage.zip.ZipBackupStore;
import dev.ishaanko.worldarchive.storage.zip.ZipBackupStoreResolver;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** ZIP imports and the search for stored backups, on real archives, catalogs and registries. */
final class FileBackupImportServiceTest {
    private static final Instant NOW = Instant.parse("2026-07-31T12:00:00Z");

    @TempDir
    Path temporaryDirectory;

    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    private final MutableClock clock = new MutableClock(NOW);

    @AfterEach
    void stopExecutor() {
        executor.shutdownNow();
    }

    @Test
    void importsOnlyTheBackupsSelectedFromThePreview() throws Exception {
        ZipBackupStore source = new ZipBackupStore(temporaryDirectory.resolve("source"), FolderOrigin.DEFAULT);
        ZipBackupArtifact first = sourceBackup(source, "first");
        ZipBackupArtifact second = sourceBackup(source, "second");
        Imports imports = imports("selection");

        ImportPreview preview = imports.service().previewZip(source.root()).toCompletableFuture().join();
        ImportSummary summary = imports.service().execute(preview.token(), Set.of(first.manifest().backupId()))
                .toCompletableFuture().join();

        assertEquals(1, summary.added());
        assertTrue(imports.catalog().find(first.manifest().backupId()).isPresent());
        assertTrue(imports.catalog().find(second.manifest().backupId()).isEmpty());
    }

    @Test
    void aDiscardedOrExpiredPreviewCannotRun() throws Exception {
        ZipBackupStore source = new ZipBackupStore(temporaryDirectory.resolve("source"), FolderOrigin.DEFAULT);
        sourceBackup(source, "only");
        Imports imports = imports("expiry");
        ImportPreview discarded = imports.service().previewZip(source.root()).toCompletableFuture().join();
        ImportPreview expired = imports.service().previewZip(source.root()).toCompletableFuture().join();

        imports.service().discard(discarded.token()).toCompletableFuture().join();
        clock.advance(FileBackupImportService.PREVIEW_LIFETIME);

        for (ImportPreview preview : List.of(discarded, expired)) {
            assertThrows(CompletionException.class,
                    () -> importAll(imports, preview));
        }
        assertTrue(imports.catalog().listAll().isEmpty());
    }

    @Test
    void anArchiveThatChangedAfterThePreviewIsAnIssueAndTheOthersStillImport() throws Exception {
        ZipBackupStore source = new ZipBackupStore(temporaryDirectory.resolve("source"), FolderOrigin.DEFAULT);
        ZipBackupArtifact changed = sourceBackup(source, "changed");
        ZipBackupArtifact intact = sourceBackup(source, "intact");
        Imports imports = imports("changed");
        ImportPreview preview = imports.service().previewZip(source.root()).toCompletableFuture().join();
        Files.write(changed.archivePath(), new byte[] {1}, StandardOpenOption.APPEND);

        ImportSummary summary = importAll(imports, preview);

        assertEquals(1, summary.added());
        assertEquals(1, summary.issues());
        assertTrue(imports.catalog().find(changed.manifest().backupId()).isEmpty());
        assertTrue(imports.catalog().find(intact.manifest().backupId()).isPresent());
        assertTrue(Files.isRegularFile(changed.archivePath()));
    }

    @Test
    void importingTheSameArchiveTwiceChangesNothingAndNeverTouchesTheSource() throws Exception {
        ZipBackupStore source = new ZipBackupStore(temporaryDirectory.resolve("source"), FolderOrigin.DEFAULT);
        ZipBackupArtifact archive = sourceBackup(source, "idempotent");
        Imports imports = imports("idempotent");

        ImportPreview preview = imports.service().previewZip(source.root()).toCompletableFuture().join();
        assertEquals(ImportDisposition.ADD, preview.items().getFirst().disposition());
        assertEquals(1, importAll(imports, preview).added());
        ImportPreview repeated = imports.service().previewZip(source.root()).toCompletableFuture().join();

        assertEquals(ImportDisposition.UNCHANGED, repeated.items().getFirst().disposition());
        assertEquals(1, importAll(imports, repeated).unchanged());
        assertEquals(ArtifactOwnership.MANAGED, imports.catalog().find(archive.manifest().backupId()).orElseThrow()
                .result().destinations().getFirst().ownership());
        assertTrue(Files.isRegularFile(archive.archivePath()));
        assertEquals(1, imports.managed().listArchives().size());
    }

    @Test
    void storedBackupsAreFoundOfflineAndOnlyTheSelectedOnesAreListed() throws Exception {
        ZipBackupStore source = new ZipBackupStore(temporaryDirectory.resolve("source"), FolderOrigin.DEFAULT);
        ZipBackupArtifact first = sourceBackup(source, "first");
        ZipBackupArtifact second = sourceBackup(source, "second");
        Imports imports = imports("stored");
        ImportPreview copy = imports.service().previewZip(source.root()).toCompletableFuture().join();
        importAll(imports, copy);
        Imports afterLostCatalog = imports("stored", "rebuilt-catalog.json");

        ImportPreview found = afterLostCatalog.service().previewLocal().toCompletableFuture().join();
        afterLostCatalog.service().execute(found.token(), Set.of(first.manifest().backupId()))
                .toCompletableFuture().join();
        ImportSummary rebuilt = afterLostCatalog.service().rebuildLocal().toCompletableFuture().join();

        assertEquals(2, found.items().size());
        assertEquals(1, rebuilt.added());
        assertEquals(1, rebuilt.unchanged());
        DestinationResult listed = afterLostCatalog.catalog().find(second.manifest().backupId()).orElseThrow()
                .result().destinations().getFirst();
        assertEquals(second.manifest(), afterLostCatalog.catalog().find(second.manifest().backupId())
                .orElseThrow().manifest());
        assertEquals(VerificationStatus.NOT_VERIFIED, listed.verificationStatus());
    }

    @Test
    void onlyARemoteThatCanBePushedToIsOfferedAsTheWorldsRemote() {
        assertFalse(FileBackupImportService.connectableRemote("git://example.invalid/team/archive.git"));
        assertTrue(FileBackupImportService.connectableRemote("https://example.invalid/team/archive.git"));
    }

    /** An import service on real stores under one folder; each name gets its own storage. */
    private Imports imports(String name) {
        return imports(name, "catalog.json");
    }

    private Imports imports(String name, String catalogName) {
        Path storage = temporaryDirectory.resolve(name);
        FileBackupCatalog catalog = new FileBackupCatalog(storage.resolve(catalogName));
        ZipBackupStore managed = new ZipBackupStore(storage.resolve("archives"), FolderOrigin.DEFAULT);
        WorldGitSnapshotStore git = new WorldGitSnapshotStore(
                new GitBackendSettings(true, storage.resolve("git"), FolderOrigin.DEFAULT, "git", "origin",
                        Optional.empty(), GitBackendSettings.DEFAULT_LFS_PATTERNS,
                        GitBackendSettings.DEFAULT_COMMAND_TIMEOUT, GitBackendSettings.DEFAULT_MAXIMUM_OUTPUT_BYTES),
                Map.of(),
                new SystemGitCommandRunner(),
                executor);
        ZipBackupStoreResolver stores = new ZipBackupStoreResolver() {
            @Override
            public ZipBackupStore store(WorldId worldId) {
                return managed;
            }

            @Override
            public ZipBackupStore defaultStore() {
                return managed;
            }
        };
        FileBackupImportService service = new FileBackupImportService(
                catalog,
                new FileImportSourceRegistry(storage.resolve("import-sources.json")),
                new FileBackupDeletionRegistry(storage.resolve("deleted-backups.txt")),
                git,
                stores,
                Set::of,
                new LockingWorldOperationGate(),
                executor,
                clock);
        return new Imports(service, catalog, managed);
    }

    private ZipBackupArtifact sourceBackup(ZipBackupStore store, String name) throws Exception {
        Path world = Files.createDirectories(temporaryDirectory.resolve("worlds").resolve(name));
        Files.writeString(world.resolve("level.dat"), "recoverable-world-data-" + name);
        WorldInventory inventory = TestCaptures.inventoryOf(world);
        BackupManifest manifest = new BackupManifest(
                BackupManifest.CURRENT_FORMAT_VERSION,
                BackupId.create(),
                WorldId.create(),
                "Recovered World",
                Optional.of("Import"),
                NOW.minus(Duration.ofDays(1)),
                BackupTrigger.MANUAL,
                inventory.fileCount(),
                inventory.byteCount(),
                inventory.fileCount(),
                inventory.contentSha256(),
                inventory.inventorySha256(),
                Optional.empty());
        return store.create(TestCaptures.of(world, manifest), bytes -> {
        });
    }

    /** Imports every backup the preview lists, as a player does who keeps the whole selection. */
    private static ImportSummary importAll(Imports imports, ImportPreview preview) {
        return imports.service().execute(preview.token(), preview.items().stream()
                        .map(item -> item.manifest().backupId())
                        .collect(Collectors.toUnmodifiableSet()))
                .toCompletableFuture()
                .join();
    }

    private record Imports(FileBackupImportService service, FileBackupCatalog catalog, ZipBackupStore managed) {
    }

    /** A clock the test moves by hand, so a preview can expire. */
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
