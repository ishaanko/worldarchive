package dev.ishaanko.worldarchive.storage.git;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.ishaanko.worldarchive.config.FolderOrigin;
import dev.ishaanko.worldarchive.core.BackupCapture;
import dev.ishaanko.worldarchive.core.TestCaptures;
import dev.ishaanko.worldarchive.core.WorldInventory;
import dev.ishaanko.worldarchive.model.BackupId;
import dev.ishaanko.worldarchive.model.BackupManifest;
import dev.ishaanko.worldarchive.model.BackupTrigger;
import dev.ishaanko.worldarchive.model.DestinationResult;
import dev.ishaanko.worldarchive.model.DestinationStatus;
import dev.ishaanko.worldarchive.model.ProgressListener;
import dev.ishaanko.worldarchive.model.SyncStatus;
import dev.ishaanko.worldarchive.model.WorldId;
import dev.ishaanko.worldarchive.support.Digests;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Git storage behavior that the end-to-end suite cannot reach through the engine. */
class WorldGitSnapshotStoreTest {
    private static final Instant START = Instant.parse("2026-09-01T12:00:00Z");

    @TempDir
    Path temporaryDirectory;

    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();


    @BeforeEach
    void requireGitAndLfs() throws Exception {
        GitToolHealth tools = new GitToolProbe(settings("storage", GitBackendSettings.DEFAULT_LFS_PATTERNS),
                new SystemGitCommandRunner()).probe();
        Assumptions.assumeTrue(tools.available(), tools.summary());
    }

    @AfterEach
    void stopExecutor() {
        executor.shutdownNow();
    }

    @Test
    void gitThatCannotStartFailsTheBackupAndCreatesNothing() throws Exception {
        GitBackendSettings missing = new GitBackendSettings(true, temporaryDirectory.resolve("missing"),
                FolderOrigin.DEFAULT, "worldarchive-missing-git", "origin", Optional.empty(),
                GitBackendSettings.DEFAULT_LFS_PATTERNS, Duration.ofSeconds(10), 1_024 * 1_024);
        WorldGitSnapshotStore store =
                new WorldGitSnapshotStore(missing, Map.of(), new SystemGitCommandRunner(), executor);

        DestinationResult result = store.createBackup(capture(world("Missing"), WorldId.create(), START), ProgressListener.NO_OP);

        assertEquals(DestinationStatus.FAILED, result.status());
        assertTrue(result.message().orElseThrow().contains("Install Git"), result.message().orElseThrow());
        assertFalse(Files.exists(temporaryDirectory.resolve("missing")));
    }

    @Test
    void aNestedGitRepositoryFailsWithAMessageNamingItsFolder() throws Exception {
        Path world = world("Datapacks");
        write(world.resolve("datapacks/mypack/.git/HEAD"), "ref: refs/heads/main\n".getBytes(StandardCharsets.UTF_8));

        DestinationResult result = store(Map.of()).createBackup(capture(world, WorldId.create(), START), ProgressListener.NO_OP);

        assertEquals(DestinationStatus.FAILED, result.status());
        assertTrue(result.message().orElseThrow().contains("datapacks/mypack"), result.message().orElseThrow());
        assertTrue(result.message().orElseThrow().contains("Remove its .git folder"));
    }

    @Test
    void aWorldThatChangedAfterItsCaptureIsNeverPublished() throws Exception {
        Path world = world("Changed");
        WorldId worldId = WorldId.create();
        WorldGitSnapshotStore store = store(Map.of());
        BackupCapture capture = capture(world, worldId, START);
        write(world.resolve("level.dat"), bytes(4_096, 99));

        DestinationResult result = store.createBackup(capture, ProgressListener.NO_OP);

        assertEquals(DestinationStatus.FAILED, result.status());
        assertTrue(result.message().orElseThrow().contains("level.dat"), result.message().orElseThrow());
        assertTrue(await(store.listSnapshots(Optional.of(worldId))).isEmpty());
    }

    @Test
    void aLinkInTheCaptureFolderNeverBecomesPartOfASnapshot() throws Exception {
        Assumptions.assumeFalse(isWindows(), "creating a symbolic link needs extra rights on Windows");
        Path world = world("Linked");
        WorldId worldId = WorldId.create();
        BackupCapture capture = capture(world, worldId, START);
        Files.createSymbolicLink(world.resolve("linked.dat"), world.resolve("level.dat"));
        WorldGitSnapshotStore store = store(Map.of());

        assertEquals(DestinationStatus.SUCCESS, store.createBackup(capture, ProgressListener.NO_OP).status());

        Path staging = Files.createDirectories(temporaryDirectory.resolve("linked-restore"));
        await(store.restoreSnapshot(worldId, capture.manifest().backupId(), capture.manifest(), staging));
        assertEquals(capture.inventory(), TestCaptures.inventoryOf(staging));
        assertFalse(Files.exists(staging.resolve("linked.dat"), LinkOption.NOFOLLOW_LINKS));
    }

    @Test
    void anUnchangedWorldReusesEveryObjectAndEachWorldKeepsItsOwnRepository() throws Exception {
        Path world = world("Unchanged");
        WorldId worldId = WorldId.create();
        WorldId otherWorldId = WorldId.create();
        WorldGitSnapshotStore store = store(Map.of());
        BackupId first = backup(store, world, worldId, START);
        long objects = lfsObjects(store, worldId);

        BackupId second = backup(store, world, worldId, START.plusSeconds(60));
        backup(store, world("Other"), otherWorldId, START.plusSeconds(120));

        assertEquals(objects, lfsObjects(store, worldId));
        assertEquals(worldFiles(store, worldId, first), worldFiles(store, worldId, second));
        assertNotEquals(store.repositoryFor(worldId), store.repositoryFor(otherWorldId));
        assertEquals(2, await(store.listSnapshots(Optional.of(worldId))).size());
        assertEquals(1, await(store.listSnapshots(Optional.of(otherWorldId))).size());
        assertEquals(3, await(store.listSnapshots(Optional.empty())).size());
    }

    @Test
    void verifyCatchesADamagedOrMissingLfsObjectAndARepointedRef() throws Exception {
        Path world = world("Verified");
        WorldId worldId = WorldId.create();
        WorldGitSnapshotStore store = store(Map.of());
        BackupId first = backup(store, world, worldId, START);
        write(world.resolve("level.dat"), bytes(4_096, 7));
        BackupId second = backup(store, world, worldId, START.plusSeconds(60));
        Path repository = store.repositoryFor(worldId);
        String firstCommit = commit(store, worldId, first);
        git(repository, "update-ref", GitSnapshot.refName(worldId, first), commit(store, worldId, second));
        assertFalse(await(store.verifyCurrentSnapshot(worldId, first)).valid(), "a ref that shows another backup");
        git(repository, "update-ref", GitSnapshot.refName(worldId, first), firstCommit);
        assertTrue(await(store.verifyCurrentSnapshot(worldId, first)).valid());

        flipOneByte(lfsObject(repository, Files.readAllBytes(world.resolve("level.dat"))));
        Files.delete(lfsObject(repository, bytes(4_096, 1)));

        GitVerification damaged = await(store.verifyCurrentSnapshot(worldId, second));
        assertFalse(damaged.valid());
        assertTrue(damaged.message().contains("missing or damaged"), damaged.message());
        assertFalse(await(store.verifyCurrentSnapshot(worldId, first)).valid());
    }

    @Test
    void aSnapshotWrittenWithOtherLfsPatternsStillRestores() throws Exception {
        Path world = world("Patterns");
        WorldId worldId = WorldId.create();
        WorldGitSnapshotStore before = store("storage", List.of("*.mca", "*.dat"), Map.of());
        BackupCapture older = capture(world, worldId, START);
        assertEquals(DestinationStatus.SUCCESS, before.createBackup(older, ProgressListener.NO_OP).status());
        WorldGitSnapshotStore after = store("storage", List.of("*.mca"), Map.of());
        BackupCapture newer = capture(world, worldId, START.plusSeconds(60));

        assertEquals(DestinationStatus.SUCCESS, after.createBackup(newer, ProgressListener.NO_OP).status());

        assertRestores(after, older, world);
        assertRestores(after, newer, world);
        assertArrayEquals(Files.readAllBytes(world.resolve("level.dat")), git(after.repositoryFor(worldId), "cat-file",
                "blob", GitSnapshot.refName(worldId, newer.manifest().backupId()) + ":level.dat")
                .getBytes(StandardCharsets.ISO_8859_1));
    }

    @Test
    void aRestoreNeedsAnEmptyFolder() throws Exception {
        Path world = world("Occupied");
        WorldId worldId = WorldId.create();
        WorldGitSnapshotStore store = store(Map.of());
        BackupCapture capture = capture(world, worldId, START);
        store.createBackup(capture, ProgressListener.NO_OP);
        Path staging = Files.createDirectories(temporaryDirectory.resolve("occupied"));
        write(staging.resolve("keep.txt"), "players' file".getBytes(StandardCharsets.UTF_8));

        ExecutionException failure = assertThrows(ExecutionException.class, () -> await(
                store.restoreSnapshot(worldId, capture.manifest().backupId(), capture.manifest(), staging)));

        assertInstanceOf(GitStorageException.class, failure.getCause());
        assertEquals(List.of(staging.resolve("keep.txt")), list(staging));
    }

    @Test
    void anUploadThatStallsIsPendingAndALaterSyncFinishesIt() throws Exception {
        Path remote = bareRepository("stalling.git");
        Path hook = remote.resolve("hooks/pre-receive");
        Files.writeString(hook, "#!/bin/sh\nsleep 30\n");
        assertTrue(hook.toFile().setExecutable(true));
        WorldId worldId = WorldId.create();
        GitBackendSettings impatient = new GitBackendSettings(true, temporaryDirectory.resolve("impatient"),
                FolderOrigin.DEFAULT, "git", "origin", Optional.empty(), GitBackendSettings.DEFAULT_LFS_PATTERNS,
                Duration.ofSeconds(2), 1_024 * 1_024);
        WorldGitSnapshotStore store = new WorldGitSnapshotStore(
                impatient, Map.of(worldId, remote.toString()), new SystemGitCommandRunner(), executor);
        BackupCapture capture = capture(world("Stalled"), worldId, START);

        DestinationResult pending = store.createBackup(capture, ProgressListener.NO_OP);
        Files.delete(hook);
        DestinationResult synced = await(store.syncSnapshot(worldId, capture.manifest().backupId()));

        assertEquals(DestinationStatus.PENDING_SYNC, pending.status(), pending.message().orElse(""));
        assertTrue(pending.message().orElseThrow().contains("no progress"), pending.message().orElseThrow());
        assertEquals(SyncStatus.SYNCED, synced.syncStatus());
        assertEquals(Set.of(capture.manifest().backupId()), await(store.remoteSnapshotCommits(worldId)).keySet());
    }

    @Test
    void syncingAnOlderBackupLeavesMainOnTheNewest() throws Exception {
        Path remote = temporaryDirectory.resolve("later.git");
        WorldId worldId = WorldId.create();
        Path world = world("Main");
        WorldGitSnapshotStore store = store(Map.of(worldId, remote.toString()));
        BackupCapture older = capture(world, worldId, START);
        assertEquals(DestinationStatus.PENDING_SYNC, store.createBackup(older, ProgressListener.NO_OP).status());
        bareRepository("later.git");
        write(world.resolve("level.dat"), bytes(4_096, 12));
        BackupId newer = backup(store, world, worldId, START.plusSeconds(60));
        String newerCommit = commit(store, worldId, newer);
        assertEquals(newerCommit, git(remote, "rev-parse", "refs/heads/main").trim());

        assertEquals(SyncStatus.SYNCED, await(store.syncSnapshot(worldId, older.manifest().backupId())).syncStatus());

        assertEquals(newerCommit, git(remote, "rev-parse", "refs/heads/main").trim());
        assertEquals(2, await(store.remoteSnapshotCommits(worldId)).size());
    }

    @Test
    void deleteReportsWhatItFoundAndRemovesBackupsFromTheRemoteInOneOperation() throws Exception {
        Path remote = bareRepository("remote.git");
        WorldId local = WorldId.create();
        WorldId synced = WorldId.create();
        WorldGitSnapshotStore store = store(Map.of(synced, remote.toString()));
        Path world = world("Synced");
        BackupId first = backup(store, world, synced, START);
        write(world.resolve("level.dat"), bytes(4_096, 13));
        BackupId second = backup(store, world, synced, START.plusSeconds(60));
        BackupId unknown = BackupId.create();
        assertEquals(Map.of(first, commit(store, synced, first), second, commit(store, synced, second)),
                await(store.remoteSnapshotCommits(synced)));

        backup(store, world("Local"), local, START);
        assertEquals(GitDeletion.Outcome.REMOTE_NOT_CONFIGURED,
                await(store.deleteSnapshots(local, Set.of(unknown))).get(unknown).outcome());
        WorldId withoutRepository = WorldId.create();
        assertEquals(GitDeletion.Outcome.REMOTE_NOT_CONFIGURED,
                await(store.deleteSnapshots(withoutRepository, Set.of(unknown))).get(unknown).outcome());
        assertFalse(Files.exists(store.repositoryFor(withoutRepository)), "a delete creates no repository");
        assertEquals(GitDeletion.Outcome.NOT_FOUND, await(store.deleteSnapshots(synced, Set.of(unknown))).get(unknown).outcome());
        Map<BackupId, GitDeletion> deleted = await(store.deleteSnapshots(synced, Set.of(first, second)));

        assertEquals(GitDeletion.Outcome.DELETED, deleted.get(first).outcome());
        assertEquals(GitDeletion.Outcome.DELETED, deleted.get(second).outcome());
        assertTrue(await(store.remoteSnapshotCommits(synced)).isEmpty());
        assertTrue(await(store.listSnapshots(Optional.of(synced))).isEmpty());
        assertEquals("", git(remote, "ls-tree", "refs/heads/main").trim(), "main shows an empty placeholder");
        assertEquals(0, lfsObjects(store, synced), "the deleted backups' space is freed");
    }

    @Test
    void aRemoteCopyThatDiffersIsNeitherDeletedNorRestoredOrKept() throws Exception {
        Path remote = bareRepository("diverged.git");
        WorldId worldId = WorldId.create();
        WorldGitSnapshotStore store = store(Map.of(worldId, remote.toString()));
        Path world = world("Diverged");
        BackupCapture first = capture(world, worldId, START);
        store.createBackup(first, ProgressListener.NO_OP);
        write(world.resolve("level.dat"), bytes(4_096, 14));
        BackupId second = backup(store, world, worldId, START.plusSeconds(60));
        BackupId firstId = first.manifest().backupId();
        String firstBranch = remoteBranch(remote, firstId);
        git(remote, "update-ref", firstBranch, commit(store, worldId, second));

        GitDeletion refused = await(store.deleteSnapshots(worldId, Set.of(firstId))).get(firstId);
        assertTrue(await(store.deleteLocalSnapshot(worldId, firstId)));
        Path staging = Files.createDirectories(temporaryDirectory.resolve("diverged-restore"));

        assertEquals(GitDeletion.Outcome.FAILED, refused.outcome());
        assertTrue(refused.message().contains("differs"), refused.message());
        assertThrows(ExecutionException.class, () -> await(store.restoreSnapshot(worldId, firstId, first.manifest(), staging)));
        assertEquals(List.of(second), await(store.listSnapshots(Optional.of(worldId))).stream()
                .map(GitSnapshot::backupId).toList());
    }

    @Test
    void anImportedBackupWithoutItsLfsObjectsFailsAloneAndHydrationDownloadsMissingObjects() throws Exception {
        Path remote = bareRepository("source.git");
        WorldId worldId = WorldId.create();
        WorldGitSnapshotStore source = store("source", GitBackendSettings.DEFAULT_LFS_PATTERNS, Map.of(worldId, remote.toString()));
        Path world = world("Imported");
        BackupId broken = backup(source, world, worldId, START);
        Files.delete(lfsObject(remote, Files.readAllBytes(world.resolve("level.dat"))));
        write(world.resolve("level.dat"), bytes(4_096, 15));
        BackupId intact = backup(source, world, worldId, START.plusSeconds(60));

        WorldGitSnapshotStore target = store("target", GitBackendSettings.DEFAULT_LFS_PATTERNS, Map.of());
        try (GitPreparedImport preview = await(target.prepareImport(remote.toString()))) {
            assertEquals(Map.of(broken, GitImportInstallStatus.FAILED, intact, GitImportInstallStatus.ADDED),
                    await(target.installImport(preview, preview.candidates())));
        }
        BackupManifest manifest = await(target.readManifests(worldId, await(target.listSnapshots(Optional.of(worldId)))))
                .get(intact);
        try (Stream<Path> objects = Files.walk(target.repositoryFor(worldId).resolve("lfs/objects"))) {
            for (Path object : objects.filter(Files::isRegularFile).toList()) {
                Files.delete(object);
            }
        }
        GitVerification hydrated = await(target.hydrateExternalSnapshot(
                worldId, intact, manifest, commit(target, worldId, intact), remote.toString()));
        assertTrue(hydrated.valid(), hydrated.message());
    }

    @Test
    void aRefUpdateThatFailsAfterItAppliedStillPublishesTheSnapshot() throws Exception {
        GitCommandRunner lostAnswer = refUpdate(true, "fatal: the connection was lost");
        GitCommandRunner neverApplied = refUpdate(false, "fatal: unable to lock the ref");
        WorldId published = WorldId.create();
        WorldId refused = WorldId.create();

        DestinationResult applied = new WorldGitSnapshotStore(
                        settings("lost", GitBackendSettings.DEFAULT_LFS_PATTERNS), Map.of(), lostAnswer, executor)
                .createBackup(capture(world("Lost"), published, START), ProgressListener.NO_OP);
        DestinationResult failed = new WorldGitSnapshotStore(
                        settings("never", GitBackendSettings.DEFAULT_LFS_PATTERNS), Map.of(), neverApplied, executor)
                .createBackup(capture(world("Never"), refused, START), ProgressListener.NO_OP);

        assertEquals(DestinationStatus.SUCCESS, applied.status(), applied.message().orElse(""));
        assertEquals(DestinationStatus.FAILED, failed.status());
        assertTrue(failed.message().orElseThrow().contains("unable to lock"), failed.message().orElseThrow());
    }

    /** A cancel that arrives right after Git published the snapshot reports it as kept here, waiting for its upload. */
    @Test
    void aCancelRightAfterPublishStillReportsThePublishedSnapshot() throws Exception {
        Path remote = bareRepository("cancel.git");
        WorldId worldId = WorldId.create();
        AtomicBoolean cancelled = new AtomicBoolean();
        SystemGitCommandRunner system = new SystemGitCommandRunner();
        GitCommandRunner cancelAfterPublish = new GitCommandRunner() {
            @Override
            public GitCommandResult run(GitCommand command) throws IOException, InterruptedException {
                GitCommandResult result = system.run(command);
                if (command.arguments().contains("update-ref") && command.arguments().contains("--stdin")
                        && cancelled.compareAndSet(false, true)) {
                    Thread.currentThread().interrupt();
                }
                return result;
            }

            @Override
            public GitCommandResult stream(GitCommand command, OutputReader reader)
                    throws IOException, InterruptedException, GitStorageException {
                return system.stream(command, reader);
            }
        };
        WorldGitSnapshotStore store = new WorldGitSnapshotStore(
                settings("cancel", GitBackendSettings.DEFAULT_LFS_PATTERNS),
                Map.of(worldId, remote.toString()),
                cancelAfterPublish,
                executor);

        DestinationResult result = store.createBackup(capture(world("Cancel"), worldId, START), ProgressListener.NO_OP);
        Thread.interrupted();

        assertTrue(cancelled.get());
        assertEquals(1, await(store.listSnapshots(Optional.of(worldId))).size());
        assertEquals(DestinationStatus.PENDING_SYNC, result.status(), result.toString());
    }

    /**
     * Git LFS writes what it downloads without flushing it. Every object a download added, or
     * wrote again with another size, is flushed afterwards; objects that were there before are not.
     */
    @Test
    void theLfsObjectsADownloadWroteAreFlushed() throws Exception {
        GitBackendSettings settings = settings("flush", GitBackendSettings.DEFAULT_LFS_PATTERNS)
                .withRepository(temporaryDirectory.resolve("flush/world.git"), Optional.empty());
        GitLfsObjects lfs = new GitLfsObjects(new GitRepository(settings, new SystemGitCommandRunner()));
        lfsObject(lfs, bytes(1_024, 3));
        Path resized = lfsObject(lfs, bytes(2_048, 4));
        Map<Path, Long> before = lfs.sizes();
        Path added = lfsObject(lfs, bytes(512, 5));
        Files.write(resized, bytes(1_000, 6));

        assertEquals(Set.of(added, resized), lfs.flushAddedSince(before));
    }

    /** The system runner, except that every ref transaction reports a failure, after running or instead of it. */
    private static GitCommandRunner refUpdate(boolean runIt, String error) {
        SystemGitCommandRunner system = new SystemGitCommandRunner();
        return new GitCommandRunner() {
            @Override
            public GitCommandResult run(GitCommand command) throws IOException, InterruptedException {
                if (!command.arguments().contains("update-ref") || !command.arguments().contains("--stdin")) {
                    return system.run(command);
                }
                if (runIt) {
                    system.run(command);
                }
                return new GitCommandResult(128, "", error, false, false);
            }

            @Override
            public GitCommandResult stream(GitCommand command, OutputReader reader)
                    throws IOException, InterruptedException, GitStorageException {
                return system.stream(command, reader);
            }
        };
    }

    private void assertRestores(WorldGitSnapshotStore store, BackupCapture capture, Path world) throws Exception {
        Path staging = Files.createDirectories(temporaryDirectory.resolve("restore-" + capture.manifest().backupId()));
        await(store.restoreSnapshot(capture.manifest().worldId(), capture.manifest().backupId(), capture.manifest(), staging));
        assertEquals(TestCaptures.inventoryOf(world), TestCaptures.inventoryOf(staging));
    }

    private BackupId backup(WorldGitSnapshotStore store, Path world, WorldId worldId, Instant time) throws Exception {
        BackupCapture capture = capture(world, worldId, time);
        DestinationResult result = store.createBackup(capture, ProgressListener.NO_OP);
        assertTrue(result.status() == DestinationStatus.SUCCESS, result.message().orElse(""));
        return capture.manifest().backupId();
    }

    private static BackupCapture capture(Path world, WorldId worldId, Instant time) throws IOException {
        WorldInventory inventory = TestCaptures.inventoryOf(world);
        BackupManifest manifest = BackupManifest.create(BackupId.create(), worldId, world.getFileName().toString(),
                Optional.empty(), time, BackupTrigger.MANUAL, inventory.fileCount(), inventory.byteCount(),
                inventory.fileCount(), inventory.contentSha256(), inventory.inventorySha256(), Optional.empty());
        return new BackupCapture(world, manifest, inventory);
    }

    /** A small world: LFS region and player files, a plain JSON file, and an empty file. */
    private Path world(String name) throws IOException {
        Path world = temporaryDirectory.resolve("worlds").resolve(name);
        write(world.resolve("level.dat"), bytes(4_096, 1));
        write(world.resolve("region/r.0.0.mca"), bytes(64 * 1_024, 2));
        write(world.resolve("data/raids.json"), "{\"raids\":[]}".getBytes(StandardCharsets.UTF_8));
        write(world.resolve("empty.txt"), new byte[0]);
        return world;
    }

    private WorldGitSnapshotStore store(Map<WorldId, String> remotes) {
        return store("storage", GitBackendSettings.DEFAULT_LFS_PATTERNS, remotes);
    }

    private WorldGitSnapshotStore store(String folder, List<String> lfsPatterns, Map<WorldId, String> remotes) {
        return new WorldGitSnapshotStore(
                settings(folder, lfsPatterns), remotes, new SystemGitCommandRunner(), executor);
    }

    private GitBackendSettings settings(String folder, List<String> lfsPatterns) {
        return new GitBackendSettings(true, temporaryDirectory.resolve(folder).resolve("git Ω"), FolderOrigin.DEFAULT,
                "git", "origin", Optional.empty(), lfsPatterns, Duration.ofSeconds(30), 4 * 1_024 * 1_024);
    }

    private String commit(WorldGitSnapshotStore store, WorldId worldId, BackupId backupId) throws Exception {
        return git(store.repositoryFor(worldId), "rev-parse", GitSnapshot.refName(worldId, backupId)).trim();
    }

    /** The snapshot's files and their blobs, without its own manifest. */
    private List<String> worldFiles(WorldGitSnapshotStore store, WorldId worldId, BackupId backupId) throws Exception {
        return git(store.repositoryFor(worldId), "ls-tree", "-r", GitSnapshot.refName(worldId, backupId)).lines()
                .filter(line -> !line.endsWith(GitTreeValidator.MANIFEST_PATH))
                .toList();
    }

    private String remoteBranch(Path remote, BackupId backupId) throws Exception {
        return git(remote, "for-each-ref", "--format=%(refname)", "refs/heads/backups/").lines()
                .filter(line -> line.endsWith(backupId.toString()))
                .findFirst()
                .orElseThrow();
    }

    private long lfsObjects(WorldGitSnapshotStore store, WorldId worldId) throws IOException {
        Path objects = store.repositoryFor(worldId).resolve("lfs/objects");
        if (!Files.isDirectory(objects)) {
            return 0;
        }
        try (Stream<Path> files = Files.walk(objects)) {
            return files.filter(Files::isRegularFile).count();
        }
    }

    /** Stores the bytes as the LFS object named by their SHA-256. */
    private static Path lfsObject(GitLfsObjects lfs, byte[] contents) throws IOException {
        Path object = lfs.path(Digests.hex(Digests.sha256().digest(contents)));
        Files.createDirectories(object.getParent());
        return Files.write(object, contents);
    }

    private static Path lfsObject(Path repository, byte[] contents) {
        String sha256 = Digests.hex(Digests.sha256().digest(contents));
        return repository.resolve("lfs/objects").resolve(sha256.substring(0, 2)).resolve(sha256.substring(2, 4)).resolve(sha256);
    }

    private Path bareRepository(String name) throws Exception {
        Path repository = temporaryDirectory.resolve(name);
        Files.createDirectories(repository);
        git(repository, "init", "--bare", "--quiet");
        return repository;
    }

    private String git(Path repository, String... arguments) throws Exception {
        List<String> command = new ArrayList<>(List.of("git", "--git-dir=" + repository));
        command.addAll(List.of(arguments));
        Process process = new ProcessBuilder(command).directory(temporaryDirectory.toFile()).start();
        byte[] output = process.getInputStream().readAllBytes();
        String error = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(process.waitFor(30, TimeUnit.SECONDS));
        assertEquals(0, process.exitValue(), error);
        return new String(output, StandardCharsets.ISO_8859_1);
    }

    private static void write(Path file, byte[] contents) throws IOException {
        Files.createDirectories(file.getParent());
        Files.write(file, contents);
    }

    private static void flipOneByte(Path file) throws IOException {
        try (RandomAccessFile access = new RandomAccessFile(file.toFile(), "rw")) {
            int value = access.read();
            access.seek(0);
            access.write(value ^ 0xFF);
        }
    }

    private static List<Path> list(Path folder) throws IOException {
        try (Stream<Path> children = Files.list(folder)) {
            return children.toList();
        }
    }

    private static byte[] bytes(int length, long seed) {
        byte[] value = new byte[length];
        new Random(seed).nextBytes(value);
        return value;
    }

    private static boolean isWindows() {
        return System.getProperty("os.name").startsWith("Windows");
    }

    private static <T> T await(CompletionStage<T> stage) throws Exception {
        return stage.toCompletableFuture().get(60, TimeUnit.SECONDS);
    }
}
