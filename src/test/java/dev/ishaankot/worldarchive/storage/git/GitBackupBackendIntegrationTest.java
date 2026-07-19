package dev.ishaankot.worldarchive.storage.git;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
import dev.ishaankot.worldarchive.model.BackupTrigger;
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
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.DosFileAttributeView;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GitBackupBackendIntegrationTest {
    private static final Duration TEST_TIMEOUT = Duration.ofSeconds(30);

    private static final Duration WINDOWS_DELETE_RETRY_TIMEOUT = Duration.ofSeconds(2);

    @TempDir
    Path temporaryDirectory;

    private GitBackendSettings settings;

    @BeforeEach
    void requireNativeGitAndLfs() throws Exception {
        settings = settings(Optional.empty());
        GitToolHealth health = new GitToolProbe(settings, new SystemGitCommandRunner()).probe();
        Assumptions.assumeTrue(health.available(), health.summary());
    }

    @Test
    void createsChangesDeletesListsVerifiesAndRestoresIndependentRefs() throws Exception {
        Path world = temporaryDirectory.resolve("World ü with spaces");
        Files.createDirectories(world.resolve("region"));
        Files.createDirectories(world.resolve("data Ω"));
        byte[] firstRegion = bytes(16_384, 17);
        Files.write(world.resolve("region/r.0.0.mca"), firstRegion);
        Files.writeString(world.resolve("level.dat"), "first level", StandardCharsets.UTF_8);
        Files.writeString(world.resolve("data Ω/你好.txt"), "snowman ☃", StandardCharsets.UTF_8);
        Files.writeString(world.resolve("session.lock"), "not captured", StandardCharsets.UTF_8);
        Files.createDirectories(world.resolve(".worldarchive"));
        Files.writeString(world.resolve(".worldarchive/world.json"), "not captured", StandardCharsets.UTF_8);
        Map<String, byte[]> firstExpected = worldFiles(world);
        WorldId worldId = WorldId.create();
        BackupId firstId = BackupId.create();

        try (GitBackupBackend backend = new GitBackupBackend(settings)) {
            BackupCapture firstCapture = capture(
                    world, worldId, firstId, Instant.now().minusSeconds(5));
            DestinationResult first = await(backend.createBackup(
                    firstCapture,
                    ProgressListener.NO_OP));
            assertEquals(DestinationStatus.SUCCESS, first.status(), first.message().orElse(""));

            Files.writeString(world.resolve("level.dat"), "second level", StandardCharsets.UTF_8);
            Files.delete(world.resolve("data Ω/你好.txt"));
            Files.writeString(world.resolve("created-新.txt"), "new", StandardCharsets.UTF_8);
            byte[] secondRegion = bytes(20_000, 29);
            Files.write(world.resolve("region/r.0.0.mca"), secondRegion);
            Map<String, byte[]> secondExpected = worldFiles(world);
            BackupId secondId = BackupId.create();
            BackupCapture secondCapture = capture(world, worldId, secondId, Instant.now());

            DestinationResult second = await(backend.createBackup(
                    secondCapture,
                    ProgressListener.NO_OP));
            assertEquals(DestinationStatus.SUCCESS, second.status(), second.message().orElse(""));

            List<GitSnapshot> snapshots = await(backend.listSnapshots(Optional.of(worldId)));
            assertEquals(2, snapshots.size());
            assertEquals(2, snapshots.stream().map(GitSnapshot::commitId).distinct().count());
            assertTrue(snapshots.stream().allMatch(
                    snapshot -> snapshot.refName().startsWith("refs/heads/worldarchive/")));
            GitVerification firstVerification = await(backend.verifySnapshot(worldId, firstId));
            GitVerification secondVerification = await(backend.verifySnapshot(worldId, secondId));
            assertTrue(firstVerification.valid());
            assertTrue(secondVerification.valid());
            assertEquals(firstCapture.manifest(), firstVerification.manifest().orElseThrow());
            assertEquals(secondCapture.manifest(), secondVerification.manifest().orElseThrow());

            Path firstRestore = temporaryDirectory.resolve("restore first Ω");
            await(backend.restoreSnapshot(worldId, firstId, firstRestore));
            assertWorldEquals(firstExpected, firstRestore);
            assertFalse(Files.exists(firstRestore.resolve(".git")));
            assertFalse(Files.exists(firstRestore.resolve(GitBackupBackend.MANIFEST_PATH)));

            Path secondRestore = temporaryDirectory.resolve("restore second");
            await(backend.restoreSnapshot(worldId, secondId, secondRestore));
            assertWorldEquals(secondExpected, secondRestore);

            assertTrue(await(backend.deleteSnapshot(worldId, firstId)));
            assertFalse(await(backend.deleteSnapshot(worldId, firstId)));
            assertEquals(List.of(secondId), await(backend.listSnapshots(Optional.of(worldId))).stream()
                    .map(GitSnapshot::backupId)
                    .toList());
            Path remainingRestore = temporaryDirectory.resolve("remaining");
            await(backend.restoreSnapshot(worldId, secondId, remainingRestore));
            assertWorldEquals(secondExpected, remainingRestore);
        }
    }

    @Test
    void missingLfsObjectFailsVerification() throws Exception {
        Path world = temporaryDirectory.resolve("lfs-world");
        Files.createDirectories(world.resolve("region"));
        Files.write(world.resolve("region/r.1.2.mca"), bytes(8_192, 7));
        WorldId worldId = WorldId.create();
        BackupId backupId = BackupId.create();

        try (GitBackupBackend backend = new GitBackupBackend(settings)) {
            assertEquals(DestinationStatus.SUCCESS, await(backend.createBackup(
                    capture(world, worldId, backupId, Instant.now()),
                    ProgressListener.NO_OP)).status());
            GitSnapshot snapshot = await(backend.listSnapshots(Optional.of(worldId))).getFirst();
            String pointer = nativeGit(
                    "--git-dir=" + settings.repository(),
                    "show",
                    snapshot.refName() + ":region/r.1.2.mca");
            assertTrue(pointer.startsWith("version https://git-lfs.github.com/spec/v1"));
            String oid = pointer.lines()
                    .filter(line -> line.startsWith("oid sha256:"))
                    .findFirst()
                    .orElseThrow()
                    .substring("oid sha256:".length());
            Path object = settings.repository().resolve("lfs/objects")
                    .resolve(oid.substring(0, 2))
                    .resolve(oid.substring(2, 4))
                    .resolve(oid);
            Path parked = object.resolveSibling(oid + ".parked");
            Files.move(object, parked);
            try {
                assertFalse(await(backend.verifySnapshot(worldId, backupId)).valid());
            } finally {
                Files.move(parked, object);
            }
        }
    }

    @Test
    void remoteFailureLeavesLocalRefPendingSync() throws Exception {
        Path world = temporaryDirectory.resolve("remote-world");
        Files.createDirectories(world);
        Files.writeString(world.resolve("level.dat"), "durable locally", StandardCharsets.UTF_8);
        WorldId worldId = WorldId.create();
        BackupId backupId = BackupId.create();
        GitBackendSettings remoteSettings = settings(Optional.of(
                temporaryDirectory.resolve("missing remote.git").toUri().toString()));

        try (GitBackupBackend backend = new GitBackupBackend(remoteSettings)) {
            DestinationResult result = await(backend.createBackup(
                    capture(world, worldId, backupId, Instant.now()),
                    ProgressListener.NO_OP));

            assertEquals(DestinationStatus.PENDING_SYNC, result.status());
            assertEquals(backupId, await(backend.listSnapshots(Optional.of(worldId))).getFirst().backupId());
            Path restore = temporaryDirectory.resolve("pending-restore");
            await(backend.restoreSnapshot(worldId, backupId, restore));
            assertEquals("durable locally", Files.readString(restore.resolve("level.dat")));
        }
    }

    @Test
    void remoteAuthenticationFailureIsPendingAndCredentialSafe() throws Exception {
        Path remote = temporaryDirectory.resolve("auth-failure-remote.git");
        nativeGit("init", "--bare", remote.toString());
        Path world = Files.createDirectories(temporaryDirectory.resolve("auth-failure-world"));
        Files.writeString(world.resolve("notes.txt"), "local remains durable", StandardCharsets.UTF_8);
        WorldId worldId = WorldId.create();
        BackupId backupId = BackupId.create();
        GitBackendSettings remoteSettings = settings(Optional.of(remote.toUri().toString()));
        SystemGitCommandRunner systemRunner = new SystemGitCommandRunner();
        GitCommandRunner authenticationFailure = command -> {
            if (command.arguments().contains("push")) {
                throw new IOException(
                        "authentication failed password=visible-secret token=ghp_1234567890abcdef");
            }
            return systemRunner.run(command);
        };
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (GitBackupBackend backend = new GitBackupBackend(
                remoteSettings,
                authenticationFailure,
                executor)) {
            DestinationResult result = await(backend.createBackup(
                    capture(world, worldId, backupId, Instant.now()),
                    ProgressListener.NO_OP));

            assertEquals(DestinationStatus.PENDING_SYNC, result.status());
            String message = result.message().orElseThrow();
            assertTrue(message.toLowerCase(java.util.Locale.ROOT).contains("authentication"));
            assertFalse(message.contains("visible-secret"));
            assertFalse(message.contains("ghp_1234567890abcdef"));
            assertEquals(backupId, await(backend.listSnapshots(Optional.of(worldId)))
                    .getFirst().backupId());
            assertTrue(remoteRef(remote, GitSnapshot.refName(worldId, backupId)).isEmpty());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void remoteWithoutLfsLeavesVerifiedLocalSnapshotPendingSync() throws Exception {
        Path remote = temporaryDirectory.resolve("no-lfs-remote.git");
        nativeGit("init", "--bare", remote.toString());
        Path world = Files.createDirectories(temporaryDirectory.resolve("no-lfs-world"));
        Files.createDirectories(world.resolve("region"));
        Files.write(world.resolve("region/r.0.0.mca"), bytes(8_192, 41));
        WorldId worldId = WorldId.create();
        BackupId backupId = BackupId.create();
        GitBackendSettings remoteSettings = settings(Optional.of(remote.toUri().toString()));
        SystemGitCommandRunner systemRunner = new SystemGitCommandRunner();
        GitCommandRunner remoteWithoutLfs = command -> {
            if (command.arguments().contains("lfs") && command.arguments().contains("push")) {
                throw new IOException("remote does not support Git LFS uploads");
            }
            return systemRunner.run(command);
        };
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (GitBackupBackend backend = new GitBackupBackend(
                remoteSettings,
                remoteWithoutLfs,
                executor)) {
            DestinationResult result = await(backend.createBackup(
                    capture(world, worldId, backupId, Instant.now()),
                    ProgressListener.NO_OP));

            assertEquals(DestinationStatus.PENDING_SYNC, result.status());
            assertTrue(result.message().orElseThrow().contains("does not support Git LFS"));
            assertTrue(await(backend.verifySnapshot(worldId, backupId)).valid());
            assertTrue(remoteRef(remote, GitSnapshot.refName(worldId, backupId)).isEmpty());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void successfulRemotePushUsesTheHeadsNamespace() throws Exception {
        Path remote = temporaryDirectory.resolve("remote repository.git");
        nativeGit("init", "--bare", remote.toString());
        Path world = temporaryDirectory.resolve("remote-success-world");
        Files.createDirectories(world);
        Files.writeString(world.resolve("notes.txt"), "remote snapshot", StandardCharsets.UTF_8);
        WorldId worldId = WorldId.create();
        BackupId backupId = BackupId.create();
        GitBackendSettings remoteSettings = settings(Optional.of(remote.toUri().toString()));

        try (GitBackupBackend backend = new GitBackupBackend(remoteSettings)) {
            DestinationResult result = await(backend.createBackup(
                    capture(world, worldId, backupId, Instant.now()),
                    ProgressListener.NO_OP));

            assertEquals(DestinationStatus.SUCCESS, result.status(), result.message().orElse(""));
            assertEquals(SyncStatus.SYNCED, result.syncStatus());
            String expectedRef = GitSnapshot.refName(worldId, backupId);
            assertEquals(expectedRef, result.artifactId().orElseThrow());
            String remoteCommit = nativeGit(
                    "--git-dir=" + remote,
                    "rev-parse",
                    "--verify",
                    expectedRef).trim();
            assertEquals(await(backend.listSnapshots(Optional.of(worldId))).getFirst().commitId(), remoteCommit);
        }
    }

    @Test
    void deletesRemoteRefBeforeLocalRefAndRetriesAfterLocalFailure() throws Exception {
        Path remote = temporaryDirectory.resolve("remote-delete.git");
        nativeGit("init", "--bare", remote.toString());
        Path world = temporaryDirectory.resolve("remote-delete-world");
        Files.createDirectories(world);
        Files.writeString(world.resolve("level.dat"), "remote deletion", StandardCharsets.UTF_8);
        WorldId worldId = WorldId.create();
        BackupId backupId = BackupId.create();
        String snapshotRef = GitSnapshot.refName(worldId, backupId);
        GitBackendSettings remoteSettings = settings(Optional.of(remote.toUri().toString()));
        SystemGitCommandRunner systemRunner = new SystemGitCommandRunner();
        AtomicInteger localDeleteAttempts = new AtomicInteger();
        GitCommandRunner failFirstLocalDelete = command -> {
            if (command.arguments().contains("update-ref")
                    && command.arguments().contains("-d")
                    && command.arguments().contains(snapshotRef)) {
                int attempt = localDeleteAttempts.getAndIncrement();
                if (attempt == 0) {
                    throw new IOException("simulated local ref deletion failure");
                }
                if (attempt == 1) {
                    systemRunner.run(command);
                    throw new GitCommandTimeoutException(Duration.ofMillis(1));
                }
            }
            return systemRunner.run(command);
        };
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (GitBackupBackend backend = new GitBackupBackend(
                remoteSettings,
                failFirstLocalDelete,
                executor)) {
            DestinationResult result = await(backend.createBackup(
                    capture(world, worldId, backupId, Instant.now()),
                    ProgressListener.NO_OP));
            assertEquals(DestinationStatus.SUCCESS, result.status(), result.message().orElse(""));

            assertThrows(IOException.class, () -> await(backend.deleteSnapshot(worldId, backupId)));
            assertTrue(remoteRef(remote, snapshotRef).isEmpty());
            assertEquals(
                    backupId,
                    await(backend.listSnapshots(Optional.of(worldId))).getFirst().backupId());

            assertTrue(await(backend.deleteSnapshot(worldId, backupId)));
            assertTrue(remoteRef(remote, snapshotRef).isEmpty());
            assertTrue(await(backend.listSnapshots(Optional.of(worldId))).isEmpty());
            assertFalse(await(backend.deleteSnapshot(worldId, backupId)));
            assertEquals(2, localDeleteAttempts.get());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void refusesToReconcileMissingLocalSnapshotWhileExactRemoteRefExists() throws Exception {
        Path remote = temporaryDirectory.resolve("remote-only-delete.git");
        nativeGit("init", "--bare", remote.toString());
        Path world = temporaryDirectory.resolve("remote-only-delete-world");
        Files.createDirectories(world);
        Files.writeString(world.resolve("level.dat"), "remote-only deletion");
        WorldId worldId = WorldId.create();
        BackupId backupId = BackupId.create();
        String snapshotRef = GitSnapshot.refName(worldId, backupId);
        GitBackendSettings remoteSettings = settings(Optional.of(remote.toUri().toString()));

        try (GitBackupBackend backend = new GitBackupBackend(remoteSettings)) {
            DestinationResult result = await(backend.createBackup(
                    capture(world, worldId, backupId, Instant.now()),
                    ProgressListener.NO_OP));
            assertEquals(DestinationStatus.SUCCESS, result.status(), result.message().orElse(""));
            nativeGit(
                    "--git-dir=" + remoteSettings.repository(),
                    "update-ref",
                    "-d",
                    snapshotRef);

            assertThrows(GitStorageException.class, () ->
                    await(backend.deleteSnapshot(worldId, backupId)));
            assertTrue(remoteRef(remote, snapshotRef).isPresent());
        }
    }

    @Test
    void rejectsPreparedManifestThatDoesNotMatchCapturedWorld() throws Exception {
        Path world = temporaryDirectory.resolve("manifest-mismatch-world");
        Files.createDirectories(world);
        Files.writeString(world.resolve("level.dat"), "not represented by the manifest");
        WorldId worldId = WorldId.create();

        try (GitBackupBackend backend = new GitBackupBackend(settings)) {
            DestinationResult result = await(backend.createBackup(
                    captureWithoutScanning(world, worldId, BackupId.create(), Instant.now()),
                    ProgressListener.NO_OP));

            assertEquals(DestinationStatus.FAILED, result.status());
            assertTrue(await(backend.listSnapshots(Optional.of(worldId))).isEmpty());
        }
    }

    @Test
    void capturesNestedSessionLockButExcludesTheLiveWorldLock() throws Exception {
        Path world = temporaryDirectory.resolve("nested-lock-world");
        Files.createDirectories(world.resolve("nested"));
        Files.writeString(world.resolve("session.lock"), "live lock");
        Files.writeString(world.resolve("nested/session.lock"), "world data");
        WorldId worldId = WorldId.create();
        BackupId backupId = BackupId.create();

        try (GitBackupBackend backend = new GitBackupBackend(settings)) {
            DestinationResult result = await(backend.createBackup(
                    capture(world, worldId, backupId, Instant.now()),
                    ProgressListener.NO_OP));
            assertEquals(DestinationStatus.SUCCESS, result.status(), result.message().orElse(""));

            Path restored = temporaryDirectory.resolve("nested-lock-restore");
            await(backend.restoreSnapshot(worldId, backupId, restored));
            assertFalse(Files.exists(restored.resolve("session.lock")));
            assertEquals("world data", Files.readString(restored.resolve("nested/session.lock")));
        }
    }

    @Test
    void verifiesLargeFilesThatAreNotManagedByLfs() throws Exception {
        Path world = temporaryDirectory.resolve("large-ordinary-world");
        Files.createDirectories(world);
        byte[] original = bytes(5 * 1_024 * 1_024 + 17, 53);
        Files.write(world.resolve("large.bin"), original);
        WorldId worldId = WorldId.create();
        BackupId backupId = BackupId.create();

        try (GitBackupBackend backend = new GitBackupBackend(settings)) {
            DestinationResult result = await(backend.createBackup(
                    capture(world, worldId, backupId, Instant.now()),
                    ProgressListener.NO_OP));
            assertEquals(DestinationStatus.SUCCESS, result.status(), result.message().orElse(""));
            assertTrue(await(backend.verifySnapshot(worldId, backupId)).valid());

            Path restored = temporaryDirectory.resolve("large-ordinary-restore");
            await(backend.restoreSnapshot(worldId, backupId, restored));
            assertArrayEquals(original, Files.readAllBytes(restored.resolve("large.bin")));
        }
    }

    @Test
    void refusesRestoreIntoNonEmptyDirectory() throws Exception {
        Path world = temporaryDirectory.resolve("world");
        Files.createDirectories(world);
        Files.writeString(world.resolve("level.dat"), "level");
        WorldId worldId = WorldId.create();
        BackupId backupId = BackupId.create();

        try (GitBackupBackend backend = new GitBackupBackend(settings)) {
            await(backend.createBackup(capture(world, worldId, backupId, Instant.now()), ProgressListener.NO_OP));
            Path staging = temporaryDirectory.resolve("occupied");
            Files.createDirectories(staging);
            Files.writeString(staging.resolve("keep.txt"), "keep");

            CompletionException exception = assertThrows(
                    CompletionException.class,
                    () -> backend.restoreSnapshot(worldId, backupId, staging).toCompletableFuture().join());
            assertTrue(exception.getCause() instanceof GitStorageException);
            assertEquals("keep", Files.readString(staging.resolve("keep.txt")));
        }
    }

    @Test
    void refusesRestoreThroughAWindowsJunction() throws Exception {
        Assumptions.assumeTrue(System.getProperty("os.name").startsWith("Windows"));
        Path world = temporaryDirectory.resolve("restore-junction-world");
        Files.createDirectories(world);
        Files.writeString(world.resolve("level.dat"), "level");
        WorldId worldId = WorldId.create();
        BackupId backupId = BackupId.create();
        Path outside = temporaryDirectory.resolve("restore-junction-outside");
        Path junction = temporaryDirectory.resolve("restore-junction-target");
        Files.createDirectories(outside);

        try (GitBackupBackend backend = new GitBackupBackend(settings)) {
            assertEquals(DestinationStatus.SUCCESS, await(backend.createBackup(
                    capture(world, worldId, backupId, Instant.now()),
                    ProgressListener.NO_OP)).status());
            Process createJunction = new ProcessBuilder(
                            "cmd.exe",
                            "/d",
                            "/c",
                            "mklink",
                            "/J",
                            junction.toString(),
                            outside.toString())
                    .redirectErrorStream(true)
                    .start();
            String commandOutput = new String(createJunction.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(createJunction.waitFor(10, TimeUnit.SECONDS));
            assertEquals(0, createJunction.exitValue(), commandOutput);
            try {
                assertThrows(GitStorageException.class, () -> await(backend.restoreSnapshot(
                        worldId,
                        backupId,
                        junction)));
                try (Stream<Path> children = Files.list(outside)) {
                    assertTrue(children.findAny().isEmpty());
                }
            } finally {
                Files.deleteIfExists(junction);
            }
        }
    }

    @Test
    void restoresOldLfsPointersAfterConfiguredPatternsChange() throws Exception {
        Path world = temporaryDirectory.resolve("pattern-world");
        Files.createDirectories(world);
        byte[] original = bytes(12_345, 41);
        Files.write(world.resolve("region.legacy"), original);
        WorldId worldId = WorldId.create();
        BackupId backupId = BackupId.create();
        GitBackendSettings oldPatterns = settings(List.of("*.legacy"), Optional.empty());

        try (GitBackupBackend backend = new GitBackupBackend(oldPatterns)) {
            assertEquals(DestinationStatus.SUCCESS, await(backend.createBackup(
                    capture(world, worldId, backupId, Instant.now()),
                    ProgressListener.NO_OP)).status());
        }
        String pointer = nativeGit(
                "--git-dir=" + settings.repository(),
                "show",
                GitSnapshot.refName(worldId, backupId) + ":region.legacy");
        assertTrue(pointer.startsWith("version https://git-lfs.github.com/spec/v1"));

        GitBackendSettings newPatterns = settings(List.of("*.nbt"), Optional.empty());
        try (GitBackupBackend backend = new GitBackupBackend(newPatterns)) {
            assertTrue(await(backend.verifySnapshot(worldId, backupId)).valid());
            Path restored = temporaryDirectory.resolve("pattern-restore");
            await(backend.restoreSnapshot(worldId, backupId, restored));
            assertArrayEquals(original, Files.readAllBytes(restored.resolve("region.legacy")));
        }
        String attributes = Files.readString(settings.repository().resolve("info/attributes"));
        assertTrue(attributes.contains("*.legacy filter=lfs"));
        assertTrue(attributes.contains("*.nbt filter=lfs"));
    }

    @Test
    void refRepointingFailsIdentityVerificationAndRestore() throws Exception {
        Path world = temporaryDirectory.resolve("identity-world");
        Files.createDirectories(world);
        Files.writeString(world.resolve("level.dat"), "first");
        WorldId worldId = WorldId.create();
        BackupId firstId = BackupId.create();
        BackupId secondId = BackupId.create();

        try (GitBackupBackend backend = new GitBackupBackend(settings)) {
            await(backend.createBackup(capture(world, worldId, firstId, Instant.now().minusSeconds(2)),
                    ProgressListener.NO_OP));
            Files.writeString(world.resolve("level.dat"), "second");
            await(backend.createBackup(capture(world, worldId, secondId, Instant.now()),
                    ProgressListener.NO_OP));
            GitSnapshot second = await(backend.listSnapshots(Optional.of(worldId))).stream()
                    .filter(snapshot -> snapshot.backupId().equals(secondId))
                    .findFirst()
                    .orElseThrow();
            nativeGit(
                    "--git-dir=" + settings.repository(),
                    "update-ref",
                    GitSnapshot.refName(worldId, firstId),
                    second.commitId());

            assertFalse(await(backend.verifySnapshot(worldId, firstId)).valid());
            assertTrue(await(backend.verifySnapshot(worldId, secondId)).valid());
            assertThrows(GitStorageException.class, () -> await(backend.restoreSnapshot(
                    worldId,
                    firstId,
                    temporaryDirectory.resolve("repointed-restore"))));
        }
    }

    @Test
    void rejectsLinkedSourceBeforeGitAdd() throws Exception {
        Path world = temporaryDirectory.resolve("linked-world");
        Path outside = temporaryDirectory.resolve("outside-secret.txt");
        Files.createDirectories(world);
        Files.writeString(outside, "must not be captured");
        Path link = world.resolve("linked-secret.txt");
        try {
            Files.createSymbolicLink(link, outside);
        } catch (IOException | UnsupportedOperationException exception) {
            Assumptions.abort("Symbolic links are unavailable: " + exception.getMessage());
        }

        try (GitBackupBackend backend = new GitBackupBackend(settings)) {
            DestinationResult result = await(backend.createBackup(
                    captureWithoutScanning(world, WorldId.create(), BackupId.create(), Instant.now()),
                    ProgressListener.NO_OP));
            assertEquals(DestinationStatus.FAILED, result.status());
            assertTrue(await(backend.listSnapshots(Optional.empty())).isEmpty());
        }
    }

    @Test
    void rejectsWindowsJunctionBeforeGitAdd() throws Exception {
        Assumptions.assumeTrue(System.getProperty("os.name").startsWith("Windows"));
        Path world = temporaryDirectory.resolve("junction-world");
        Path outside = temporaryDirectory.resolve("junction-outside");
        Path junction = world.resolve("linked-directory");
        Files.createDirectories(world);
        Files.createDirectories(outside);
        Files.writeString(outside.resolve("secret.txt"), "must not be captured");
        Process createJunction = new ProcessBuilder(
                        "cmd.exe",
                        "/d",
                        "/c",
                        "mklink",
                        "/J",
                        junction.toString(),
                        outside.toString())
                .redirectErrorStream(true)
                .start();
        String commandOutput = new String(createJunction.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(createJunction.waitFor(10, TimeUnit.SECONDS));
        assertEquals(0, createJunction.exitValue(), commandOutput);
        try (GitBackupBackend backend = new GitBackupBackend(settings)) {
            DestinationResult result = await(backend.createBackup(
                    captureWithoutScanning(world, WorldId.create(), BackupId.create(), Instant.now()),
                    ProgressListener.NO_OP));
            assertEquals(DestinationStatus.FAILED, result.status());
            assertTrue(await(backend.listSnapshots(Optional.empty())).isEmpty());
        } finally {
            Files.deleteIfExists(junction);
        }
    }

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

    private GitBackendSettings settings(Optional<String> remoteUrl) {
        return settings(GitBackendSettings.DEFAULT_LFS_PATTERNS, remoteUrl);
    }

    private GitBackendSettings settings(List<String> lfsPatterns, Optional<String> remoteUrl) {
        return new GitBackendSettings(
                true,
                temporaryDirectory.resolve("synced folder Ω").resolve("worldarchive.git"),
                "git",
                "origin",
                remoteUrl,
                lfsPatterns,
                TEST_TIMEOUT,
                4 * 1_024 * 1_024);
    }

    private static BackupCapture capture(Path world, WorldId worldId, BackupId backupId, Instant timestamp)
            throws IOException, GitStorageException {
        List<GitInventoryEntry> entries = new ArrayList<>();
        try (Stream<Path> paths = Files.walk(world)) {
            for (Path path : paths.filter(Files::isRegularFile).toList()) {
                String relative = relative(world, path);
                if (relative.equals("session.lock") || relative.startsWith(".worldarchive/")) {
                    continue;
                }
                byte[] contents = Files.readAllBytes(path);
                entries.add(new GitInventoryEntry(
                        relative,
                        contents.length,
                        java.util.HexFormat.of().formatHex(GitInventory.sha256().digest(contents))));
            }
        }
        GitInventory inventory = GitInventory.create(entries);
        BackupManifest manifest = BackupManifest.create(
                backupId,
                worldId,
                "Integration 世界",
                Optional.empty(),
                timestamp,
                BackupTrigger.MANUAL,
                inventory.fileCount(),
                inventory.byteCount(),
                inventory.fileCount(),
                inventory.contentSha256(),
                inventory.inventorySha256());
        return new BackupCapture(world, manifest);
    }

    private static BackupCapture captureWithoutScanning(
            Path world,
            WorldId worldId,
            BackupId backupId,
            Instant timestamp) {
        return new BackupCapture(world, BackupManifest.create(
                backupId,
                worldId,
                "Unsafe source test",
                timestamp,
                BackupTrigger.MANUAL,
                0,
                0,
                "0".repeat(64)));
    }

    private static Map<String, byte[]> worldFiles(Path world) throws IOException {
        Map<String, byte[]> files = new HashMap<>();
        try (Stream<Path> paths = Files.walk(world)) {
            for (Path path : paths.filter(Files::isRegularFile).toList()) {
                String relative = relative(world, path);
                if (!relative.equals("session.lock") && !relative.startsWith(".worldarchive/")) {
                    files.put(relative, Files.readAllBytes(path));
                }
            }
        }
        return files;
    }

    private static void assertWorldEquals(Map<String, byte[]> expected, Path actualRoot) throws IOException {
        Map<String, byte[]> actual = worldFiles(actualRoot);
        assertEquals(expected.keySet(), actual.keySet());
        for (Map.Entry<String, byte[]> entry : expected.entrySet()) {
            assertArrayEquals(entry.getValue(), actual.get(entry.getKey()), entry.getKey());
        }
    }

    private static String relative(Path root, Path path) {
        return root.relativize(path).toString().replace('\\', '/');
    }

    private static byte[] bytes(int length, int seed) {
        byte[] value = new byte[length];
        for (int index = 0; index < value.length; index++) {
            value[index] = (byte) (index * 31 + seed);
        }
        return value;
    }

    private Map<String, String> worldTreeObjects(String refName) throws Exception {
        Map<String, String> objects = new HashMap<>();
        String tree = nativeGit(
                "--git-dir=" + settings.repository(),
                "ls-tree",
                "-r",
                refName);
        for (String line : tree.lines().toList()) {
            int tab = line.indexOf('\t');
            String[] metadata = line.substring(0, tab).split(" ");
            String path = line.substring(tab + 1);
            if (!path.equals(GitBackupBackend.MANIFEST_PATH)) {
                objects.put(path, metadata[2]);
            }
        }
        return Map.copyOf(objects);
    }

    private long lfsObjectCount() throws IOException {
        Path objects = settings.repository().resolve("lfs/objects");
        if (!Files.isDirectory(objects)) {
            return 0;
        }
        try (Stream<Path> paths = Files.walk(objects)) {
            return paths.filter(Files::isRegularFile).count();
        }
    }

    private static void deleteTree(Path root) throws IOException, InterruptedException {
        if (!Files.exists(root)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                deleteWithSharingRetry(path);
            }
        }
    }

    private static void deleteWithSharingRetry(Path path) throws IOException, InterruptedException {
        long deadline = System.nanoTime() + WINDOWS_DELETE_RETRY_TIMEOUT.toNanos();
        while (true) {
            try {
                Files.deleteIfExists(path);
                return;
            } catch (AccessDeniedException exception) {
                if (!System.getProperty("os.name").startsWith("Windows")) {
                    throw exception;
                }
                try {
                    if (clearDosReadOnly(path)) {
                        continue;
                    }
                } catch (AccessDeniedException ignored) {
                    // A sharing violation can temporarily prevent reading the DOS attributes too.
                }
                if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)
                        || System.nanoTime() >= deadline) {
                    throw exception;
                }
                TimeUnit.MILLISECONDS.sleep(10L);
            }
        }
    }

    private static boolean clearDosReadOnly(Path path) throws IOException {
        DosFileAttributeView attributes = Files.getFileAttributeView(
                path,
                DosFileAttributeView.class,
                LinkOption.NOFOLLOW_LINKS);
        if (attributes == null || !attributes.readAttributes().isReadOnly()) {
            return false;
        }
        attributes.setReadOnly(false);
        return true;
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

    private Optional<String> remoteRef(Path remote, String refName) throws Exception {
        String output = nativeGit(
                "--git-dir=" + remote,
                "for-each-ref",
                "--format=%(objectname)",
                refName).trim();
        return output.isEmpty() ? Optional.empty() : Optional.of(output);
    }

    private static <T> T await(java.util.concurrent.CompletionStage<T> stage) throws Exception {
        try {
            return stage.toCompletableFuture().get(60, TimeUnit.SECONDS);
        } catch (java.util.concurrent.ExecutionException exception) {
            if (exception.getCause() instanceof Exception cause) {
                throw cause;
            }
            throw exception;
        }
    }
}
