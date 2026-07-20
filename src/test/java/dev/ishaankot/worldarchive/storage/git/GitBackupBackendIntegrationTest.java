package dev.ishaankot.worldarchive.storage.git;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.ishaankot.worldarchive.core.BackupCapture;
import dev.ishaankot.worldarchive.core.ProgressListener;
import dev.ishaankot.worldarchive.model.BackupId;
import dev.ishaankot.worldarchive.model.DestinationResult;
import dev.ishaankot.worldarchive.model.DestinationStatus;
import dev.ishaankot.worldarchive.model.SyncStatus;
import dev.ishaankot.worldarchive.model.WorldId;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

class GitBackupBackendIntegrationTest extends GitBackupBackendIntegrationTestSupport {

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

}
