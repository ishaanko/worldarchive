package dev.ishaankot.worldarchive.storage.git;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.ishaankot.worldarchive.catalog.FileBackupCatalog;
import dev.ishaankot.worldarchive.config.WorldIdentityStore;
import dev.ishaankot.worldarchive.core.BackupCapture;
import dev.ishaankot.worldarchive.core.LockingWorldOperationGate;
import dev.ishaankot.worldarchive.core.ProgressListener;
import dev.ishaankot.worldarchive.core.RestoreBackupRequest;
import dev.ishaankot.worldarchive.core.RestoreBackupResult;
import dev.ishaankot.worldarchive.model.BackupId;
import dev.ishaankot.worldarchive.model.BackupManifest;
import dev.ishaankot.worldarchive.model.BackupRecord;
import dev.ishaankot.worldarchive.model.BackupResult;
import dev.ishaankot.worldarchive.model.DestinationResult;
import dev.ishaankot.worldarchive.model.DestinationStatus;
import dev.ishaankot.worldarchive.model.SyncStatus;
import dev.ishaankot.worldarchive.model.WorldId;
import dev.ishaankot.worldarchive.model.WorldIdentity;
import dev.ishaankot.worldarchive.recovery.BackupRecoveryException;
import dev.ishaankot.worldarchive.recovery.BackupRecoveryService;
import dev.ishaankot.worldarchive.recovery.RestoredWorldMetadataFinalizer;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class GitBackupBackendRemoteIntegrationTest extends GitBackupBackendIntegrationTestSupport {
    @Test
    void concurrentWorldsPublishIndependentRefs() throws Exception {
        Path firstWorld = temporaryDirectory.resolve("concurrent-one");
        Path secondWorld = temporaryDirectory.resolve("concurrent-two");
        Files.createDirectories(firstWorld);
        Files.createDirectories(secondWorld);
        Files.writeString(firstWorld.resolve("level.dat"), "one");
        Files.writeString(secondWorld.resolve("level.dat"), "two");
        WorldId firstWorldId = WorldId.create();
        WorldId secondWorldId = WorldId.create();
        BackupId firstBackupId = BackupId.create();
        BackupId secondBackupId = BackupId.create();

        try (GitBackupBackend firstBackend = new GitBackupBackend(settings);
                GitBackupBackend secondBackend = new GitBackupBackend(settings)) {
            CompletableFuture<DestinationResult> first = firstBackend.createBackup(
                    capture(firstWorld, firstWorldId, firstBackupId, Instant.now()),
                    ProgressListener.NO_OP).toCompletableFuture();
            CompletableFuture<DestinationResult> second = secondBackend.createBackup(
                    capture(secondWorld, secondWorldId, secondBackupId, Instant.now()),
                    ProgressListener.NO_OP).toCompletableFuture();
            CompletableFuture.allOf(first, second).get(60, TimeUnit.SECONDS);

            assertEquals(DestinationStatus.SUCCESS, first.get().status());
            assertEquals(DestinationStatus.SUCCESS, second.get().status());
            assertEquals(2, await(firstBackend.listSnapshots(Optional.empty())).size());
            assertTrue(await(firstBackend.verifySnapshot(firstWorldId, firstBackupId)).valid());
            assertTrue(await(firstBackend.verifySnapshot(secondWorldId, secondBackupId)).valid());
        }
    }

    @Test
    void timeoutAfterRefPublicationReturnsPendingSync() throws Exception {
        Path world = temporaryDirectory.resolve("interrupted-remote");
        Files.createDirectories(world);
        Files.writeString(world.resolve("level.dat"), "locally durable");
        WorldId worldId = WorldId.create();
        BackupId backupId = BackupId.create();
        GitBackendSettings remoteSettings = settings(
                GitBackendSettings.DEFAULT_LFS_PATTERNS,
                Optional.of(temporaryDirectory.resolve("remote.git").toUri().toString()));
        SystemGitCommandRunner systemRunner = new SystemGitCommandRunner();
        GitCommandRunner timedOutPush = command -> {
            if (command.arguments().contains("push")) {
                throw new GitCommandTimeoutException(Duration.ofMillis(1));
            }
            return systemRunner.run(command);
        };
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (GitBackupBackend backend = new GitBackupBackend(remoteSettings, timedOutPush, executor)) {
            DestinationResult result = await(backend.createBackup(
                    capture(world, worldId, backupId, Instant.now()),
                    ProgressListener.NO_OP));

            assertEquals(DestinationStatus.PENDING_SYNC, result.status());
            assertEquals(GitSnapshot.refName(worldId, backupId), result.artifactId().orElseThrow());
            assertEquals(backupId, await(backend.listSnapshots(Optional.of(worldId))).getFirst().backupId());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void unchangedCaptureReusesEveryWorldBlobAndLfsObject() throws Exception {
        Path world = temporaryDirectory.resolve("unchanged-world");
        Files.createDirectories(world.resolve("region"));
        Files.writeString(world.resolve("level.dat"), "unchanged level", StandardCharsets.UTF_8);
        Files.write(world.resolve("region/r.2.3.mca"), bytes(32_768, 67));
        WorldId worldId = WorldId.create();
        BackupId firstId = BackupId.create();
        BackupId secondId = BackupId.create();

        try (GitBackupBackend backend = new GitBackupBackend(settings)) {
            assertEquals(DestinationStatus.SUCCESS, await(backend.createBackup(
                    capture(world, worldId, firstId, Instant.now().minusSeconds(1)),
                    ProgressListener.NO_OP)).status());
            Map<String, String> firstObjects = worldTreeObjects(GitSnapshot.refName(worldId, firstId));
            long firstLfsObjects = lfsObjectCount();

            assertEquals(DestinationStatus.SUCCESS, await(backend.createBackup(
                    capture(world, worldId, secondId, Instant.now()),
                    ProgressListener.NO_OP)).status());

            assertEquals(firstObjects, worldTreeObjects(GitSnapshot.refName(worldId, secondId)));
            assertEquals(firstLfsObjects, lfsObjectCount());
            assertEquals(2, await(backend.listSnapshots(Optional.of(worldId))).size());
        }
    }

    @Test
    void restoresExactBytesFromRemoteWhenLocalRepositoryIsAbsent() throws Exception {
        Path remote = temporaryDirectory.resolve("remote-only.git");
        nativeGit("init", "--bare", remote.toString());
        Path world = temporaryDirectory.resolve("remote-only-world");
        Files.createDirectories(world.resolve("region"));
        Files.writeString(world.resolve("level.dat"), "remote recovery", StandardCharsets.UTF_8);
        Files.write(world.resolve("region/r.4.5.mca"), bytes(24_321, 73));
        Map<String, byte[]> expected = worldFiles(world);
        WorldId worldId = WorldId.create();
        BackupId backupId = BackupId.create();
        GitBackendSettings remoteSettings = settings(Optional.of(remote.toUri().toString()));
        BackupManifest manifest;
        DestinationResult destination;

        try (GitBackupBackend backend = new GitBackupBackend(remoteSettings)) {
            BackupCapture capture = capture(world, worldId, backupId, Instant.now());
            manifest = capture.manifest();
            destination = await(backend.createBackup(capture, ProgressListener.NO_OP));
            assertEquals(
                    DestinationStatus.SUCCESS,
                    destination.status(),
                    destination.message().orElse(""));
            assertEquals(SyncStatus.SYNCED, destination.syncStatus());
        }
        FileBackupCatalog catalog = new FileBackupCatalog(
                temporaryDirectory.resolve("remote-only-catalog.json"));
        catalog.add(new BackupRecord(
                manifest,
                BackupResult.aggregate(
                        backupId,
                        worldId,
                        List.of(destination),
                        manifest.createdAt().plusSeconds(1))));
        deleteTree(remoteSettings.repository());

        try (GitBackupBackend recovered = new GitBackupBackend(remoteSettings)) {
            BackupRecoveryService service = new BackupRecoveryService(
                    catalog,
                    Optional.of(recovered),
                    Optional.empty(),
                    new WorldIdentityStore(),
                    RestoredWorldMetadataFinalizer.NO_OP,
                    Runnable::run,
                    new LockingWorldOperationGate());
            Path worlds = Files.createDirectory(temporaryDirectory.resolve("remote-only-worlds"));
            RestoreBackupResult restored = await(service.restoreBackup(
                    new RestoreBackupRequest(backupId, worlds, "Remote Restored"),
                    ProgressListener.NO_OP));

            assertWorldEquals(expected, restored.restoredWorldDirectory());
            WorldIdentity restoredIdentity = new WorldIdentityStore().loadOrCreateIdentity(
                    restored.restoredWorldDirectory());
            assertNotEquals(worldId, restoredIdentity.worldId());
            assertEquals(backupId, restoredIdentity.sourceBackupId().orElseThrow());
            assertEquals(backupId, await(recovered.listSnapshots(Optional.of(worldId))).getFirst().backupId());
            assertTrue(nativeGit(
                    "--git-dir=" + remoteSettings.repository(),
                    "for-each-ref",
                    "--format=%(refname)",
                    "refs/worldarchive/fetch/").isBlank());
        }
    }

    @Test
    void remoteCatalogMismatchDoesNotInstallCanonicalLocalRef() throws Exception {
        Path remote = temporaryDirectory.resolve("catalog-mismatch.git");
        nativeGit("init", "--bare", remote.toString());
        Path world = Files.createDirectory(temporaryDirectory.resolve("catalog-mismatch-world"));
        Files.writeString(world.resolve("level.dat"), "remote manifest");
        WorldId worldId = WorldId.create();
        BackupId backupId = BackupId.create();
        GitBackendSettings remoteSettings = settings(Optional.of(remote.toUri().toString()));
        BackupManifest captured;
        DestinationResult destination;

        try (GitBackupBackend backend = new GitBackupBackend(remoteSettings)) {
            BackupCapture capture = capture(world, worldId, backupId, Instant.now());
            captured = capture.manifest();
            destination = await(backend.createBackup(capture, ProgressListener.NO_OP));
            assertEquals(DestinationStatus.SUCCESS, destination.status());
        }
        BackupManifest divergentCatalogManifest = new BackupManifest(
                captured.formatVersion(),
                captured.backupId(),
                captured.worldId(),
                captured.worldName(),
                Optional.of("divergent catalog label"),
                captured.createdAt(),
                captured.trigger(),
                captured.sourceFileCount(),
                captured.sourceByteCount(),
                captured.changedFileCount(),
                captured.contentSha256(),
                captured.inventorySha256());
        FileBackupCatalog catalog = new FileBackupCatalog(
                temporaryDirectory.resolve("catalog-mismatch.json"));
        catalog.add(new BackupRecord(
                divergentCatalogManifest,
                BackupResult.aggregate(
                        backupId,
                        worldId,
                        List.of(destination),
                        captured.createdAt().plusSeconds(1))));
        deleteTree(remoteSettings.repository());

        try (GitBackupBackend recovered = new GitBackupBackend(remoteSettings)) {
            BackupRecoveryService service = new BackupRecoveryService(
                    catalog,
                    Optional.of(recovered),
                    Optional.empty(),
                    new WorldIdentityStore(),
                    RestoredWorldMetadataFinalizer.NO_OP,
                    Runnable::run,
                    new LockingWorldOperationGate());
            Path worlds = Files.createDirectory(temporaryDirectory.resolve("catalog-mismatch-worlds"));

            assertThrows(
                    BackupRecoveryException.class,
                    () -> await(service.restoreBackup(
                            new RestoreBackupRequest(backupId, worlds, "Must Fail"),
                            ProgressListener.NO_OP)));
            assertTrue(nativeGit(
                    "--git-dir=" + remoteSettings.repository(),
                    "for-each-ref",
                    "--format=%(refname)",
                    GitSnapshot.refName(worldId, backupId)).isBlank());
            assertTrue(nativeGit(
                    "--git-dir=" + remoteSettings.repository(),
                    "for-each-ref",
                    "--format=%(refname)",
                    "refs/worldarchive/fetch/").isBlank());
        }
    }

    @Test
    void cancelledRecoveryCleansAtomicallyReplacedGitStaging() throws Exception {
        Path world = Files.createDirectory(temporaryDirectory.resolve("cancelled-recovery-world"));
        Files.writeString(world.resolve("level.dat"), "cancel after Git publication");
        WorldId worldId = WorldId.create();
        BackupId backupId = BackupId.create();
        BackupManifest manifest;
        DestinationResult destination;
        try (GitBackupBackend backend = new GitBackupBackend(settings)) {
            BackupCapture capture = capture(world, worldId, backupId, Instant.now());
            manifest = capture.manifest();
            destination = await(backend.createBackup(capture, ProgressListener.NO_OP));
        }
        FileBackupCatalog catalog = new FileBackupCatalog(
                temporaryDirectory.resolve("cancelled-recovery-catalog.json"));
        catalog.add(new BackupRecord(
                manifest,
                BackupResult.aggregate(
                        backupId,
                        worldId,
                        List.of(destination),
                        manifest.createdAt().plusSeconds(1))));

        CountDownLatch checkoutEntered = new CountDownLatch(1);
        CountDownLatch releaseCheckout = new CountDownLatch(1);
        SystemGitCommandRunner systemRunner = new SystemGitCommandRunner();
        GitCommandRunner blockingCheckout = command -> {
            if (command.arguments().contains("checkout-index")) {
                checkoutEntered.countDown();
                releaseCheckout.await();
            }
            return systemRunner.run(command);
        };
        Path worlds = Files.createDirectory(temporaryDirectory.resolve("cancelled-recovery-worlds"));
        try (ExecutorService backendExecutor = Executors.newSingleThreadExecutor();
                ExecutorService serviceExecutor = Executors.newSingleThreadExecutor();
                GitBackupBackend recovered = new GitBackupBackend(
                        settings, blockingCheckout, backendExecutor)) {
            BackupRecoveryService service = new BackupRecoveryService(
                    catalog,
                    Optional.of(recovered),
                    Optional.empty(),
                    new WorldIdentityStore(),
                    RestoredWorldMetadataFinalizer.NO_OP,
                    serviceExecutor,
                    new LockingWorldOperationGate());
            CompletableFuture<RestoreBackupResult> future = service.restoreBackup(
                            new RestoreBackupRequest(backupId, worlds, "Cancelled Restore"),
                            ProgressListener.NO_OP)
                    .toCompletableFuture();
            try {
                assertTrue(checkoutEntered.await(30, TimeUnit.SECONDS));
                assertTrue(future.cancel(true));
            } finally {
                releaseCheckout.countDown();
            }
            assertThrows(CancellationException.class, future::join);
        }

        try (Stream<Path> children = Files.list(worlds)) {
            assertEquals(List.of(), children.toList());
        }
    }

    @Test
    void failedGitMaterializationPreservesRecoveryStagingIdentityForCleanup() throws Exception {
        Path world = Files.createDirectory(temporaryDirectory.resolve("failed-recovery-world"));
        Files.writeString(world.resolve("level.dat"), "fail Git checkout");
        WorldId worldId = WorldId.create();
        BackupId backupId = BackupId.create();
        BackupManifest manifest;
        DestinationResult destination;
        try (GitBackupBackend backend = new GitBackupBackend(settings)) {
            BackupCapture capture = capture(world, worldId, backupId, Instant.now());
            manifest = capture.manifest();
            destination = await(backend.createBackup(capture, ProgressListener.NO_OP));
        }
        FileBackupCatalog catalog = new FileBackupCatalog(
                temporaryDirectory.resolve("failed-recovery-catalog.json"));
        catalog.add(new BackupRecord(
                manifest,
                BackupResult.aggregate(
                        backupId,
                        worldId,
                        List.of(destination),
                        manifest.createdAt().plusSeconds(1))));

        SystemGitCommandRunner systemRunner = new SystemGitCommandRunner();
        GitCommandRunner failedCheckout = command -> {
            if (command.arguments().contains("checkout-index")) {
                throw new IOException("simulated Git checkout failure");
            }
            return systemRunner.run(command);
        };
        Path worlds = Files.createDirectory(temporaryDirectory.resolve("failed-recovery-worlds"));
        try (ExecutorService backendExecutor = Executors.newSingleThreadExecutor();
                GitBackupBackend recovered = new GitBackupBackend(
                        settings, failedCheckout, backendExecutor)) {
            BackupRecoveryService service = new BackupRecoveryService(
                    catalog,
                    Optional.of(recovered),
                    Optional.empty(),
                    new WorldIdentityStore(),
                    RestoredWorldMetadataFinalizer.NO_OP,
                    Runnable::run,
                    new LockingWorldOperationGate());

            assertThrows(
                    BackupRecoveryException.class,
                    () -> await(service.restoreBackup(
                            new RestoreBackupRequest(backupId, worlds, "Failed Restore"),
                            ProgressListener.NO_OP)));
        }

        try (Stream<Path> children = Files.list(worlds)) {
            assertEquals(List.of(), children.toList());
        }
    }

    @Test
    void atomicallyPublishedRefsAreAcceptedAfterAnAmbiguousCommandTimeout() throws Exception {
        Path world = temporaryDirectory.resolve("ambiguous-publication-world");
        Files.createDirectories(world);
        Files.writeString(world.resolve("level.dat"), "must not remain published");
        WorldId worldId = WorldId.create();
        BackupId backupId = BackupId.create();
        String snapshotRef = GitSnapshot.refName(worldId, backupId);
        SystemGitCommandRunner systemRunner = new SystemGitCommandRunner();
        AtomicInteger refUpdates = new AtomicInteger();
        GitCommandRunner ambiguousRunner = command -> {
            if (command.arguments().contains("update-ref")
                    && new String(command.standardInput(), StandardCharsets.UTF_8)
                            .contains(snapshotRef)) {
                if (refUpdates.incrementAndGet() == 1) {
                    systemRunner.run(command);
                    throw new GitCommandTimeoutException(Duration.ofMillis(1));
                }
            }
            return systemRunner.run(command);
        };
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (GitBackupBackend backend = new GitBackupBackend(settings, ambiguousRunner, executor)) {
            DestinationResult result = await(backend.createBackup(
                    capture(world, worldId, backupId, Instant.now()),
                    ProgressListener.NO_OP));

            assertEquals(DestinationStatus.SUCCESS, result.status());
            assertEquals(
                    List.of(backupId),
                    await(backend.listSnapshots(Optional.of(worldId))).stream()
                            .map(GitSnapshot::backupId)
                            .toList());
            assertEquals(1, refUpdates.get());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void deletionCannotInterleaveBetweenLocalPublicationAndRemotePush() throws Exception {
        Path remote = temporaryDirectory.resolve("serialized-remote.git");
        nativeGit("init", "--bare", remote.toString());
        Path world = temporaryDirectory.resolve("serialized-create-world");
        Files.createDirectories(world);
        Files.writeString(world.resolve("level.dat"), "serialized");
        WorldId worldId = WorldId.create();
        BackupId backupId = BackupId.create();
        GitBackendSettings remoteSettings = settings(Optional.of(remote.toUri().toString()));
        CountDownLatch pushEntered = new CountDownLatch(1);
        CountDownLatch releasePush = new CountDownLatch(1);
        SystemGitCommandRunner systemRunner = new SystemGitCommandRunner();
        GitCommandRunner blockingPush = command -> {
            if (command.arguments().contains("lfs") && command.arguments().contains("push")) {
                pushEntered.countDown();
                if (!releasePush.await(10, TimeUnit.SECONDS)) {
                    throw new IOException("Test did not release Git LFS push");
                }
            }
            return systemRunner.run(command);
        };
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (GitBackupBackend creator = new GitBackupBackend(remoteSettings, blockingPush, executor);
                GitBackupBackend deleter = new GitBackupBackend(remoteSettings)) {
            CompletableFuture<DestinationResult> create = creator.createBackup(
                    capture(world, worldId, backupId, Instant.now()),
                    ProgressListener.NO_OP).toCompletableFuture();
            assertTrue(pushEntered.await(30, TimeUnit.SECONDS));
            CompletableFuture<Boolean> delete = deleter.deleteSnapshot(worldId, backupId).toCompletableFuture();
            assertThrows(java.util.concurrent.TimeoutException.class, () -> delete.get(250, TimeUnit.MILLISECONDS));
            releasePush.countDown();

            assertEquals(DestinationStatus.SUCCESS, create.get(30, TimeUnit.SECONDS).status());
            assertTrue(delete.get(30, TimeUnit.SECONDS));
        } finally {
            releasePush.countDown();
            executor.shutdownNow();
        }
    }

}
