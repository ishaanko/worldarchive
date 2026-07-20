package dev.ishaankot.worldarchive.recovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.ishaankot.worldarchive.config.WorldIdentityStore;
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
import dev.ishaankot.worldarchive.model.BackupTrigger;
import dev.ishaankot.worldarchive.model.DestinationResult;
import dev.ishaankot.worldarchive.model.DestinationType;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

class BackupRecoveryServiceTest extends BackupRecoveryServiceTestSupport {

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
    void cancellingRestoreDrainsNestedGitMaterializationBeforeStagingCleanup() throws Exception {
        Fixture fixture = fixture(DestinationType.GIT);
        FakeDestination git = new FakeDestination(DestinationType.GIT, fixture.worldId());
        BlockingStep nestedWork = new BlockingStep();
        DrainAwareFuture<Path> nestedResult = new DrainAwareFuture<>();
        AtomicBoolean nestedFinished = new AtomicBoolean();
        Path worlds = Files.createDirectory(temporaryDirectory.resolve("nested-cancel-worlds"));
        try (ExecutorService nestedExecutor = Executors.newSingleThreadExecutor();
                ExecutorService serviceExecutor = Executors.newThreadPerTaskExecutor(
                        Thread.ofVirtual().factory())) {
            git.nestedMaterialization = emptyTarget -> {
                nestedExecutor.execute(() -> {
                    nestedWork.blockUnchecked();
                    try {
                        Files.createDirectories(emptyTarget);
                        Files.writeString(emptyTarget.resolve("nested.txt"), "finished");
                        nestedFinished.set(true);
                        nestedResult.complete(emptyTarget);
                    } catch (IOException exception) {
                        nestedResult.completeExceptionally(exception);
                    }
                });
                return nestedResult;
            };
            BackupRecoveryService service = service(
                    new InMemoryCatalog(fixture.record()),
                    Map.of(DestinationType.GIT, git),
                    new MutableClock(CREATED_AT.plusSeconds(2)),
                    RestoredWorldMetadataFinalizer.NO_OP,
                    serviceExecutor);

            var future = service.restoreBackup(
                            new RestoreBackupRequest(
                                    fixture.backupId(), worlds, "Nested Cancel Copy"),
                            ProgressListener.NO_OP)
                    .toCompletableFuture();
            nestedWork.awaitEntered();
            try {
                assertTrue(future.cancel(true));
                nestedResult.awaitDrainStarted();
            } finally {
                nestedWork.release();
            }
            assertThrows(CancellationException.class, future::join);
        }

        assertTrue(nestedFinished.get());
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
    void moveThenThrowIsReconciledByExactStagingIdentity() throws IOException {
        Fixture fixture = fixture(DestinationType.ZIP);
        FakeDestination zip = new FakeDestination(DestinationType.ZIP, fixture.worldId());
        Path worlds = Files.createDirectory(temporaryDirectory.resolve("ambiguous-atomic-worlds"));
        BackupRecoveryService service = service(
                new InMemoryCatalog(fixture.record()),
                Map.of(DestinationType.ZIP, zip),
                new MutableClock(CREATED_AT.plusSeconds(2)),
                (source, target, options) -> {
                    Files.move(source, target, options);
                    throw new IOException("simulated error after atomic publication");
                });

        RestoreBackupResult result = service.restoreBackup(
                        new RestoreBackupRequest(
                                fixture.backupId(), worlds, "Ambiguous Copy"),
                        ProgressListener.NO_OP)
                .toCompletableFuture().join();

        assertTrue(Files.isDirectory(result.restoredWorldDirectory()));
        assertEquals("payload-ZIP", Files.readString(
                result.restoredWorldDirectory().resolve("payload.txt")));
        try (var children = Files.list(worlds)) {
            List<Path> published = children.toList();
            assertEquals(1, published.size());
            assertTrue(Files.isSameFile(result.restoredWorldDirectory(), published.getFirst()));
        }
    }

    @Test
    void validationFailurePreservesUnrelatedReplacementTarget() throws IOException {
        Fixture fixture = fixture(DestinationType.ZIP);
        FakeDestination zip = new FakeDestination(DestinationType.ZIP, fixture.worldId());
        Path worlds = Files.createDirectory(temporaryDirectory.resolve("replaced-target-worlds"));
        Path displacedRestore = temporaryDirectory.resolve("displaced-restore");
        AtomicReference<Path> replacement = new AtomicReference<>();
        BackupRecoveryService service = service(
                new InMemoryCatalog(fixture.record()),
                Map.of(DestinationType.ZIP, zip),
                new MutableClock(CREATED_AT.plusSeconds(2)),
                (source, target, options) -> {
                    Files.move(source, target, options);
                    Files.move(target, displacedRestore);
                    Files.createDirectory(target);
                    Files.writeString(target.resolve("unrelated.txt"), "preserve me");
                    replacement.set(target);
                    return target;
                });

        assertRecoveryFailure(() -> service.restoreBackup(
                        new RestoreBackupRequest(
                                fixture.backupId(), worlds, "Replaced Target Copy"),
                        ProgressListener.NO_OP)
                .toCompletableFuture().join());

        Path unrelated = replacement.get();
        assertTrue(Files.isDirectory(unrelated));
        assertEquals("preserve me", Files.readString(unrelated.resolve("unrelated.txt")));
        assertTrue(Files.isDirectory(displacedRestore));
    }

    @Test
    void materializationReplacementIsNeverDeletedDuringCleanup() throws IOException {
        Fixture fixture = fixture(DestinationType.ZIP);
        FakeDestination zip = new FakeDestination(DestinationType.ZIP, fixture.worldId());
        zip.replaceStagingBeforeReturn = true;
        Path worlds = Files.createDirectory(temporaryDirectory.resolve("replaced-staging-worlds"));
        BackupRecoveryService service = service(
                new InMemoryCatalog(fixture.record()),
                Map.of(DestinationType.ZIP, zip),
                new MutableClock(CREATED_AT.plusSeconds(2)));

        assertRecoveryFailure(() -> service.restoreBackup(
                        new RestoreBackupRequest(
                                fixture.backupId(), worlds, "Replaced Staging Copy"),
                        ProgressListener.NO_OP)
                .toCompletableFuture().join());

        try (var children = Files.list(worlds)) {
            Path replacement = children.findFirst().orElseThrow();
            assertEquals("preserve me", Files.readString(replacement.resolve("unrelated.txt")));
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

}
