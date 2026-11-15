package dev.ishaanko.worldarchive.storage.git;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
import dev.ishaanko.worldarchive.model.WorldId;
import dev.ishaanko.worldarchive.support.PortablePath;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** How the Git writer and reader treat bucketed regions where the engine cannot reach. */
class GitRegionSnapshotsTest {
    private static final Instant START = Instant.parse("2026-09-01T12:00:00Z");

    private static final String REGION = "region/r.0.0.mca";

    @TempDir
    Path temporaryDirectory;

    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    private WorldGitSnapshotStore store;

    @BeforeEach
    void requireGitAndLfs() throws Exception {
        GitToolHealth tools = new GitToolProbe(settings(), new SystemGitCommandRunner()).probe();
        Assumptions.assumeTrue(tools.available(), tools.summary());
        store = new WorldGitSnapshotStore(settings(), Map.of(), new SystemGitCommandRunner(), executor);
    }

    @AfterEach
    void stopExecutor() {
        executor.shutdownNow();
    }

    /**
     * The second capture's region file is replaced after its inventory was taken. Read, it would
     * fail the backup; an unchanged region reuses its buckets without reading the file.
     */
    @Test
    void anUnchangedRegionReusesItsBucketsWithoutReadingTheFile() throws Exception {
        Path world = world("Unchanged");
        WorldId worldId = WorldId.create();
        byte[] region = Files.readAllBytes(world.resolve(REGION));
        backup(world, worldId, START);
        long objects = lfsObjects(worldId);
        BackupCapture second = capture(world, worldId, START.plusSeconds(60));
        Files.write(world.resolve(REGION), new byte[region.length]);

        DestinationResult result = store.createBackup(second, ProgressListener.NO_OP);

        assertEquals(DestinationStatus.SUCCESS, result.status(), result.message().orElse(""));
        assertEquals(objects, lfsObjects(worldId));
        Path staging = restore(second);
        Path original = Files.write(temporaryDirectory.resolve("original.mca"), region);
        assertEquals(RegionFileBuilder.chunks(original), RegionFileBuilder.chunks(staging.resolve(REGION)));
    }

    @Test
    void aChangedRegionThatNoLongerMatchesItsCaptureIsNeverPublished() throws Exception {
        Path world = world("Changed");
        WorldId worldId = WorldId.create();
        backup(world, worldId, START);
        Files.write(world.resolve(REGION), RegionFileBuilder.scattered(40, 77));
        BackupCapture second = capture(world, worldId, START.plusSeconds(60));
        Files.write(world.resolve(REGION), RegionFileBuilder.scattered(40, 78));

        DestinationResult result = store.createBackup(second, ProgressListener.NO_OP);

        assertEquals(DestinationStatus.FAILED, result.status());
        assertTrue(result.message().orElseThrow().contains(REGION), result.message().orElseThrow());
        assertEquals(1, await(store.listSnapshots(Optional.of(worldId))).size());
    }

    @Test
    void aBackupRewritesAReusedBucketWhoseMarkerRotted() throws Exception {
        requireRepairOfABucketDamagedAt(0);
    }

    @Test
    void aBackupRewritesAReusedBucketWhoseChunkBytesRotted() throws Exception {
        requireRepairOfABucketDamagedAt(-1);
    }

    /**
     * A bucket object rots in place, keeping its size, and then a chunk of another bucket changes.
     * The next backup finds the rotten object by its size, sees that the buckets no longer rebuild
     * the captured file, writes that bucket again, and stores a snapshot that restores.
     *
     * @param position the byte to flip: 0 for the marker, -1 for the last byte of the last chunk
     */
    private void requireRepairOfABucketDamagedAt(int position) throws Exception {
        Path world = world("Rotten" + position);
        WorldId worldId = WorldId.create();
        backup(world, worldId, START);
        Path rotten;
        try (Stream<Path> files = Files.walk(store.repositoryFor(worldId).resolve("lfs/objects"))) {
            rotten = files.filter(Files::isRegularFile)
                    .filter(GitRegionSnapshotsTest::isBucketOtherThanTheFirst)
                    .findFirst()
                    .orElseThrow();
        }
        byte[] bytes = Files.readAllBytes(rotten);
        bytes[position < 0 ? bytes.length + position : position] ^= 0x55;
        Files.write(rotten, bytes);
        long objects = lfsObjects(worldId);
        Files.write(world.resolve(REGION), RegionFileBuilder.scattered(40, 99));
        BackupCapture second = capture(world, worldId, START.plusSeconds(60));

        DestinationResult result = store.createBackup(second, ProgressListener.NO_OP);

        assertEquals(DestinationStatus.SUCCESS, result.status(), result.message().orElse(""));
        assertEquals(objects + 1, lfsObjects(worldId), "one new bucket for the changed chunk; the rotten one is replaced");
        assertEquals(RegionFileBuilder.chunks(world.resolve(REGION)),
                RegionFileBuilder.chunks(restore(second).resolve(REGION)));
    }

    /** Whether a file in the LFS store is a bucket object of a bucket other than number 0, which chunk 0 is in. */
    private static boolean isBucketOtherThanTheFirst(Path object) {
        try {
            byte[] bytes = Files.readAllBytes(object);
            return bytes.length > 6 && bytes[0] == 'W' && bytes[1] == 'A' && bytes[2] == 'R' && bytes[3] == 'B'
                    && bytes[5] != 0;
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    /**
     * The writer stores several files at once. Equal region files and equal whole files make its
     * workers write the same objects at the same time; every backup still verifies, and two
     * repositories get the same tree whatever order the workers finish in.
     */
    @Test
    void filesWrittenAtOnceGiveOneTreeWhateverOrderTheyFinish() throws Exception {
        Path world = world("Parallel");
        for (int copy = 0; copy < 16; copy++) {
            Files.write(world.resolve("region/r.1." + copy + ".mca"), RegionFileBuilder.scattered(40, copy % 2));
            Files.write(world.resolve("region/r.2." + copy + ".mca"), RegionFileBuilder.payload(64 * 1_024, 3));
        }
        WorldId worldId = WorldId.create();
        BackupCapture capture = capture(world, worldId, START);
        WorldGitSnapshotStore other = new WorldGitSnapshotStore(settings("other-storage"), Map.of(),
                new SystemGitCommandRunner(), executor);
        String ref = GitSnapshot.refName(worldId, capture.manifest().backupId());

        List<String> trees = new ArrayList<>();
        for (WorldGitSnapshotStore target : List.of(store, other)) {
            DestinationResult result = target.createBackup(capture, ProgressListener.NO_OP);
            assertEquals(DestinationStatus.SUCCESS, result.status(), result.message().orElse(""));
            GitVerification verification = await(target.verifyCurrentSnapshot(worldId, capture.manifest().backupId()));
            assertTrue(verification.valid(), verification.message());
            trees.add(git(target.repositoryFor(worldId), new byte[0], "rev-parse", ref + "^{tree}").strip());
        }

        assertEquals(trees.get(0), trees.get(1));
    }

    /** A snapshot in the layout WorldArchive wrote before buckets: the region file is one LFS object. */
    @Test
    void aVersionOneSnapshotStillVerifiesAndRestoresByteForByte() throws Exception {
        Path world = world("Older");
        WorldId worldId = WorldId.create();
        backup(world, worldId, START);
        BackupCapture older = capture(world, worldId, START.plusSeconds(60));
        writeVersionOneSnapshot(older);

        assertTrue(await(store.verifyCurrentSnapshot(worldId, older.manifest().backupId())).valid());
        Path staging = restore(older);
        assertEquals(older.inventory(), TestCaptures.inventoryOf(staging));
        assertArrayEquals(Files.readAllBytes(world.resolve(REGION)), Files.readAllBytes(staging.resolve(REGION)));
    }

    @Test
    void regionBucketsInAVersionOneSnapshotAreRefused() throws Exception {
        Path world = world("Mislabeled");
        WorldId worldId = WorldId.create();
        BackupCapture capture = capture(world, worldId, START);
        assertEquals(DestinationStatus.SUCCESS, store.createBackup(capture, ProgressListener.NO_OP).status());
        String ref = GitSnapshot.refName(worldId, capture.manifest().backupId());
        String commit = git(store.repositoryFor(worldId), new byte[0], "rev-parse", ref).strip();
        byte[] manifest = GitSnapshotManifestCodec.encode(
                GitSnapshotManifest.create(capture.manifest(), GitBackendSettings.DEFAULT_LFS_PATTERNS));
        ByteArrayOutputStream stream = new ByteArrayOutputStream();
        commitHeader(stream, ref, capture.manifest());
        text(stream, "from " + commit + "\nM 100644 inline " + GitTreeValidator.MANIFEST_PATH + "\n");
        data(stream, manifest);
        git(store.repositoryFor(worldId), stream.toByteArray(), "fast-import", "--quiet", "--force");

        GitVerification verification = await(store.verifyCurrentSnapshot(worldId, capture.manifest().backupId()));

        assertFalse(verification.valid());
        assertTrue(verification.message().contains("storage format"), verification.message());
    }

    /**
     * Writes the snapshot as the writer did before buckets: every {@code *.mca} file as an LFS
     * object behind a pointer, every other file as a blob, and a version 1 manifest.
     */
    private void writeVersionOneSnapshot(BackupCapture capture) throws Exception {
        Path repository = store.repositoryFor(capture.manifest().worldId());
        ByteArrayOutputStream stream = new ByteArrayOutputStream();
        commitHeader(stream, GitSnapshot.refName(capture.manifest().worldId(), capture.manifest().backupId()),
                capture.manifest());
        for (WorldInventory.Entry file : capture.inventory().files()) {
            byte[] bytes = Files.readAllBytes(PortablePath.resolveInside(capture.worldDirectory(), file.path()));
            text(stream, "M 100644 inline " + file.path() + "\n");
            if (file.path().endsWith(".mca")) {
                Path object = repository.resolve("lfs/objects").resolve(file.sha256().substring(0, 2))
                        .resolve(file.sha256().substring(2, 4)).resolve(file.sha256());
                Files.createDirectories(object.getParent());
                Files.write(object, bytes);
                data(stream, new GitLfsPointer(file.sha256(), file.size()).text().getBytes(StandardCharsets.US_ASCII));
            } else {
                data(stream, bytes);
            }
        }
        text(stream, "M 100644 inline " + GitTreeValidator.MANIFEST_PATH + "\n");
        data(stream, GitSnapshotManifestCodec.encode(
                GitSnapshotManifest.create(capture.manifest(), GitBackendSettings.DEFAULT_LFS_PATTERNS)));
        git(repository, stream.toByteArray(), "fast-import", "--quiet");
    }

    private static void commitHeader(OutputStream stream, String ref, BackupManifest manifest) throws IOException {
        long time = manifest.createdAt().getEpochSecond();
        text(stream, "commit " + ref + "\n"
                + "author WorldArchive <worldarchive@localhost> " + time + " +0000\n"
                + "committer WorldArchive <worldarchive@localhost> " + time + " +0000\n");
        data(stream, GitSnapshotManifest.create(manifest, GitBackendSettings.DEFAULT_LFS_PATTERNS).commitMessage()
                .getBytes(StandardCharsets.UTF_8));
    }

    private static void data(OutputStream stream, byte[] bytes) throws IOException {
        text(stream, "data " + bytes.length + "\n");
        stream.write(bytes);
        text(stream, "\n");
    }

    private static void text(OutputStream stream, String text) throws IOException {
        stream.write(text.getBytes(StandardCharsets.UTF_8));
    }

    /** A world with one real region file, one that does not parse, and a player file. */
    private Path world(String name) throws IOException {
        Path world = temporaryDirectory.resolve("worlds").resolve(name);
        Files.createDirectories(world.resolve("region"));
        Files.write(world.resolve(REGION), RegionFileBuilder.scattered(40));
        Files.write(world.resolve("region/r.0.1.mca"), RegionFileBuilder.payload(64 * 1_024, 3));
        Files.write(world.resolve("level.dat"), RegionFileBuilder.payload(4_096, 4));
        return world;
    }

    private void backup(Path world, WorldId worldId, Instant time) throws Exception {
        DestinationResult result = store.createBackup(capture(world, worldId, time), ProgressListener.NO_OP);
        assertEquals(DestinationStatus.SUCCESS, result.status(), result.message().orElse(""));
    }

    private Path restore(BackupCapture capture) throws Exception {
        Path staging = Files.createDirectories(temporaryDirectory.resolve("restore-" + capture.manifest().backupId()));
        await(store.restoreSnapshot(capture.manifest().worldId(), capture.manifest().backupId(), capture.manifest(),
                staging));
        return staging;
    }

    private static BackupCapture capture(Path world, WorldId worldId, Instant time) throws IOException {
        WorldInventory inventory = TestCaptures.inventoryOf(world);
        BackupManifest manifest = BackupManifest.create(BackupId.create(), worldId, world.getFileName().toString(),
                Optional.empty(), time, BackupTrigger.MANUAL, inventory.fileCount(), inventory.byteCount(),
                inventory.fileCount(), inventory.contentSha256(), inventory.inventorySha256(), Optional.empty());
        return new BackupCapture(world, manifest, inventory);
    }

    private GitBackendSettings settings() {
        return settings("storage");
    }

    private GitBackendSettings settings(String folder) {
        return new GitBackendSettings(true, temporaryDirectory.resolve(folder), FolderOrigin.DEFAULT, "git", "origin",
                Optional.empty(), GitBackendSettings.DEFAULT_LFS_PATTERNS, Duration.ofSeconds(30), 4 * 1_024 * 1_024);
    }

    private long lfsObjects(WorldId worldId) throws IOException {
        try (Stream<Path> files = Files.walk(store.repositoryFor(worldId).resolve("lfs/objects"))) {
            return files.filter(Files::isRegularFile).count();
        }
    }

    private String git(Path repository, byte[] input, String... arguments) throws Exception {
        List<String> command = new ArrayList<>(List.of("git", "--git-dir=" + repository));
        command.addAll(List.of(arguments));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        try (OutputStream stdin = process.getOutputStream()) {
            stdin.write(input);
        }
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(process.waitFor(30, TimeUnit.SECONDS));
        assertEquals(0, process.exitValue(), output);
        return output;
    }

    private static <T> T await(CompletionStage<T> stage) throws Exception {
        return stage.toCompletableFuture().get(60, TimeUnit.SECONDS);
    }
}
