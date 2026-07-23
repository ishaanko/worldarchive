package dev.ishaankot.worldarchive.recovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.ishaankot.worldarchive.catalog.BackupDeletionRegistry;
import dev.ishaankot.worldarchive.catalog.FileBackupDeletionRegistry;
import dev.ishaankot.worldarchive.core.DeleteBackupRequest;
import dev.ishaankot.worldarchive.core.DeletePreparation;
import dev.ishaankot.worldarchive.core.CaptureProgressListener;
import dev.ishaankot.worldarchive.core.CapturedBackup;
import dev.ishaankot.worldarchive.core.CreateBackupRequest;
import dev.ishaankot.worldarchive.core.FileSystemBackupCaptureFactory;
import dev.ishaankot.worldarchive.core.ProgressListener;
import dev.ishaankot.worldarchive.model.BackupId;
import dev.ishaankot.worldarchive.model.BackupRecord;
import dev.ishaankot.worldarchive.model.BackupResult;
import dev.ishaankot.worldarchive.model.BackupStatus;
import dev.ishaankot.worldarchive.model.BackupTrigger;
import dev.ishaankot.worldarchive.model.DestinationHealth;
import dev.ishaankot.worldarchive.model.DestinationHealthStatus;
import dev.ishaankot.worldarchive.model.DestinationResult;
import dev.ishaankot.worldarchive.model.DestinationStatus;
import dev.ishaankot.worldarchive.model.DestinationType;
import dev.ishaankot.worldarchive.model.SyncStatus;
import dev.ishaankot.worldarchive.model.VerificationStatus;
import dev.ishaankot.worldarchive.model.WorldId;
import dev.ishaankot.worldarchive.storage.zip.ZipBackupArtifact;
import dev.ishaankot.worldarchive.storage.zip.ZipBackupStore;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class BackupRecoveryServiceMaintenanceTest extends BackupRecoveryServiceTestSupport {
    @Test
    void deletionIntentMustPersistBeforeDestinationMutation() {
        Fixture fixture = fixture(DestinationType.ZIP);
        FakeDestination zip = new FakeDestination(DestinationType.ZIP, fixture.worldId());
        InMemoryCatalog catalog = new InMemoryCatalog(fixture.record());
        BackupDeletionRegistry unavailable = new BackupDeletionRegistry() {
            @Override
            public boolean contains(BackupId backupId) {
                return false;
            }

            @Override
            public void record(BackupId backupId) throws IOException {
                throw new IOException("Deletion registry unavailable");
            }

            @Override
            public void restore(BackupId backupId) {
            }
        };
        BackupRecoveryService service = new BackupRecoveryService(
                catalog,
                Map.of(DestinationType.ZIP, zip),
                unavailable,
                new dev.ishaankot.worldarchive.config.WorldIdentityStore(),
                RestoredWorldMetadataFinalizer.NO_OP,
                Runnable::run,
                new MutableClock(CREATED_AT.plusSeconds(2)),
                BackupRecoveryService.DEFAULT_CONFIRMATION_LIFETIME,
                new dev.ishaankot.worldarchive.core.LockingWorldOperationGate(),
                Files::move);
        DeletePreparation preparation = service.prepareDelete(fixture.backupId())
                .toCompletableFuture().join();

        assertThrows(java.util.concurrent.CompletionException.class, () -> service.deleteBackup(
                        new DeleteBackupRequest(
                                fixture.backupId(), preparation.confirmationToken()),
                        ProgressListener.NO_OP)
                .toCompletableFuture().join());

        assertEquals(0, zip.deleteCalls.get());
        assertTrue(catalog.findUnchecked(fixture.backupId()).isPresent());
    }

    @Test
    void failedDeletionRollsBackItsIntentMarker() throws Exception {
        Fixture fixture = fixture(DestinationType.ZIP);
        FakeDestination zip = new FakeDestination(DestinationType.ZIP, fixture.worldId());
        zip.deleteResult = false;
        InMemoryCatalog catalog = new InMemoryCatalog(fixture.record());
        FileBackupDeletionRegistry deletions = new FileBackupDeletionRegistry(
                temporaryDirectory.resolve("failed-delete-intent.txt"));
        BackupRecoveryService service = new BackupRecoveryService(
                catalog,
                Map.of(DestinationType.ZIP, zip),
                deletions,
                new dev.ishaankot.worldarchive.config.WorldIdentityStore(),
                RestoredWorldMetadataFinalizer.NO_OP,
                Runnable::run,
                new MutableClock(CREATED_AT.plusSeconds(2)),
                BackupRecoveryService.DEFAULT_CONFIRMATION_LIFETIME,
                new dev.ishaankot.worldarchive.core.LockingWorldOperationGate(),
                Files::move);
        DeletePreparation preparation = service.prepareDelete(fixture.backupId())
                .toCompletableFuture().join();

        service.deleteBackup(
                new DeleteBackupRequest(fixture.backupId(), preparation.confirmationToken()),
                ProgressListener.NO_OP).toCompletableFuture().join();

        assertFalse(deletions.contains(fixture.backupId()));
        assertTrue(catalog.findUnchecked(fixture.backupId()).isPresent());
    }

    @Test
    void deleteConfirmationExpiresAndCannotBeReplayed() {
        Fixture fixture = fixture(DestinationType.ZIP);
        FakeDestination zip = new FakeDestination(DestinationType.ZIP, fixture.worldId());
        InMemoryCatalog catalog = new InMemoryCatalog(fixture.record());
        MutableClock clock = new MutableClock(CREATED_AT.plusSeconds(2));
        BackupRecoveryService service = service(
                catalog,
                Map.of(DestinationType.ZIP, zip),
                clock,
                RestoredWorldMetadataFinalizer.NO_OP,
                Runnable::run,
                Duration.ofSeconds(10));

        DeletePreparation expired = service.prepareDelete(fixture.backupId())
                .toCompletableFuture().join();
        clock.advance(Duration.ofSeconds(10));
        assertRecoveryFailure(() -> service.deleteBackup(
                        new DeleteBackupRequest(fixture.backupId(), expired.confirmationToken()),
                        ProgressListener.NO_OP)
                .toCompletableFuture().join());
        assertEquals(0, zip.deleteCalls.get());

        DeletePreparation valid = service.prepareDelete(fixture.backupId())
                .toCompletableFuture().join();
        BackupResult deleted = service.deleteBackup(
                        new DeleteBackupRequest(fixture.backupId(), valid.confirmationToken()),
                        ProgressListener.NO_OP)
                .toCompletableFuture().join();
        assertEquals(BackupStatus.SUCCESS, deleted.status());
        assertTrue(catalog.findUnchecked(fixture.backupId()).isEmpty());
        assertRecoveryFailure(() -> service.deleteBackup(
                        new DeleteBackupRequest(fixture.backupId(), valid.confirmationToken()),
                        ProgressListener.NO_OP)
                .toCompletableFuture().join());
        assertEquals(1, zip.deleteCalls.get());
    }

    @Test
    void deletionRemovesCatalogRecordWithNoDurableDestinations() {
        Fixture fixture = fixture();
        InMemoryCatalog catalog = new InMemoryCatalog(fixture.record());
        BackupRecoveryService service = service(
                catalog, Map.of(), new MutableClock(CREATED_AT.plusSeconds(2)));
        DeletePreparation preparation = service.prepareDelete(fixture.backupId())
                .toCompletableFuture().join();

        BackupResult result = service.deleteBackup(
                        new DeleteBackupRequest(
                                fixture.backupId(), preparation.confirmationToken()),
                        ProgressListener.NO_OP)
                .toCompletableFuture().join();

        assertEquals(BackupStatus.SKIPPED, result.status());
        assertTrue(catalog.findUnchecked(fixture.backupId()).isEmpty());
    }

    @Test
    void alreadyAbsentExactZipArtifactRepairsCatalogAsSuccessfulDeletion() throws Exception {
        Path world = Files.createDirectory(temporaryDirectory.resolve("absent-zip-world"));
        Files.writeString(world.resolve("level.dat"), "zip contents");
        WorldId worldId = WorldId.create();
        BackupId backupId = BackupId.create();
        ZipBackupStore store = new ZipBackupStore(temporaryDirectory.resolve("absent-zip-store"));
        ZipBackupArtifact artifact;
        try (CapturedBackup captured = new FileSystemBackupCaptureFactory(
                        temporaryDirectory.resolve("absent-zip-captures"))
                .capture(
                        new CreateBackupRequest(
                                worldId,
                                world,
                                "Absent ZIP",
                                BackupTrigger.MANUAL),
                        backupId,
                        CREATED_AT,
                        Optional.empty(),
                        CaptureProgressListener.NO_OP)) {
            artifact = store.create(captured.capture());
        }
        DestinationResult destination = DestinationResult.success(
                DestinationType.ZIP, artifact.artifactId());
        BackupRecord record = new BackupRecord(
                artifact.manifest(),
                BackupResult.aggregate(
                        backupId,
                        worldId,
                        List.of(destination),
                        CREATED_AT.plusSeconds(1)));
        InMemoryCatalog catalog = new InMemoryCatalog(record);
        assertTrue(store.delete(artifact.archivePath()));
        BackupRecoveryService service = service(
                catalog,
                Map.of(DestinationType.ZIP, new ZipRecoveryDestination(store, Clock.systemUTC())),
                new MutableClock(CREATED_AT.plusSeconds(2)));
        DeletePreparation preparation = service.prepareDelete(backupId)
                .toCompletableFuture().join();

        BackupResult result = service.deleteBackup(
                        new DeleteBackupRequest(backupId, preparation.confirmationToken()),
                        ProgressListener.NO_OP)
                .toCompletableFuture().join();

        assertEquals(BackupStatus.SUCCESS, result.status());
        assertTrue(catalog.findUnchecked(backupId).isEmpty());
    }

    @Test
    void partialDeletionRemovesOnlySuccessfulDestinationFromCatalog() {
        Fixture fixture = fixture(DestinationType.GIT, DestinationType.ZIP);
        FakeDestination git = new FakeDestination(DestinationType.GIT, fixture.worldId());
        git.deleteResult = false;
        FakeDestination zip = new FakeDestination(DestinationType.ZIP, fixture.worldId());
        InMemoryCatalog catalog = new InMemoryCatalog(fixture.record());
        BackupRecoveryService service = service(
                catalog, destinationMap(git, zip), new MutableClock(CREATED_AT.plusSeconds(2)));
        DeletePreparation preparation = service.prepareDelete(fixture.backupId())
                .toCompletableFuture().join();

        BackupResult result = service.deleteBackup(
                        new DeleteBackupRequest(fixture.backupId(), preparation.confirmationToken()),
                        ProgressListener.NO_OP)
                .toCompletableFuture().join();

        assertEquals(BackupStatus.PARTIAL_SUCCESS, result.status());
        assertEquals(1, git.deleteCalls.get());
        assertEquals(1, zip.deleteCalls.get());
        BackupRecord retained = catalog.findUnchecked(fixture.backupId()).orElseThrow();
        assertEquals(List.of(DestinationType.GIT), retained.result().destinations().stream()
                .map(DestinationResult::destination).toList());
    }

    @Test
    void cancellationAfterFirstDeletionPublishesCatalogBeforeStopping() throws Exception {
        Fixture fixture = fixture(DestinationType.GIT, DestinationType.ZIP);
        FakeDestination git = new FakeDestination(DestinationType.GIT, fixture.worldId());
        FakeDestination zip = new FakeDestination(DestinationType.ZIP, fixture.worldId());
        InMemoryCatalog catalog = new InMemoryCatalog(fixture.record());
        BlockingStep catalogCommit = new BlockingStep();
        catalog.afterUpdate = catalogCommit::blockUnchecked;
        try (ExecutorService executor = Executors.newThreadPerTaskExecutor(
                Thread.ofVirtual().factory())) {
            BackupRecoveryService service = service(
                    catalog,
                    destinationMap(git, zip),
                    new MutableClock(CREATED_AT.plusSeconds(2)),
                    RestoredWorldMetadataFinalizer.NO_OP,
                    executor);
            DeletePreparation preparation = service.prepareDelete(fixture.backupId())
                    .toCompletableFuture().join();

            var future = service.deleteBackup(
                            new DeleteBackupRequest(
                                    fixture.backupId(), preparation.confirmationToken()),
                            ProgressListener.NO_OP)
                    .toCompletableFuture();
            catalogCommit.awaitEntered();
            assertEquals(List.of(DestinationType.ZIP), catalog.findUnchecked(fixture.backupId())
                    .orElseThrow().result().destinations().stream()
                    .map(DestinationResult::destination)
                    .toList());
            try {
                assertTrue(future.cancel(true));
            } finally {
                catalogCommit.release();
            }
            assertThrows(CancellationException.class, future::join);
        }

        assertFalse(catalogCommit.interrupted());
        assertEquals(1, git.deleteCalls.get());
        assertEquals(0, zip.deleteCalls.get());
        assertEquals(List.of(DestinationType.ZIP), catalog.findUnchecked(fixture.backupId())
                .orElseThrow().result().destinations().stream()
                .map(DestinationResult::destination)
                .toList());
    }

    @Test
    void cancellingDeletionDefersInterruptUntilArtifactAndCatalogAreConsistent() throws Exception {
        Fixture fixture = fixture(DestinationType.GIT);
        FakeDestination git = new FakeDestination(DestinationType.GIT, fixture.worldId());
        BlockingStep deletion = new BlockingStep();
        git.deletionBlock = deletion;
        InMemoryCatalog catalog = new InMemoryCatalog(fixture.record());
        try (ExecutorService executor = Executors.newThreadPerTaskExecutor(
                Thread.ofVirtual().factory())) {
            BackupRecoveryService service = service(
                    catalog,
                    Map.of(DestinationType.GIT, git),
                    new MutableClock(CREATED_AT.plusSeconds(2)),
                    RestoredWorldMetadataFinalizer.NO_OP,
                    executor);
            DeletePreparation preparation = service.prepareDelete(fixture.backupId())
                    .toCompletableFuture().join();

            var future = service.deleteBackup(
                            new DeleteBackupRequest(
                                    fixture.backupId(), preparation.confirmationToken()),
                            ProgressListener.NO_OP)
                    .toCompletableFuture();
            deletion.awaitEntered();
            try {
                assertTrue(future.cancel(true));
            } finally {
                deletion.release();
            }
            assertThrows(CancellationException.class, future::join);
        }

        assertFalse(deletion.interrupted());
        assertEquals(1, git.deleteCalls.get());
        assertTrue(catalog.findUnchecked(fixture.backupId()).isEmpty());
    }

    @Test
    void cancellingVerificationInterruptsCurrentDestinationAndLeavesCatalogUnchanged()
            throws Exception {
        Fixture fixture = fixture(DestinationType.GIT, DestinationType.ZIP);
        FakeDestination git = new FakeDestination(DestinationType.GIT, fixture.worldId());
        FakeDestination zip = new FakeDestination(DestinationType.ZIP, fixture.worldId());
        BlockingStep verification = new BlockingStep();
        git.verificationBlock = verification;
        InMemoryCatalog catalog = new InMemoryCatalog(fixture.record());
        try (ExecutorService executor = Executors.newThreadPerTaskExecutor(
                Thread.ofVirtual().factory())) {
            BackupRecoveryService service = service(
                    catalog,
                    destinationMap(git, zip),
                    new MutableClock(CREATED_AT.plusSeconds(2)),
                    RestoredWorldMetadataFinalizer.NO_OP,
                    executor);

            var future = service.verifyBackup(fixture.backupId(), ProgressListener.NO_OP)
                    .toCompletableFuture();
            verification.awaitEntered();
            try {
                assertTrue(future.cancel(true));
            } finally {
                verification.release();
            }
            assertThrows(CancellationException.class, future::join);
        }

        assertTrue(verification.interrupted());
        assertEquals(1, git.verifyCalls.get());
        assertEquals(0, zip.verifyCalls.get());
        assertEquals(fixture.record(), catalog.findUnchecked(fixture.backupId()).orElseThrow());
    }

    @Test
    void cancellingSyncInterruptsRemoteWorkAndRetainsRetryableCatalogState() throws Exception {
        Fixture fixture = fixture(DestinationType.GIT);
        FakeDestination git = new FakeDestination(DestinationType.GIT, fixture.worldId());
        BlockingStep synchronization = new BlockingStep();
        git.syncBlock = synchronization;
        InMemoryCatalog catalog = new InMemoryCatalog(fixture.record());
        try (ExecutorService executor = Executors.newThreadPerTaskExecutor(
                Thread.ofVirtual().factory())) {
            BackupRecoveryService service = service(
                    catalog,
                    Map.of(DestinationType.GIT, git),
                    new MutableClock(CREATED_AT.plusSeconds(2)),
                    RestoredWorldMetadataFinalizer.NO_OP,
                    executor);

            var future = service.syncBackup(fixture.backupId(), ProgressListener.NO_OP)
                    .toCompletableFuture();
            synchronization.awaitEntered();
            try {
                assertTrue(future.cancel(true));
            } finally {
                synchronization.release();
            }
            assertThrows(CancellationException.class, future::join);
        }

        assertTrue(synchronization.interrupted());
        assertEquals(1, git.syncCalls.get());
        assertEquals(fixture.record(), catalog.findUnchecked(fixture.backupId()).orElseThrow());
    }

    @Test
    void delayedCancellationInterruptCannotEnterAfterMandatoryCommitStarts() throws Exception {
        Fixture fixture = fixture(DestinationType.GIT);
        FakeDestination git = new FakeDestination(DestinationType.GIT, fixture.worldId());
        BlockingStep synchronization = new BlockingStep();
        git.syncBlock = synchronization;
        git.syncResult = DestinationResult.pendingSync(
                DestinationType.GIT,
                fixture.destination(DestinationType.GIT).artifactId().orElseThrow(),
                "retry remote");
        InMemoryCatalog catalog = new InMemoryCatalog(fixture.record());
        BlockingStep catalogCommit = new BlockingStep();
        catalog.beforeUpdate = catalogCommit::blockUnchecked;
        try (DelayedInterruptExecutor serviceExecutor = new DelayedInterruptExecutor();
                ExecutorService cancellationExecutor = Executors.newSingleThreadExecutor()) {
            BackupRecoveryService service = service(
                    catalog,
                    Map.of(DestinationType.GIT, git),
                    new MutableClock(CREATED_AT.plusSeconds(2)),
                    RestoredWorldMetadataFinalizer.NO_OP,
                    serviceExecutor);
            var future = service.syncBackup(fixture.backupId(), ProgressListener.NO_OP)
                    .toCompletableFuture();
            synchronization.awaitEntered();

            Future<Boolean> cancellation = cancellationExecutor.submit(() -> future.cancel(true));
            serviceExecutor.awaitInterruptEntered();
            synchronization.release();
            assertFalse(catalogCommit.enteredWithin(250, TimeUnit.MILLISECONDS));
            serviceExecutor.releaseInterrupt();
            assertTrue(cancellation.get(5, TimeUnit.SECONDS));
            catalogCommit.awaitEntered();
            catalogCommit.release();
            serviceExecutor.awaitFinished();

            assertThrows(CancellationException.class, future::join);
        }

        assertFalse(catalogCommit.interrupted());
        DestinationResult persisted = destination(
                catalog.findUnchecked(fixture.backupId()).orElseThrow().result(),
                DestinationType.GIT);
        assertEquals(DestinationStatus.PENDING_SYNC, persisted.status());
    }

    @Test
    void verificationAndSyncAtomicallyUpdateDestinationStateInCatalog() {
        Fixture fixture = fixture(DestinationType.GIT, DestinationType.ZIP);
        FakeDestination git = new FakeDestination(DestinationType.GIT, fixture.worldId());
        git.verification = VerificationOutcome.failed("Git object missing");
        git.syncResult = DestinationResult.pendingSync(
                DestinationType.GIT,
                fixture.destination(DestinationType.GIT).artifactId().orElseThrow(),
                "Remote unavailable");
        FakeDestination zip = new FakeDestination(DestinationType.ZIP, fixture.worldId());
        InMemoryCatalog catalog = new InMemoryCatalog(fixture.record());
        BackupRecoveryService service = service(
                catalog, destinationMap(git, zip), new MutableClock(CREATED_AT.plusSeconds(2)));

        BackupResult verified = service.verifyBackup(
                        fixture.backupId(), ProgressListener.NO_OP)
                .toCompletableFuture().join();
        assertEquals(VerificationStatus.FAILED, destination(verified, DestinationType.GIT)
                .verificationStatus());
        assertEquals(VerificationStatus.VERIFIED, destination(verified, DestinationType.ZIP)
                .verificationStatus());

        BackupResult synced = service.syncBackup(
                        fixture.backupId(), ProgressListener.NO_OP)
                .toCompletableFuture().join();
        DestinationResult syncedGit = destination(synced, DestinationType.GIT);
        assertEquals(DestinationStatus.PENDING_SYNC, syncedGit.status());
        assertEquals(SyncStatus.PENDING, syncedGit.syncStatus());
        assertEquals(VerificationStatus.FAILED, syncedGit.verificationStatus());
        BackupRecord persisted = catalog.findUnchecked(fixture.backupId()).orElseThrow();
        assertEquals(syncedGit, destination(persisted.result(), DestinationType.GIT));
        assertEquals(VerificationStatus.VERIFIED, destination(
                persisted.result(), DestinationType.ZIP).verificationStatus());
    }

    @Test
    void healthMergesConfiguredAndMissingDestinationsDeterministically() {
        Fixture fixture = fixture(DestinationType.ZIP);
        FakeDestination zip = new FakeDestination(DestinationType.ZIP, fixture.worldId());
        BackupRecoveryService service = service(
                new InMemoryCatalog(fixture.record()),
                Map.of(DestinationType.ZIP, zip),
                new MutableClock(CREATED_AT.plusSeconds(2)));

        List<DestinationHealth> health = service.health(Optional.of(fixture.worldId()))
                .toCompletableFuture().join();

        assertEquals(List.of(DestinationType.GIT, DestinationType.ZIP), health.stream()
                .map(DestinationHealth::destination).toList());
        assertEquals(DestinationHealthStatus.UNCONFIGURED, health.get(0).status());
        assertEquals(DestinationHealthStatus.HEALTHY, health.get(1).status());
    }

}
