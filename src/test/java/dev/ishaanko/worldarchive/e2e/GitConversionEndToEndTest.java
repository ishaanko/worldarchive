package dev.ishaanko.worldarchive.e2e;

import static dev.ishaanko.worldarchive.e2e.Engine.await;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.ishaanko.worldarchive.config.GitDestinationConfig;
import dev.ishaanko.worldarchive.config.RemoteUpload;
import dev.ishaanko.worldarchive.config.StoragePolicy;
import dev.ishaanko.worldarchive.config.WorldArchiveConfig;
import dev.ishaanko.worldarchive.core.RestoreBackupRequest;
import dev.ishaanko.worldarchive.core.SourceCaptureObserver;
import dev.ishaanko.worldarchive.model.BackupId;
import dev.ishaanko.worldarchive.model.BackupRecord;
import dev.ishaanko.worldarchive.model.BackupResult;
import dev.ishaanko.worldarchive.model.BackupStatus;
import dev.ishaanko.worldarchive.model.BackupTrigger;
import dev.ishaanko.worldarchive.model.DestinationResult;
import dev.ishaanko.worldarchive.model.DestinationType;
import dev.ishaanko.worldarchive.model.ProgressListener;
import dev.ishaanko.worldarchive.model.SyncStatus;
import dev.ishaanko.worldarchive.storage.git.GitConversion;
import dev.ishaanko.worldarchive.storage.git.GitSnapshot;
import dev.ishaanko.worldarchive.storage.git.RegionFileBuilder;
import dev.ishaanko.worldarchive.support.Digests;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Converting Git backups from the old format, one LFS object per region file, to region buckets:
 * every backup still restores chunk for chunk, the old objects are freed, a damaged or cancelled
 * backup stays in the old format and restorable, and a remote that holds the old form is replaced
 * by Sync only when it holds the same backup.
 */
class GitConversionEndToEndTest {
    /** Real region files; the test world's own region files are noise, which stays whole. */
    private static final List<String> REGIONS = List.of("region/r.1.0.mca", "region/r.-1.-1.mca", "DIM-1/region/r.0.0.mca");

    private static final String CHANGING = REGIONS.getFirst();

    @TempDir
    Path root;

    private Engine engine;

    /** The world's files when each backup was made, which the old format stored byte for byte. */
    private final Map<BackupId, Map<String, byte[]>> captured = new LinkedHashMap<>();

    @AfterEach
    void closeEngine() throws Exception {
        if (engine != null) {
            engine.close();
        }
    }

    @Test
    void everyOldBackupIsConvertedRestoresItsChunksAndFreesTheWholeFiles() throws Exception {
        engine = start(Engine.onlyDestination(DestinationType.GIT));
        TestWorld world = regionWorld("Converted");
        List<BackupId> backups = oldBackups(world, 2);
        List<BackupRecord> records = engine.records(world.id());
        long before = repositoryBytes(world);
        Set<String> wholeFiles = wholeFileObjects(backups);
        assertEquals(2, await(engine.git.oldFormatSnapshotCount(world.id())));

        GitConversion conversion = await(engine.recovery.convertGitBackups(world.id(), ProgressListener.NO_OP));

        assertEquals(backups, conversion.converted());
        assertEquals(List.of(), conversion.failures());
        assertEquals(records, engine.records(world.id()), "a world without a remote keeps its records as they were");
        for (BackupId backupId : backups) {
            assertEquals(2, storageFormat(world, backupId));
            assertRestoresChunks(captured.get(backupId), restore(backupId));
        }
        Set<String> left = lfsObjects(world);
        assertTrue(wholeFiles.stream().noneMatch(left::contains), "the old whole region files are gone");
        assertTrue(repositoryBytes(world) < before);
        assertEquals(before - repositoryBytes(world), conversion.bytesFreed());
        assertEquals(0, await(engine.git.oldFormatSnapshotCount(world.id())));
    }

    /** Cancel arrives when the conversion reaches the second backup, before that backup changes. */
    @Test
    void aCancelLeavesEveryBackupRestorableAndConvertingAgainFinishes() throws Exception {
        engine = start(Engine.onlyDestination(DestinationType.GIT));
        TestWorld world = regionWorld("Cancelled");
        List<BackupId> backups = oldBackups(world, 2);
        CountDownLatch started = new CountDownLatch(1);
        List<CompletableFuture<GitConversion>> running = new ArrayList<>(1);
        ProgressListener cancelAtSecond = update -> {
            if (update.backupId().equals(Optional.of(backups.get(1)))) {
                awaitLatch(started);
                running.getFirst().cancel(true);
                awaitInterrupt();
            }
        };

        running.add(engine.recovery.convertGitBackups(world.id(), cancelAtSecond).toCompletableFuture());
        started.countDown();

        assertThrows(CancellationException.class, () -> await(running.getFirst()));
        assertEquals(2, storageFormat(world, backups.get(0)));
        assertEquals(1, storageFormat(world, backups.get(1)));
        for (BackupId backupId : backups) {
            assertRestoresChunks(captured.get(backupId), restore(backupId));
        }

        GitConversion again = await(engine.recovery.convertGitBackups(world.id(), ProgressListener.NO_OP));

        assertEquals(List.of(backups.get(1)), again.converted());
        assertEquals(1, again.unchanged());
        assertEquals(2, storageFormat(world, backups.get(1)));
        assertRestoresChunks(captured.get(backups.get(1)), restore(backups.get(1)));
    }

    @Test
    void aDamagedOldBackupIsReportedAndLeftAsItIsWhileTheOthersConvert() throws Exception {
        engine = start(Engine.onlyDestination(DestinationType.GIT));
        TestWorld world = regionWorld("Damaged");
        List<BackupId> backups = oldBackups(world, 2);
        BackupId damaged = backups.getFirst();
        String commit = commit(world, damaged);
        flipMiddleByte(lfsObject(world, sha256(captured.get(damaged).get(CHANGING))));

        GitConversion conversion = await(engine.recovery.convertGitBackups(world.id(), ProgressListener.NO_OP));

        assertEquals(List.of(backups.get(1)), conversion.converted());
        assertEquals(List.of(damaged), conversion.failures().stream().map(GitConversion.Failure::backupId).toList());
        assertTrue(conversion.failures().getFirst().reason().contains("damaged"), conversion.failures().getFirst().reason());
        assertEquals(commit, commit(world, damaged), "the damaged backup's snapshot did not move");
        assertEquals(1, storageFormat(world, damaged));
        assertRestoresChunks(captured.get(backups.get(1)), restore(backups.get(1)));
    }

    @Test
    void aBackupWithNoRegionFileTheBucketsCanHoldNeedsNothing() throws Exception {
        engine = start(Engine.onlyDestination(DestinationType.GIT));
        TestWorld world = TestWorld.create(engine, "Noise");
        BackupId backupId = engine.backupNow(world, BackupTrigger.MANUAL).backupId();
        String commit = commit(world, backupId);
        assertEquals(1, storageFormat(world, backupId));
        assertEquals(0, await(engine.git.oldFormatSnapshotCount(world.id())));

        GitConversion conversion = await(engine.recovery.convertGitBackups(world.id(), ProgressListener.NO_OP));

        assertEquals(List.of(), conversion.converted());
        assertEquals(1, conversion.unchanged());
        assertEquals(commit, commit(world, backupId));
    }

    /**
     * Three backups reach the remote in the old format and are converted here. Sync replaces
     * the remote's old form of the first; Delete removes the second although the remote still
     * holds its old form; and the third, whose remote branch now holds another backup, is not
     * replaced.
     */
    @Test
    void syncReplacesOnlyTheOldFormOfTheSameBackupOnTheRemote() throws Exception {
        Path remote = Files.createDirectories(root.resolve("remote.git"));
        git(remote, "init", "--bare", "--quiet");
        TestWorld world = regionWorld("Mirrored");
        engine = start(remoteConfig(world, remote));
        List<BackupId> backups = oldBackups(world, 3);
        await(engine.recovery.syncBackups(world.id(), backups, ProgressListener.NO_OP));
        Map<BackupId, String> oldOnRemote = remoteBranches(remote);
        backups.forEach(backupId -> assertEquals(SyncStatus.SYNCED, gitCopy(backupId).syncStatus()));

        GitConversion conversion = await(engine.recovery.convertGitBackups(world.id(), ProgressListener.NO_OP));

        assertEquals(backups, conversion.converted());
        backups.forEach(backupId -> assertEquals(SyncStatus.NOT_SYNCED, gitCopy(backupId).syncStatus()));
        BackupId synced = backups.get(0);
        BackupId deleted = backups.get(1);
        BackupId taken = backups.get(2);

        BackupResult uploaded = await(engine.recovery.syncBackup(synced, ProgressListener.NO_OP));

        assertEquals(SyncStatus.SYNCED, git(uploaded).syncStatus(), uploaded.destinations().toString());
        assertEquals(commit(world, synced), remoteBranches(remote).get(synced));

        BackupResult removed = engine.delete(deleted, ProgressListener.NO_OP);

        assertEquals(BackupStatus.SUCCESS, removed.status(), removed.destinations().toString());
        assertFalse(remoteBranches(remote).containsKey(deleted));

        String takenBranch = remoteBranch(remote, taken);
        git(remote, "update-ref", takenBranch, oldOnRemote.get(synced));
        BackupResult refused = await(engine.recovery.syncBackup(taken, ProgressListener.NO_OP));

        assertNotEquals(SyncStatus.SYNCED, git(refused).syncStatus(), refused.destinations().toString());
        assertEquals(oldOnRemote.get(synced), remoteBranches(remote).get(taken), "the other backup's branch was kept");
    }

    /**
     * Versions 0.1.0 to 0.1.3 named a backup's branch {@code worldarchive/<world>/<backup>}. Left
     * behind after a Sync, such a branch would keep the old form on the remote beside the new one.
     */
    @Test
    void syncRemovesABranchWithAnOldNameThatHoldsTheSameBackup() throws Exception {
        Path remote = Files.createDirectories(root.resolve("old-names.git"));
        git(remote, "init", "--bare", "--quiet");
        TestWorld world = regionWorld("OldNames");
        engine = start(remoteConfig(world, remote));
        BackupId backup = oldBackups(world, 1).getFirst();
        await(engine.recovery.syncBackups(world.id(), List.of(backup), ProgressListener.NO_OP));
        git(remote, "update-ref", "refs/heads/worldarchive/" + world.id() + "/" + backup,
                remoteBranches(remote).get(backup));
        await(engine.recovery.convertGitBackups(world.id(), ProgressListener.NO_OP));

        BackupResult uploaded = await(engine.recovery.syncBackup(backup, ProgressListener.NO_OP));

        assertEquals(SyncStatus.SYNCED, git(uploaded).syncStatus(), uploaded.destinations().toString());
        assertEquals(commit(world, backup), remoteBranches(remote).get(backup));
        assertEquals("", git(remote, "for-each-ref", "refs/heads/worldarchive/").strip());
    }

    /**
     * Makes {@code count} backups of the world, changing one region file between them, and turns
     * each snapshot into the old format: each bucketed region becomes one LFS object of the
     * captured file again, and the manifest says format 1. Compaction then drops the buckets.
     */
    private List<BackupId> oldBackups(TestWorld world, int count) throws Exception {
        List<BackupId> backups = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            engine.clock.advance(Duration.ofMinutes(1));
            world.write(CHANGING, RegionFileBuilder.scattered(60, 500 + index));
            BackupResult result = engine.backupNow(world, BackupTrigger.MANUAL);
            assertEquals(BackupStatus.SUCCESS, result.status(), result.destinations().toString());
            captured.put(result.backupId(), world.files());
            backups.add(result.backupId());
            toOldFormat(world, result.backupId());
        }
        await(engine.git.compactCurrentStorage(world.id()));
        for (BackupId backupId : backups) {
            assertEquals(1, storageFormat(world, backupId));
            assertTrue(await(engine.git.verifyCurrentSnapshot(world.id(), backupId)).valid());
        }
        return backups;
    }

    /** Rewrites one snapshot the way the writer stored it before buckets, keeping its message and time. */
    private void toOldFormat(TestWorld world, BackupId backupId) throws Exception {
        Path repository = engine.git.repositoryFor(world.id());
        String ref = GitSnapshot.refName(world.id(), backupId);
        String object = git(repository, "cat-file", "commit", ref);
        String message = object.substring(object.indexOf("\n\n") + 2);
        String time = git(repository, "log", "-1", "--format=%ct", ref).strip();
        String manifest = git(repository, "show", ref + ":.worldarchive-manifest.json")
                .replace("\"storageFormatVersion\": 2", "\"storageFormatVersion\": 1");
        List<String> regions = git(repository, "ls-tree", "-r", "--name-only", ref).lines()
                .filter(path -> path.startsWith(".worldarchive/regions/") && path.endsWith("/index"))
                .map(path -> path.substring(".worldarchive/regions/".length(), path.length() - "/index".length()))
                .toList();
        ByteArrayOutputStream stream = new ByteArrayOutputStream();
        text(stream, "commit " + ref + "\ncommitter WorldArchive <worldarchive@localhost> " + time + " +0000\n");
        data(stream, message.getBytes(StandardCharsets.UTF_8));
        text(stream, "M 040000 " + git(repository, "rev-parse", ref + "^{tree}").strip() + " \"\"\nD .worldarchive\n");
        for (String region : regions) {
            byte[] bytes = captured.get(backupId).get(region);
            Path lfsObject = lfsObject(world, sha256(bytes));
            Files.createDirectories(lfsObject.getParent());
            Files.write(lfsObject, bytes);
            text(stream, "M 100644 inline " + region + "\n");
            data(stream, ("version https://git-lfs.github.com/spec/v1\noid sha256:" + sha256(bytes) + "\nsize "
                    + bytes.length + "\n").getBytes(StandardCharsets.US_ASCII));
        }
        text(stream, "M 100644 inline .worldarchive-manifest.json\n");
        data(stream, manifest.getBytes(StandardCharsets.UTF_8));
        git(repository, Optional.of(stream.toByteArray()), "fast-import", "--quiet", "--force");
    }

    /** The test world plus real region files. */
    private TestWorld regionWorld(String name) throws IOException {
        TestWorld world = TestWorld.create(engine == null ? root.resolve("saves") : engine.saves, name);
        world.write(REGIONS.get(0), RegionFileBuilder.scattered(60));
        world.write(REGIONS.get(1), RegionFileBuilder.scattered(30));
        world.write(REGIONS.get(2), RegionFileBuilder.scattered(5));
        return world;
    }

    /** Real region files come back with every chunk and timestamp; every other file byte for byte. */
    private void assertRestoresChunks(Map<String, byte[]> expected, Path restored) throws IOException {
        Map<String, byte[]> actual = TestWorld.filesOf(restored);
        assertEquals(expected.keySet(), actual.keySet());
        for (Map.Entry<String, byte[]> file : expected.entrySet()) {
            if (REGIONS.contains(file.getKey())) {
                Path original = Files.write(Files.createTempFile(root, "original", ".mca"), file.getValue());
                assertEquals(RegionFileBuilder.chunks(original), RegionFileBuilder.chunks(restored.resolve(file.getKey())),
                        file.getKey());
                Files.delete(original);
            } else {
                assertArrayEquals(file.getValue(), actual.get(file.getKey()), file.getKey());
            }
        }
    }

    private Path restore(BackupId backupId) throws Exception {
        return await(engine.recovery.restoreBackup(new RestoreBackupRequest(backupId, engine.saves,
                "Restored " + backupId.toString().substring(0, 8) + " " + System.nanoTime()), ProgressListener.NO_OP))
                .restoredWorldDirectory();
    }

    private int storageFormat(TestWorld world, BackupId backupId) throws Exception {
        String manifest = git(engine.git.repositoryFor(world.id()), "show",
                GitSnapshot.refName(world.id(), backupId) + ":.worldarchive-manifest.json");
        return manifest.contains("\"storageFormatVersion\": 2") ? 2 : 1;
    }

    private String commit(TestWorld world, BackupId backupId) throws Exception {
        return git(engine.git.repositoryFor(world.id()), "rev-parse", GitSnapshot.refName(world.id(), backupId)).strip();
    }

    /** The LFS objects of the bucketed regions as the old format stored them, one per captured file. */
    private Set<String> wholeFileObjects(List<BackupId> backups) {
        return backups.stream()
                .flatMap(backupId -> REGIONS.stream().map(region -> sha256(captured.get(backupId).get(region))))
                .collect(Collectors.toSet());
    }

    private Set<String> lfsObjects(TestWorld world) throws IOException {
        try (Stream<Path> files = Files.walk(engine.git.repositoryFor(world.id()).resolve("lfs").resolve("objects"))) {
            return files.filter(Files::isRegularFile).map(file -> file.getFileName().toString()).collect(Collectors.toSet());
        }
    }

    private long repositoryBytes(TestWorld world) throws IOException {
        try (Stream<Path> files = Files.walk(engine.git.repositoryFor(world.id()))) {
            long total = 0;
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                total += Files.size(file);
            }
            return total;
        }
    }

    private Path lfsObject(TestWorld world, String sha256) {
        return engine.git.repositoryFor(world.id()).resolve("lfs").resolve("objects")
                .resolve(sha256.substring(0, 2)).resolve(sha256.substring(2, 4)).resolve(sha256);
    }

    private static String sha256(byte[] bytes) {
        return Digests.hex(Digests.sha256().digest(bytes));
    }

    private static void flipMiddleByte(Path object) throws IOException {
        byte[] rotten = Files.readAllBytes(object);
        rotten[rotten.length / 2] ^= 0x40;
        Files.move(Files.write(object.resolveSibling("rotten"), rotten), object, StandardCopyOption.REPLACE_EXISTING);
    }

    private DestinationResult gitCopy(BackupId backupId) {
        try {
            return git(engine.catalog.find(backupId).orElseThrow().result());
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static DestinationResult git(BackupResult result) {
        return result.destinations().stream()
                .filter(destination -> destination.destination() == DestinationType.GIT)
                .findFirst()
                .orElseThrow();
    }

    /** Each backup's branch commit on the remote. */
    private static Map<BackupId, String> remoteBranches(Path remote) throws Exception {
        Map<BackupId, String> branches = new LinkedHashMap<>();
        git(remote, "for-each-ref", "--format=%(objectname) %(refname)", "refs/heads/backups/").lines()
                .filter(line -> !line.isBlank())
                .forEach(line -> branches.put(BackupId.parse(line.substring(line.length() - 36)),
                        line.substring(0, line.indexOf(' '))));
        return branches;
    }

    private static String remoteBranch(Path remote, BackupId backupId) throws Exception {
        return git(remote, "for-each-ref", "--format=%(refname)", "refs/heads/backups/").lines()
                .filter(line -> line.endsWith(backupId.toString()))
                .findFirst()
                .orElseThrow();
    }

    private static WorldArchiveConfig remoteConfig(TestWorld world, Path remote) {
        WorldArchiveConfig gitOnly = Engine.onlyDestination(DestinationType.GIT,
                Engine.world(world.id(), world.path(), Optional.of(remote.toString()), StoragePolicy.defaults()));
        GitDestinationConfig git = gitOnly.git();
        return gitOnly.withGit(new GitDestinationConfig(git.enabled(), git.repository(), git.remoteName(),
                git.triggers(), git.lfsPatterns(), RemoteUpload.ONLY_WITH_SYNC));
    }

    private Engine start(WorldArchiveConfig config) {
        return new Engine(root, new Engine.TestClock(Instant.parse("2026-09-01T12:00:00Z")), config,
                SourceCaptureObserver.NONE);
    }

    private static void awaitLatch(CountDownLatch latch) {
        try {
            assertTrue(latch.await(Engine.TIMEOUT.toSeconds(), TimeUnit.SECONDS));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    /** Holds the conversion's worker until the cancel reaches it, so the cancel lands before the next backup. */
    private static void awaitInterrupt() {
        long deadline = System.nanoTime() + Engine.TIMEOUT.toNanos();
        while (!Thread.currentThread().isInterrupted() && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
    }

    private static void data(OutputStream stream, byte[] bytes) throws IOException {
        text(stream, "data " + bytes.length + "\n");
        stream.write(bytes);
        text(stream, "\n");
    }

    private static void text(OutputStream stream, String text) throws IOException {
        stream.write(text.getBytes(StandardCharsets.UTF_8));
    }

    private static String git(Path repository, String... arguments) throws Exception {
        return git(repository, Optional.empty(), arguments);
    }

    private static String git(Path repository, Optional<byte[]> input, String... arguments) throws Exception {
        List<String> command = new ArrayList<>(List.of("git", "--git-dir", repository.toString()));
        command.addAll(List.of(arguments));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        try (OutputStream stdin = process.getOutputStream()) {
            if (input.isPresent()) {
                stdin.write(input.get());
            }
        }
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (!process.waitFor(30, TimeUnit.SECONDS) || process.exitValue() != 0) {
            throw new IllegalStateException("git " + String.join(" ", arguments) + " failed: " + output);
        }
        return output;
    }
}
