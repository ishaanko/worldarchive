package dev.ishaankot.worldarchive.recovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.ishaankot.worldarchive.catalog.BackupCatalog;
import dev.ishaankot.worldarchive.config.WorldIdentityStore;
import dev.ishaankot.worldarchive.core.DeleteBackupRequest;
import dev.ishaankot.worldarchive.core.DeletePreparation;
import dev.ishaankot.worldarchive.core.CaptureProgressListener;
import dev.ishaankot.worldarchive.core.CapturedBackup;
import dev.ishaankot.worldarchive.core.CreateBackupRequest;
import dev.ishaankot.worldarchive.core.FileSystemBackupCaptureFactory;
import dev.ishaankot.worldarchive.core.LockingWorldOperationGate;
import dev.ishaankot.worldarchive.core.ProgressListener;
import dev.ishaankot.worldarchive.core.RestoreBackupRequest;
import dev.ishaankot.worldarchive.core.RestoreBackupResult;
import dev.ishaankot.worldarchive.model.BackupId;
import dev.ishaankot.worldarchive.model.BackupManifest;
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
import dev.ishaankot.worldarchive.model.WorldIdentity;
import dev.ishaankot.worldarchive.storage.zip.ZipBackupArtifact;
import dev.ishaankot.worldarchive.storage.zip.ZipBackupStore;
import dev.ishaankot.worldarchive.storage.git.GitSnapshot;
import dev.ishaankot.worldarchive.storage.git.GitVerification;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.CopyOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BackupRecoveryServiceTest {
    private static final Instant CREATED_AT = Instant.parse("2026-07-17T20:00:00Z");

    @TempDir
    Path temporaryDirectory;

    @Test
    void listsAndFindsRecordsFromCatalog() {
        Fixture first = fixture(DestinationType.ZIP);
        Fixture second = fixture(DestinationType.GIT);
        InMemoryCatalog catalog = new InMemoryCatalog(first.record(), second.record());
        BackupRecoveryService service = service(catalog, Map.of(), Clock.systemUTC());

        assertEquals(List.of(first.record()), service.listBackups(Optional.of(first.worldId()))
                .toCompletableFuture().join());
        assertEquals(2, service.listBackups(Optional.empty()).toCompletableFuture().join().size());
        assertEquals(first.record(), service.findBackup(first.backupId())
                .toCompletableFuture().join().orElseThrow());
        assertTrue(service.findBackup(BackupId.create()).toCompletableFuture().join().isEmpty());
    }

    @Test
    void restorePrefersZipThenFallsBackToGitAndCreatesFreshIdentity() throws IOException {
        Fixture fixture = fixture(DestinationType.GIT, DestinationType.ZIP);
        List<DestinationType> calls = java.util.Collections.synchronizedList(new ArrayList<>());
        FakeDestination zip = new FakeDestination(DestinationType.ZIP, fixture.worldId());
        zip.materializeFailure = new IOException("simulated ZIP failure");
        zip.calls = calls;
        FakeDestination git = new FakeDestination(DestinationType.GIT, fixture.worldId());
        git.calls = calls;
        InMemoryCatalog catalog = new InMemoryCatalog(fixture.record());
        BackupRecoveryService service = service(
                catalog, destinationMap(git, zip), Clock.systemUTC());

        Path worlds = Files.createDirectory(temporaryDirectory.resolve("worlds"));
        RestoreBackupResult result = service.restoreBackup(
                        new RestoreBackupRequest(fixture.backupId(), worlds, "Recovered World"),
                        ProgressListener.NO_OP)
                .toCompletableFuture().join();

        assertEquals(List.of(DestinationType.ZIP, DestinationType.GIT), calls);
        assertEquals("payload-GIT", Files.readString(
                result.restoredWorldDirectory().resolve("payload.txt"), StandardCharsets.UTF_8));
        WorldIdentity identity = new WorldIdentityStore().loadOrCreateIdentity(
                result.restoredWorldDirectory());
        assertEquals(result.restoredWorldId(), identity.worldId());
        assertNotEquals(fixture.worldId(), identity.worldId());
        assertEquals(fixture.backupId(), identity.sourceBackupId().orElseThrow());
    }

    @Test
    void restoreRejectsUnexpectedInternalIdentityMetadataAndUsesFallback() throws IOException {
        Fixture fixture = fixture(DestinationType.GIT, DestinationType.ZIP);
        FakeDestination zip = new FakeDestination(DestinationType.ZIP, fixture.worldId());
        zip.writeUnexpectedInternalMetadata = true;
        FakeDestination git = new FakeDestination(DestinationType.GIT, fixture.worldId());
        BackupRecoveryService service = service(
                new InMemoryCatalog(fixture.record()),
                destinationMap(git, zip),
                Clock.systemUTC());
        Path worlds = Files.createDirectory(temporaryDirectory.resolve("identity-worlds"));

        RestoreBackupResult result = service.restoreBackup(
                        new RestoreBackupRequest(fixture.backupId(), worlds, "Identity Check"),
                        ProgressListener.NO_OP)
                .toCompletableFuture().join();

        assertEquals(1, zip.materializeCalls.get());
        assertEquals(1, git.materializeCalls.get());
        assertEquals("payload-GIT", Files.readString(
                result.restoredWorldDirectory().resolve("payload.txt"), StandardCharsets.UTF_8));
    }

    @Test
    void realZipArtifactRestoresWithoutCopiedInternalIdentity() throws Exception {
        Path liveWorld = Files.createDirectories(temporaryDirectory.resolve("live-world"));
        Files.writeString(liveWorld.resolve("level.dat"), "real world bytes", StandardCharsets.UTF_8);
        WorldIdentity sourceIdentity = new WorldIdentityStore().loadOrCreateIdentity(liveWorld);
        BackupId backupId = BackupId.create();
        FileSystemBackupCaptureFactory captureFactory = new FileSystemBackupCaptureFactory(
                temporaryDirectory.resolve("captures"));
        ZipBackupStore store = new ZipBackupStore(temporaryDirectory.resolve("real-archives"));
        ZipBackupArtifact artifact;
        try (CapturedBackup captured = captureFactory.capture(
                new CreateBackupRequest(
                        sourceIdentity.worldId(),
                        liveWorld,
                        "Real ZIP World",
                        Optional.empty(),
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
                        sourceIdentity.worldId(),
                        List.of(destination),
                        CREATED_AT.plusSeconds(1)));
        BackupRecoveryService service = service(
                new InMemoryCatalog(record),
                Map.of(
                        DestinationType.ZIP,
                        new ZipRecoveryDestination(store, Clock.systemUTC())),
                Clock.systemUTC());
        Path worlds = Files.createDirectory(temporaryDirectory.resolve("real-restore-worlds"));

        RestoreBackupResult restored = service.restoreBackup(
                        new RestoreBackupRequest(backupId, worlds, "Real ZIP Restored"),
                        ProgressListener.NO_OP)
                .toCompletableFuture().join();

        assertEquals("real world bytes", Files.readString(
                restored.restoredWorldDirectory().resolve("level.dat"), StandardCharsets.UTF_8));
        WorldIdentity restoredIdentity = new WorldIdentityStore().loadOrCreateIdentity(
                restored.restoredWorldDirectory());
        assertNotEquals(sourceIdentity.worldId(), restoredIdentity.worldId());
        assertEquals(backupId, restoredIdentity.sourceBackupId().orElseThrow());
    }

    @Test
    void gitVerificationRejectsManifestThatDiffersFromCatalog() {
        Fixture fixture = fixture(DestinationType.GIT);
        BackupManifest original = fixture.record().manifest();
        BackupManifest tampered = BackupManifest.create(
                original.backupId(),
                original.worldId(),
                "Tampered World Name",
                original.label(),
                original.createdAt(),
                original.trigger(),
                original.sourceFileCount(),
                original.sourceByteCount(),
                original.changedFileCount(),
                original.contentSha256(),
                original.inventorySha256());
        GitSnapshot snapshot = new GitSnapshot(
                fixture.worldId(),
                fixture.backupId(),
                fixture.destination(DestinationType.GIT).artifactId().orElseThrow(),
                "1".repeat(40),
                original.createdAt());
        GitVerification verification = new GitVerification(
                snapshot, Optional.of(tampered), true, "objects verified");

        VerificationOutcome outcome = GitRecoveryDestination.verificationOutcome(
                fixture.record(), fixture.destination(DestinationType.GIT), verification);

        assertFalse(outcome.valid());
        assertTrue(outcome.message().contains("exactly match"));
    }

    @Test
    void restoreSanitizesWindowsNamesBeforeResolvingAndKeepsExactDisplayName()
            throws IOException {
        Fixture fixture = fixture(DestinationType.ZIP);
        FakeDestination zip = new FakeDestination(DestinationType.ZIP, fixture.worldId());
        List<String> displayNames = new ArrayList<>();
        Path worlds = Files.createDirectory(temporaryDirectory.resolve("safe-worlds"));
        Files.createDirectory(worlds.resolve("_CON"));
        BackupRecoveryService service = service(
                new InMemoryCatalog(fixture.record()),
                Map.of(DestinationType.ZIP, zip),
                new MutableClock(CREATED_AT.plusSeconds(2)),
                (directory, displayName) -> displayNames.add(displayName));

        RestoreBackupResult result = service.restoreBackup(
                        new RestoreBackupRequest(fixture.backupId(), worlds, "CON. "),
                        ProgressListener.NO_OP)
                .toCompletableFuture().join();

        assertEquals("_CON (2)", result.restoredWorldDirectory().getFileName().toString());
        assertEquals(worlds.toRealPath(), result.restoredWorldDirectory().getParent());
        assertEquals(List.of("CON. "), displayNames);
        assertThrows(IllegalArgumentException.class, () -> new RestoreBackupRequest(
                fixture.backupId(), worlds, "../escape"));
    }

    @Test
    void restoresForSameWorldAreSerializedAndPublishedWithUniqueNames() throws Exception {
        Fixture fixture = fixture(DestinationType.ZIP);
        FakeDestination zip = new FakeDestination(DestinationType.ZIP, fixture.worldId());
        zip.pauseMaterialization = true;
        Path worlds = Files.createDirectory(temporaryDirectory.resolve("concurrent-worlds"));
        try (ExecutorService executor = Executors.newThreadPerTaskExecutor(
                Thread.ofVirtual().factory())) {
            BackupRecoveryService service = service(
                    new InMemoryCatalog(fixture.record()),
                    Map.of(DestinationType.ZIP, zip),
                    new MutableClock(CREATED_AT.plusSeconds(2)),
                    RestoredWorldMetadataFinalizer.NO_OP,
                    executor);
            RestoreBackupRequest request = new RestoreBackupRequest(
                    fixture.backupId(), worlds, "Concurrent Copy");

            var first = service.restoreBackup(request, ProgressListener.NO_OP);
            var second = service.restoreBackup(request, ProgressListener.NO_OP);
            RestoreBackupResult firstResult = first.toCompletableFuture().join();
            RestoreBackupResult secondResult = second.toCompletableFuture().join();

            assertNotEquals(firstResult.restoredWorldDirectory(), secondResult.restoredWorldDirectory());
            assertEquals(1, zip.maximumActiveMaterializations.get());
            assertEquals(2, zip.materializeCalls.get());
        }
    }

    @Test
    void cancellingRestoreInterruptsMaterializationAndRemovesPrivateStaging() throws Exception {
        Fixture fixture = fixture(DestinationType.ZIP);
        FakeDestination zip = new FakeDestination(DestinationType.ZIP, fixture.worldId());
        BlockingStep materialization = new BlockingStep();
        zip.materializationBlock = materialization;
        Path worlds = Files.createDirectory(temporaryDirectory.resolve("cancelled-restore-worlds"));
        try (ExecutorService executor = Executors.newThreadPerTaskExecutor(
                Thread.ofVirtual().factory())) {
            BackupRecoveryService service = service(
                    new InMemoryCatalog(fixture.record()),
                    Map.of(DestinationType.ZIP, zip),
                    new MutableClock(CREATED_AT.plusSeconds(2)),
                    RestoredWorldMetadataFinalizer.NO_OP,
                    executor);

            var future = service.restoreBackup(
                            new RestoreBackupRequest(
                                    fixture.backupId(), worlds, "Cancelled Copy"),
                            ProgressListener.NO_OP)
                    .toCompletableFuture();
            materialization.awaitEntered();
            try {
                assertTrue(future.cancel(true));
            } finally {
                materialization.release();
            }
            assertThrows(CancellationException.class, future::join);
        }

        assertTrue(materialization.interrupted());
        try (var children = Files.list(worlds)) {
            assertEquals(0, children.count());
        }
    }

    @Test
    void restoreCancellationIsRejectedAfterAtomicPublicationBegins() throws Exception {
        Fixture fixture = fixture(DestinationType.ZIP);
        FakeDestination zip = new FakeDestination(DestinationType.ZIP, fixture.worldId());
        BlockingStep publication = new BlockingStep();
        Path worlds = Files.createDirectory(temporaryDirectory.resolve("committed-restore-worlds"));
        try (ExecutorService executor = Executors.newThreadPerTaskExecutor(
                Thread.ofVirtual().factory())) {
            BackupRecoveryService service = new BackupRecoveryService(
                    new InMemoryCatalog(fixture.record()),
                    Map.of(DestinationType.ZIP, zip),
                    new WorldIdentityStore(),
                    RestoredWorldMetadataFinalizer.NO_OP,
                    executor,
                    new MutableClock(CREATED_AT.plusSeconds(2)),
                    BackupRecoveryService.DEFAULT_CONFIRMATION_LIFETIME,
                    new LockingWorldOperationGate(),
                    (source, target, options) -> {
                        publication.blockForIo();
                        return Files.move(source, target, options);
                    });

            var future = service.restoreBackup(
                            new RestoreBackupRequest(
                                    fixture.backupId(), worlds, "Committed Copy"),
                            ProgressListener.NO_OP)
                    .toCompletableFuture();
            publication.awaitEntered();
            try {
                assertFalse(future.cancel(true));
            } finally {
                publication.release();
            }
            RestoreBackupResult result = future.join();
            assertTrue(Files.isDirectory(result.restoredWorldDirectory()));
        }
        assertFalse(publication.interrupted());
    }

    @Test
    void postMaterializationFailureRollsBackAllPrivateAndPublishedTargets() throws IOException {
        Fixture fixture = fixture(DestinationType.ZIP);
        FakeDestination zip = new FakeDestination(DestinationType.ZIP, fixture.worldId());
        Path worlds = Files.createDirectory(temporaryDirectory.resolve("rollback-worlds"));
        BackupRecoveryService service = service(
                new InMemoryCatalog(fixture.record()),
                Map.of(DestinationType.ZIP, zip),
                new MutableClock(CREATED_AT.plusSeconds(2)),
                (directory, displayName) -> {
                    throw new IOException("metadata finalizer failed");
                });

        CompletionException failure = assertThrows(CompletionException.class, () -> service
                .restoreBackup(
                        new RestoreBackupRequest(fixture.backupId(), worlds, "Rollback Copy"),
                        ProgressListener.NO_OP)
                .toCompletableFuture().join());

        assertInstanceOf(BackupRecoveryException.class, failure.getCause());
        try (var children = Files.list(worlds)) {
            assertEquals(0, children.count());
        }
    }

    @Test
    void restorePublicationRequiresAnAtomicMove() throws IOException {
        Fixture fixture = fixture(DestinationType.ZIP);
        FakeDestination zip = new FakeDestination(DestinationType.ZIP, fixture.worldId());
        Path worlds = Files.createDirectory(temporaryDirectory.resolve("atomic-worlds"));
        AtomicReference<List<CopyOption>> requestedOptions = new AtomicReference<>();
        BackupRecoveryService service = service(
                new InMemoryCatalog(fixture.record()),
                Map.of(DestinationType.ZIP, zip),
                new MutableClock(CREATED_AT.plusSeconds(2)),
                (source, target, options) -> {
                    requestedOptions.set(List.of(options));
                    return Files.move(source, target, options);
                });

        RestoreBackupResult result = service.restoreBackup(
                        new RestoreBackupRequest(fixture.backupId(), worlds, "Atomic Copy"),
                        ProgressListener.NO_OP)
                .toCompletableFuture().join();

        assertEquals(List.of(StandardCopyOption.ATOMIC_MOVE), requestedOptions.get());
        assertTrue(Files.isDirectory(result.restoredWorldDirectory()));
    }

    @Test
    void unsupportedAtomicPublicationLeavesNoVisibleRestore() throws IOException {
        Fixture fixture = fixture(DestinationType.ZIP);
        FakeDestination zip = new FakeDestination(DestinationType.ZIP, fixture.worldId());
        Path worlds = Files.createDirectory(temporaryDirectory.resolve("unsupported-atomic-worlds"));
        BackupRecoveryService service = service(
                new InMemoryCatalog(fixture.record()),
                Map.of(DestinationType.ZIP, zip),
                new MutableClock(CREATED_AT.plusSeconds(2)),
                (source, target, options) -> {
                    throw new AtomicMoveNotSupportedException(
                            source.toString(), target.toString(), "simulated unsupported move");
                });

        assertRecoveryFailure(() -> service.restoreBackup(
                        new RestoreBackupRequest(fixture.backupId(), worlds, "No Partial Copy"),
                        ProgressListener.NO_OP)
                .toCompletableFuture().join());
        try (var children = Files.list(worlds)) {
            assertEquals(0, children.count());
        }
    }

    @Test
    void windowsJunctionWorldsRootIsRejectedWithoutTouchingItsTarget() throws Exception {
        Assumptions.assumeTrue(System.getProperty("os.name", "").startsWith("Windows"));
        Fixture fixture = fixture(DestinationType.ZIP);
        FakeDestination zip = new FakeDestination(DestinationType.ZIP, fixture.worldId());
        Path outside = Files.createDirectory(temporaryDirectory.resolve("junction-outside"));
        Path junction = temporaryDirectory.resolve("junction-worlds");
        Process create = new ProcessBuilder(
                        "cmd.exe", "/d", "/c", "mklink", "/J",
                        junction.toString(), outside.toString())
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start();
        boolean created = create.waitFor(10, TimeUnit.SECONDS)
                && create.exitValue() == 0
                && Files.exists(junction, java.nio.file.LinkOption.NOFOLLOW_LINKS);
        Assumptions.assumeTrue(created, "Windows junction creation is unavailable");
        try {
            BackupRecoveryService service = service(
                    new InMemoryCatalog(fixture.record()),
                    Map.of(DestinationType.ZIP, zip),
                    new MutableClock(CREATED_AT.plusSeconds(2)));

            assertRecoveryFailure(() -> service.restoreBackup(
                            new RestoreBackupRequest(
                                    fixture.backupId(), junction, "Junction Copy"),
                            ProgressListener.NO_OP)
                    .toCompletableFuture().join());
            try (var children = Files.list(outside)) {
                assertEquals(0, children.count());
            }
        } finally {
            Files.deleteIfExists(junction);
        }
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
    void cancellingDeletionInterruptsCurrentDestinationAndRetainsCatalog() throws Exception {
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

        assertTrue(deletion.interrupted());
        assertEquals(1, git.deleteCalls.get());
        assertEquals(fixture.record(), catalog.findUnchecked(fixture.backupId()).orElseThrow());
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

    private BackupRecoveryService service(
            InMemoryCatalog catalog,
            Map<DestinationType, RecoveryDestination> destinations,
            Clock clock) {
        return service(
                catalog,
                destinations,
                clock,
                RestoredWorldMetadataFinalizer.NO_OP,
                Runnable::run);
    }

    private BackupRecoveryService service(
            InMemoryCatalog catalog,
            Map<DestinationType, RecoveryDestination> destinations,
            Clock clock,
            RestoredWorldMetadataFinalizer finalizer) {
        return service(catalog, destinations, clock, finalizer, Runnable::run);
    }

    private BackupRecoveryService service(
            InMemoryCatalog catalog,
            Map<DestinationType, RecoveryDestination> destinations,
            Clock clock,
            BackupRecoveryService.DirectoryMove directoryMove) {
        return new BackupRecoveryService(
                catalog,
                destinations,
                new WorldIdentityStore(),
                RestoredWorldMetadataFinalizer.NO_OP,
                Runnable::run,
                clock,
                BackupRecoveryService.DEFAULT_CONFIRMATION_LIFETIME,
                new LockingWorldOperationGate(),
                directoryMove);
    }

    private BackupRecoveryService service(
            InMemoryCatalog catalog,
            Map<DestinationType, RecoveryDestination> destinations,
            Clock clock,
            RestoredWorldMetadataFinalizer finalizer,
            java.util.concurrent.Executor executor) {
        return service(
                catalog,
                destinations,
                clock,
                finalizer,
                executor,
                BackupRecoveryService.DEFAULT_CONFIRMATION_LIFETIME);
    }

    private BackupRecoveryService service(
            InMemoryCatalog catalog,
            Map<DestinationType, RecoveryDestination> destinations,
            Clock clock,
            RestoredWorldMetadataFinalizer finalizer,
            java.util.concurrent.Executor executor,
            Duration confirmationLifetime) {
        return new BackupRecoveryService(
                catalog,
                destinations,
                new WorldIdentityStore(),
                finalizer,
                executor,
                clock,
                confirmationLifetime,
                new LockingWorldOperationGate());
    }

    private static EnumMap<DestinationType, RecoveryDestination> destinationMap(
            FakeDestination git,
            FakeDestination zip) {
        EnumMap<DestinationType, RecoveryDestination> destinations =
                new EnumMap<>(DestinationType.class);
        destinations.put(git.destinationType(), git);
        destinations.put(zip.destinationType(), zip);
        return destinations;
    }

    private static DestinationResult destination(BackupResult result, DestinationType type) {
        return result.destinations().stream()
                .filter(destination -> destination.destination() == type)
                .findFirst()
                .orElseThrow();
    }

    private static void assertRecoveryFailure(Runnable operation) {
        CompletionException failure = assertThrows(CompletionException.class, operation::run);
        assertInstanceOf(BackupRecoveryException.class, failure.getCause());
    }

    private static Fixture fixture(DestinationType... destinationTypes) {
        WorldId worldId = WorldId.create();
        BackupId backupId = BackupId.create();
        BackupManifest manifest = BackupManifest.create(
                backupId,
                worldId,
                "Test World",
                Optional.of("manual"),
                CREATED_AT,
                BackupTrigger.MANUAL,
                2,
                24,
                2,
                "a".repeat(64),
                "b".repeat(64));
        List<DestinationResult> destinations = new ArrayList<>();
        for (DestinationType type : destinationTypes) {
            String artifact = type == DestinationType.GIT
                    ? "refs/heads/worldarchive/" + worldId + "/" + backupId
                    : worldId + "/20260717T200000Z_" + backupId + ".zip";
            destinations.add(DestinationResult.success(type, artifact));
        }
        BackupResult result = BackupResult.aggregate(
                backupId, worldId, destinations, CREATED_AT.plusSeconds(1));
        return new Fixture(worldId, backupId, new BackupRecord(manifest, result));
    }

    private record Fixture(WorldId worldId, BackupId backupId, BackupRecord record) {
        DestinationResult destination(DestinationType type) {
            return BackupRecoveryServiceTest.destination(record.result(), type);
        }
    }

    private static final class FakeDestination implements RecoveryDestination {
        private final DestinationType type;

        private final WorldId materializedWorldId;

        private final AtomicInteger activeMaterializations = new AtomicInteger();

        private final AtomicInteger maximumActiveMaterializations = new AtomicInteger();

        private final AtomicInteger materializeCalls = new AtomicInteger();

        private final AtomicInteger deleteCalls = new AtomicInteger();

        private final AtomicInteger verifyCalls = new AtomicInteger();

        private final AtomicInteger syncCalls = new AtomicInteger();

        private VerificationOutcome verification = VerificationOutcome.verified("verified");

        private IOException materializeFailure;

        private boolean deleteResult = true;

        private boolean pauseMaterialization;

        private boolean writeUnexpectedInternalMetadata;

        private BlockingStep verificationBlock;

        private BlockingStep materializationBlock;

        private BlockingStep deletionBlock;

        private BlockingStep syncBlock;

        private DestinationResult syncResult;

        private List<DestinationType> calls = new ArrayList<>();

        private FakeDestination(DestinationType type, WorldId materializedWorldId) {
            this.type = type;
            this.materializedWorldId = materializedWorldId;
        }

        @Override
        public DestinationType destinationType() {
            return type;
        }

        @Override
        public VerificationOutcome verify(
                BackupRecord record,
                DestinationResult destination) throws Exception {
            verifyCalls.incrementAndGet();
            if (verificationBlock != null) {
                verificationBlock.block();
            }
            return verification;
        }

        @Override
        public void materialize(
                BackupRecord record,
                DestinationResult destination,
                Path emptyTarget) throws Exception {
            calls.add(type);
            materializeCalls.incrementAndGet();
            int active = activeMaterializations.incrementAndGet();
            maximumActiveMaterializations.accumulateAndGet(active, Math::max);
            try {
                if (materializationBlock != null) {
                    materializationBlock.block();
                }
                if (materializeFailure != null) {
                    throw materializeFailure;
                }
                if (pauseMaterialization) {
                    Thread.sleep(Duration.ofMillis(30));
                }
                if (writeUnexpectedInternalMetadata) {
                    Path metadata = Files.createDirectories(emptyTarget.resolve(".worldarchive"));
                    Files.writeString(
                            metadata.resolve("world.json"),
                            "{\"schemaVersion\":1,\"worldId\":\"" + materializedWorldId
                                    + "\"}\n",
                            StandardCharsets.UTF_8);
                }
                Files.writeString(
                        emptyTarget.resolve("payload.txt"),
                        "payload-" + type,
                        StandardCharsets.UTF_8);
            } finally {
                activeMaterializations.decrementAndGet();
            }
        }

        @Override
        public boolean delete(
                BackupRecord record,
                DestinationResult destination) throws Exception {
            deleteCalls.incrementAndGet();
            if (deletionBlock != null) {
                deletionBlock.block();
            }
            return deleteResult;
        }

        @Override
        public DestinationResult sync(
                BackupRecord record,
                DestinationResult destination) throws Exception {
            syncCalls.incrementAndGet();
            if (syncBlock != null) {
                syncBlock.block();
            }
            return syncResult == null ? destination : syncResult;
        }

        @Override
        public DestinationHealth health(Optional<WorldId> worldId) {
            return new DestinationHealth(
                    type,
                    DestinationHealthStatus.HEALTHY,
                    type + " healthy",
                    CREATED_AT.plusSeconds(2));
        }
    }

    private static final class InMemoryCatalog implements BackupCatalog {
        private final ConcurrentMap<BackupId, BackupRecord> records = new ConcurrentHashMap<>();

        private Runnable afterUpdate;

        private InMemoryCatalog(BackupRecord... records) {
            for (BackupRecord record : records) {
                this.records.put(record.manifest().backupId(), record);
            }
        }

        @Override
        public void add(BackupRecord record) throws IOException {
            BackupRecord old = records.putIfAbsent(record.manifest().backupId(), record);
            if (old != null && !old.equals(record)) {
                throw new IOException("conflicting record");
            }
        }

        @Override
        public Optional<BackupRecord> find(BackupId backupId) {
            return Optional.ofNullable(records.get(backupId));
        }

        private Optional<BackupRecord> findUnchecked(BackupId backupId) {
            return find(backupId);
        }

        @Override
        public List<BackupRecord> listAll() {
            return List.copyOf(records.values());
        }

        @Override
        public List<BackupRecord> list(WorldId worldId) {
            return records.values().stream()
                    .filter(record -> record.manifest().worldId().equals(worldId))
                    .toList();
        }

        @Override
        public synchronized Optional<BackupRecord> update(
                BackupId backupId,
                UnaryOperator<BackupRecord> update) {
            BackupRecord existing = records.get(backupId);
            if (existing == null) {
                return Optional.empty();
            }
            BackupRecord replacement = update.apply(existing);
            records.put(backupId, replacement);
            if (afterUpdate != null) {
                afterUpdate.run();
            }
            return Optional.of(replacement);
        }

        @Override
        public boolean remove(BackupId backupId) {
            return records.remove(backupId) != null;
        }
    }

    private static final class BlockingStep {
        private final CountDownLatch entered = new CountDownLatch(1);

        private final CountDownLatch released = new CountDownLatch(1);

        private final AtomicBoolean interrupted = new AtomicBoolean();

        void awaitEntered() throws InterruptedException {
            assertTrue(entered.await(5, TimeUnit.SECONDS), "Blocking test step was not reached");
        }

        void release() {
            released.countDown();
        }

        boolean interrupted() {
            return interrupted.get();
        }

        void block() throws InterruptedException {
            entered.countDown();
            try {
                if (!released.await(5, TimeUnit.SECONDS)) {
                    throw new AssertionError("Blocking test step was not released");
                }
            } catch (InterruptedException exception) {
                interrupted.set(true);
                throw exception;
            }
        }

        void blockForIo() throws IOException {
            try {
                block();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IOException("Blocking I/O test step was interrupted", exception);
            }
        }

        void blockUnchecked() {
            try {
                block();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new AssertionError("Catalog publication was interrupted", exception);
            }
        }
    }

    private static final class MutableClock extends Clock {
        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            if (!ZoneOffset.UTC.equals(zone)) {
                throw new UnsupportedOperationException("Test clock only supports UTC");
            }
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
