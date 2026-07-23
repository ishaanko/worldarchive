package dev.ishaankot.worldarchive.storage.zip;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.ishaankot.worldarchive.catalog.FileBackupCatalog;
import dev.ishaankot.worldarchive.catalog.FileBackupDeletionRegistry;
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
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class FileBackupImportServiceIntegrationTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void importsOnlyBackupsSelectedFromPreview() throws Exception {
        ZipBackupStore sourceStore = new ZipBackupStore(temporaryDirectory.resolve("selection-source"));
        ZipBackupArtifact first = sourceBackup(sourceStore, "selection-first");
        ZipBackupArtifact second = sourceBackup(sourceStore, "selection-second");
        FileBackupCatalog catalog = new FileBackupCatalog(
                temporaryDirectory.resolve("selection-catalog.json"));
        FileImportSourceRegistry registry = new FileImportSourceRegistry(
                temporaryDirectory.resolve("selection-sources.json"));
        ZipBackupStore managed = new ZipBackupStore(temporaryDirectory.resolve("selection-managed"));
        try (WorldGitSnapshotStore git = gitStore("selection-git");
                FileBackupImportService imports = imports(catalog, registry, git, managed)) {
            ImportPreview preview = imports.previewZip(sourceStore.root(), ZipImportMode.COPY)
                    .toCompletableFuture().join();

            ImportSummary summary = imports.execute(
                    preview.token(), Set.of(first.manifest().backupId()))
                    .toCompletableFuture().join();

            assertEquals(1, summary.added());
            assertTrue(catalog.find(first.manifest().backupId()).isPresent());
            assertTrue(catalog.find(second.manifest().backupId()).isEmpty());
        }
    }

    @Test
    void discardedPreviewCannotExecuteOrChangeTheCatalog() throws Exception {
        ZipBackupArtifact source = sourceBackup("discard-source");
        FileBackupCatalog catalog = new FileBackupCatalog(
                temporaryDirectory.resolve("discard-catalog.json"));
        FileImportSourceRegistry sources = new FileImportSourceRegistry(
                temporaryDirectory.resolve("discard-sources.json"));
        ZipBackupStore managed = new ZipBackupStore(
                temporaryDirectory.resolve("discard-managed"));
        try (WorldGitSnapshotStore git = gitStore("discard-git");
                FileBackupImportService imports = imports(catalog, sources, git, managed)) {
            ImportPreview preview = imports.previewZip(
                    source.archivePath().getParent().getParent(), ZipImportMode.COPY)
                    .toCompletableFuture().join();

            imports.discard(preview.token()).toCompletableFuture().join();

            assertThrows(
                    java.util.concurrent.CompletionException.class,
                    () -> imports.execute(preview.token()).toCompletableFuture().join());
            assertTrue(catalog.listAll().isEmpty());
        }
    }

    @Test
    void storedBackupSearchAlsoImportsOnlyTheSelectedBackups() throws Exception {
        ZipBackupStore sourceStore = new ZipBackupStore(temporaryDirectory.resolve("stored-source"));
        ZipBackupArtifact first = sourceBackup(sourceStore, "stored-first");
        ZipBackupArtifact second = sourceBackup(sourceStore, "stored-second");
        ZipBackupStore managed = new ZipBackupStore(temporaryDirectory.resolve("stored-managed"));
        FileImportSourceRegistry sources = new FileImportSourceRegistry(
                temporaryDirectory.resolve("stored-sources.json"));
        try (WorldGitSnapshotStore git = gitStore("stored-git");
                FileBackupImportService initial = imports(
                        new FileBackupCatalog(temporaryDirectory.resolve("stored-initial.json")),
                        sources,
                        git,
                        managed)) {
            ImportPreview copy = initial.previewZip(sourceStore.root(), ZipImportMode.COPY)
                    .toCompletableFuture().join();
            initial.execute(copy.token()).toCompletableFuture().join();

            FileBackupCatalog rebuilt = new FileBackupCatalog(
                    temporaryDirectory.resolve("stored-rebuilt.json"));
            try (FileBackupImportService search = imports(rebuilt, sources, git, managed)) {
                ImportPreview found = search.previewLocal().toCompletableFuture().join();
                assertEquals(2, found.items().size());

                search.execute(found.token(), Set.of(first.manifest().backupId()))
                        .toCompletableFuture().join();

                assertTrue(rebuilt.find(first.manifest().backupId()).isPresent());
                assertTrue(rebuilt.find(second.manifest().backupId()).isEmpty());
            }
        }
    }

    @Test
    void deletionMarkerSuppressesAStillDiscoverableManagedBackup() throws Exception {
        ZipBackupArtifact source = sourceBackup("deleted-source");
        FileBackupCatalog catalog = new FileBackupCatalog(
                temporaryDirectory.resolve("deleted-catalog.json"));
        FileImportSourceRegistry sources = new FileImportSourceRegistry(
                temporaryDirectory.resolve("deleted-sources.json"));
        FileBackupDeletionRegistry deletions = new FileBackupDeletionRegistry(
                temporaryDirectory.resolve("deleted-backups.txt"));
        ZipBackupStore managed = new ZipBackupStore(temporaryDirectory.resolve("deleted-managed"));
        try (WorldGitSnapshotStore git = gitStore("deleted-git");
                FileBackupImportService imports = imports(
                        catalog, sources, deletions, git, managed)) {
            ImportPreview preview = imports.previewZip(
                    source.archivePath().getParent().getParent(), ZipImportMode.COPY)
                    .toCompletableFuture().join();
            imports.execute(preview.token()).toCompletableFuture().join();

            deletions.record(source.manifest().backupId());
            assertTrue(catalog.remove(source.manifest().backupId()));
            assertFalse(managed.listCompleteArchives().isEmpty());

            assertTrue(catalog.listAll().isEmpty());
            assertEquals(0, imports.rebuildLocal().toCompletableFuture().join().added());
            assertTrue(catalog.listAll().isEmpty());
        }
    }

    @Test
    void linkedZipChangedAfterPreviewIsRejectedBeforeAnyImportMutation() throws Exception {
        ZipBackupArtifact source = sourceBackup("changed-link-source");
        FileBackupCatalog catalog = new FileBackupCatalog(
                temporaryDirectory.resolve("changed-link-catalog.json"));
        FileImportSourceRegistry sources = new FileImportSourceRegistry(
                temporaryDirectory.resolve("changed-link-sources.json"));
        ZipBackupStore managed = new ZipBackupStore(
                temporaryDirectory.resolve("changed-link-managed"));
        try (WorldGitSnapshotStore git = gitStore("changed-link-git");
                FileBackupImportService imports = imports(catalog, sources, git, managed)) {
            ImportPreview preview = imports.previewZip(
                    source.archivePath().getParent().getParent(), ZipImportMode.LINK)
                    .toCompletableFuture().join();
            Files.write(
                    source.archivePath(), new byte[] {1}, StandardOpenOption.APPEND);

            assertThrows(
                    java.util.concurrent.CompletionException.class,
                    () -> imports.execute(preview.token()).toCompletableFuture().join());
            assertTrue(catalog.listAll().isEmpty());
            assertTrue(sources.list().isEmpty());
        }
    }

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
                    dev.ishaankot.worldarchive.catalog.BackupDeletionRegistry.NONE,
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

    private FileBackupImportService imports(
            FileBackupCatalog catalog,
            FileImportSourceRegistry registry,
            FileBackupDeletionRegistry deletions,
            WorldGitSnapshotStore git,
            ZipBackupStore managed) {
        return new FileBackupImportService(
                catalog,
                registry,
                deletions,
                git,
                resolver(managed),
                java.util.Set::of,
                Runnable::run);
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

    private ZipBackupArtifact sourceBackup(ZipBackupStore store, String name) throws Exception {
        Path world = Files.createDirectories(temporaryDirectory.resolve(name).resolve("world"));
        Files.writeString(world.resolve("level.dat"), "recoverable-world-data-" + name);
        return store.create(new BackupCapture(world, manifest(world)));
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
