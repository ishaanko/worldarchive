package dev.ishaankot.worldarchive.storage.zip;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.ishaankot.worldarchive.catalog.FileBackupCatalog;
import dev.ishaankot.worldarchive.config.WorldIdentityStore;
import dev.ishaankot.worldarchive.core.BackupCapture;
import dev.ishaankot.worldarchive.core.DeleteBackupRequest;
import dev.ishaankot.worldarchive.core.LockingWorldOperationGate;
import dev.ishaankot.worldarchive.core.ProgressListener;
import dev.ishaankot.worldarchive.importing.FileBackupImportService;
import dev.ishaankot.worldarchive.importing.FileImportSourceRegistry;
import dev.ishaankot.worldarchive.importing.ImportDisposition;
import dev.ishaankot.worldarchive.importing.ImportPreview;
import dev.ishaankot.worldarchive.importing.ImportSummary;
import dev.ishaankot.worldarchive.importing.ZipImportMode;
import dev.ishaankot.worldarchive.model.ArtifactOwnership;
import dev.ishaankot.worldarchive.model.BackupId;
import dev.ishaankot.worldarchive.model.BackupManifest;
import dev.ishaankot.worldarchive.model.BackupTrigger;
import dev.ishaankot.worldarchive.model.WorldId;
import dev.ishaankot.worldarchive.recovery.BackupRecoveryService;
import dev.ishaankot.worldarchive.recovery.RestoredWorldMetadataFinalizer;
import dev.ishaankot.worldarchive.storage.git.GitBackendSettings;
import dev.ishaankot.worldarchive.storage.git.WorldGitSnapshotStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class FileBackupImportServiceIntegrationTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void linkedZipImportIsIdempotentAndDeletionNeverTouchesSource() throws Exception {
        ZipBackupArtifact source = sourceBackup("linked-source");
        FileBackupCatalog catalog = new FileBackupCatalog(temporaryDirectory.resolve("linked-catalog.json"));
        FileImportSourceRegistry registry = new FileImportSourceRegistry(
                temporaryDirectory.resolve("linked-sources.json"));
        ZipBackupStore managed = new ZipBackupStore(temporaryDirectory.resolve("linked-managed"));
        try (WorldGitSnapshotStore git = gitStore("linked-git");
                FileBackupImportService imports = imports(catalog, registry, git, managed)) {
            ImportPreview preview = imports.previewZip(
                    source.archivePath().getParent().getParent(), ZipImportMode.LINK)
                    .toCompletableFuture().join();
            assertEquals(ImportDisposition.ADD, preview.items().getFirst().disposition());
            assertEquals(1, imports.execute(preview.token()).toCompletableFuture().join().added());
            assertEquals(ArtifactOwnership.EXTERNAL, catalog.find(source.manifest().backupId())
                    .orElseThrow().result().destinations().getFirst().ownership());

            ImportPreview repeated = imports.previewZip(
                    source.archivePath().getParent().getParent(), ZipImportMode.LINK)
                    .toCompletableFuture().join();
            assertEquals(ImportDisposition.UNCHANGED, repeated.items().getFirst().disposition());
            assertEquals(1, imports.execute(repeated.token())
                    .toCompletableFuture().join().unchanged());

            BackupRecoveryService recovery = new BackupRecoveryService(
                    catalog,
                    Optional.empty(),
                    Optional.of(resolver(managed)),
                    registry,
                    new WorldIdentityStore(),
                    RestoredWorldMetadataFinalizer.NO_OP,
                    Runnable::run,
                    new LockingWorldOperationGate());
            var preparation = recovery.prepareDelete(source.manifest().backupId())
                    .toCompletableFuture().join();
            recovery.deleteBackup(
                    new DeleteBackupRequest(
                            source.manifest().backupId(), preparation.confirmationToken()),
                    ProgressListener.NO_OP).toCompletableFuture().join();

            assertTrue(Files.isRegularFile(source.archivePath()));
            assertTrue(catalog.find(source.manifest().backupId()).isEmpty());
            assertTrue(registry.list().isEmpty());
        }
    }

    @Test
    void copiedZipImportCanRebuildAnEmptyCatalogOffline() throws Exception {
        ZipBackupArtifact source = sourceBackup("copy-source");
        FileBackupCatalog catalog = new FileBackupCatalog(temporaryDirectory.resolve("copy-catalog.json"));
        FileImportSourceRegistry registry = new FileImportSourceRegistry(
                temporaryDirectory.resolve("copy-sources.json"));
        ZipBackupStore managed = new ZipBackupStore(temporaryDirectory.resolve("copy-managed"));
        try (WorldGitSnapshotStore git = gitStore("copy-git");
                FileBackupImportService imports = imports(catalog, registry, git, managed)) {
            ImportPreview preview = imports.previewZip(
                    source.archivePath().getParent().getParent(), ZipImportMode.COPY)
                    .toCompletableFuture().join();
            assertEquals(1, imports.execute(preview.token()).toCompletableFuture().join().added());
            assertTrue(Files.isRegularFile(source.archivePath()));
            assertFalse(managed.listCompleteArchives().isEmpty());

            FileBackupCatalog rebuiltCatalog = new FileBackupCatalog(
                    temporaryDirectory.resolve("rebuilt-catalog.json"));
            try (FileBackupImportService rebuild = imports(
                    rebuiltCatalog, registry, git, managed)) {
                ImportSummary summary = rebuild.rebuildLocal().toCompletableFuture().join();
                assertEquals(1, summary.added());
                assertEquals(source.manifest(), rebuiltCatalog.find(
                        source.manifest().backupId()).orElseThrow().manifest());
            }
        }
    }

    private FileBackupImportService imports(
            FileBackupCatalog catalog,
            FileImportSourceRegistry registry,
            WorldGitSnapshotStore git,
            ZipBackupStore managed) {
        return new FileBackupImportService(
                catalog, registry, git, resolver(managed), java.util.Set::of, Runnable::run);
    }

    private WorldGitSnapshotStore gitStore(String name) {
        return new WorldGitSnapshotStore(new GitBackendSettings(
                true,
                temporaryDirectory.resolve(name),
                "git",
                "origin",
                Optional.empty(),
                GitBackendSettings.DEFAULT_LFS_PATTERNS,
                GitBackendSettings.DEFAULT_COMMAND_TIMEOUT,
                GitBackendSettings.DEFAULT_MAXIMUM_OUTPUT_BYTES));
    }

    private static ZipBackupStoreResolver resolver(ZipBackupStore store) {
        return new ZipBackupStoreResolver() {
            @Override
            public ZipBackupStore store(WorldId worldId) {
                return store;
            }

            @Override
            public ZipBackupStore defaultStore() {
                return store;
            }
        };
    }

    private ZipBackupArtifact sourceBackup(String name) throws Exception {
        Path world = Files.createDirectories(temporaryDirectory.resolve(name).resolve("world"));
        Files.writeString(world.resolve("level.dat"), "recoverable-world-data");
        BackupManifest manifest = manifest(world);
        return new ZipBackupStore(temporaryDirectory.resolve(name).resolve("archives"))
                .create(new BackupCapture(world, manifest));
    }

    private static BackupManifest manifest(Path world) throws Exception {
        List<ZipInventoryEntry> files = new ArrayList<>();
        for (ZipSourceScanner.SourceEntry entry : ZipSourceScanner.snapshot(world).entries()) {
            if (!entry.directory()) {
                files.add(new ZipInventoryEntry(
                        entry.relativePath(), entry.size(), ZipDigests.sha256(entry.path())));
            }
        }
        ZipInventory inventory = ZipInventory.create(files);
        return BackupManifest.create(
                BackupId.create(),
                WorldId.create(),
                "Recovered World",
                Optional.of("Import integration"),
                Instant.parse("2026-07-21T21:00:00Z"),
                BackupTrigger.MANUAL,
                inventory.fileCount(),
                inventory.byteCount(),
                inventory.fileCount(),
                ZipDigests.contentSha256(inventory.files()),
                inventory.inventorySha256());
    }
}
