package dev.ishaanko.worldarchive.e2e;

import static dev.ishaanko.worldarchive.e2e.Engine.await;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.ishaanko.worldarchive.config.StoragePolicy;
import dev.ishaanko.worldarchive.config.WorldArchiveConfig;
import dev.ishaanko.worldarchive.core.RestoreBackupRequest;
import dev.ishaanko.worldarchive.core.SourceCaptureObserver;
import dev.ishaanko.worldarchive.importing.ImportPreview;
import dev.ishaanko.worldarchive.model.BackupId;
import dev.ishaanko.worldarchive.model.BackupResult;
import dev.ishaanko.worldarchive.model.BackupStatus;
import dev.ishaanko.worldarchive.model.BackupTrigger;
import dev.ishaanko.worldarchive.model.DestinationResult;
import dev.ishaanko.worldarchive.model.DestinationType;
import dev.ishaanko.worldarchive.model.ProgressListener;
import dev.ishaanko.worldarchive.model.SyncStatus;
import dev.ishaanko.worldarchive.model.VerificationStatus;
import dev.ishaanko.worldarchive.storage.git.GitSnapshot;
import dev.ishaanko.worldarchive.storage.git.GitVerification;
import dev.ishaanko.worldarchive.storage.git.RegionFileBuilder;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Region files that parse strictly are stored as chunk buckets: they restore chunk for chunk and
 * timestamp for timestamp, an unchanged bucket is never stored twice, and damage names the file.
 */
class RegionBucketsEndToEndTest {
    /** Real region files; the test world's own region files are noise, which is stored whole. */
    private static final List<String> REGIONS = List.of(
            "region/r.1.0.mca", "region/r.-1.-1.mca", "DIM-1/region/r.0.0.mca", "region/r.2.2.mca");

    @TempDir
    Path root;

    private Engine engine;

    @AfterEach
    void closeEngine() throws Exception {
        if (engine != null) {
            engine.close();
        }
    }

    @Test
    void regionFilesRestoreChunkForChunkBesideFilesStoredWhole() throws Exception {
        engine = start(root, Engine.onlyDestination(DestinationType.GIT));
        TestWorld world = regionWorld(engine.saves, "Chunks");
        Map<String, byte[]> files = world.files();

        BackupResult result = engine.backupNow(world, BackupTrigger.MANUAL);

        assertEquals(BackupStatus.SUCCESS, result.status(), result.destinations().toString());
        String ref = GitSnapshot.refName(world.id(), result.backupId());
        Path repository = engine.git.repositoryFor(world.id());
        assertTrue(git(repository, "show", ref + ":.worldarchive-manifest.json").contains("\"storageFormatVersion\": 2"));
        Set<String> tree = git(repository, "ls-tree", "-r", "--name-only", ref).lines().collect(Collectors.toSet());
        assertFalse(tree.contains("region/r.1.0.mca"));
        assertTrue(tree.contains(".worldarchive/regions/region/r.1.0.mca/index"));
        assertTrue(tree.contains("region/r.0.0.mca"), "a region file that does not parse is stored whole");
        assertEquals(VerificationStatus.VERIFIED, destination(await(engine.recovery.verifyBackup(
                result.backupId(), ProgressListener.NO_OP))).verificationStatus());
        assertRestoresChunks(files, restore(result.backupId(), "Chunks restored"));
    }

    @Test
    void aChangedChunkWritesOneBucketAndCompactionKeepsOnlyTheBucketsLeftInUse() throws Exception {
        engine = start(root, Engine.onlyDestination(DestinationType.GIT));
        TestWorld world = regionWorld(engine.saves, "Edits");
        BackupId first = engine.backupNow(world, BackupTrigger.MANUAL).backupId();
        Set<String> firstObjects = lfsObjects(world);
        engine.clock.advance(Duration.ofMinutes(1));
        world.write("region/r.1.0.mca", RegionFileBuilder.scattered(60, 4_242));
        Map<String, byte[]> files = world.files();

        BackupResult second = engine.backupNow(world, BackupTrigger.MANUAL);
        Set<String> secondObjects = lfsObjects(world);
        engine.clock.advance(Duration.ofMinutes(1));
        BackupResult third = engine.backupNow(world, BackupTrigger.MANUAL);

        assertEquals(BackupStatus.SUCCESS, second.status(), second.destinations().toString());
        assertEquals(BackupStatus.SUCCESS, third.status(), third.destinations().toString());
        assertEquals(firstObjects.size() + 1, secondObjects.size(), "only the changed chunk's bucket is new");
        assertEquals(secondObjects, lfsObjects(world), "a backup with no change writes no object");

        assertEquals(BackupStatus.SUCCESS, engine.delete(first, ProgressListener.NO_OP).status());
        Set<String> kept = lfsObjects(world);
        assertEquals(referencedObjects(world, second.backupId()), kept);
        assertEquals(secondObjects.size() - 1, kept.size(), "the bucket only the deleted backup used is gone");
        assertRestoresChunks(files, restore(third.backupId(), "Edits restored"));
    }

    @Test
    void bucketsUploadAndAnImportOnAnotherComputerDownloadsAndRestoresThem() throws Exception {
        Path remote = Files.createDirectories(root.resolve("remote.git"));
        git(remote, "init", "--bare", "--quiet");
        TestWorld world = regionWorld(root.resolve("laptop").resolve("saves"), "Shared");
        Map<String, byte[]> files = world.files();
        engine = start(root.resolve("laptop"), Engine.onlyDestination(DestinationType.GIT,
                Engine.world(world.id(), world.path(), Optional.of(remote.toString()), StoragePolicy.defaults())));
        BackupResult backup = engine.backupNow(world, BackupTrigger.MANUAL);
        assertEquals(SyncStatus.SYNCED, destination(backup).syncStatus(), backup.destinations().toString());
        Set<String> uploaded = lfsObjects(world);
        engine.close();

        engine = start(root.resolve("desktop"), Engine.onlyDestination(DestinationType.GIT));
        ImportPreview preview = await(engine.imports.previewGit(remote.toString()));
        engine.importAll(preview);

        assertEquals(uploaded, objectsIn(remote.resolve("lfs").resolve("objects")));
        assertRestoresChunks(files, restore(backup.backupId(), "Shared restored"));
        assertEquals(uploaded, lfsObjects(world), "the import downloaded every bucket");
    }

    /** How a bucketed region can be damaged. */
    enum Damage {
        FLIPPED_BYTE, MISSING_OBJECT, OTHER_REGIONS_BUCKET
    }

    @ParameterizedTest
    @EnumSource(Damage.class)
    void aDamagedBucketFailsVerifyAndRestoreNamingTheFile(Damage damage) throws Exception {
        engine = start(root, Engine.onlyDestination(DestinationType.GIT));
        TestWorld world = regionWorld(engine.saves, "Damaged");
        BackupId backupId = engine.backupNow(world, BackupTrigger.MANUAL).backupId();
        Path repository = engine.git.repositoryFor(world.id());
        String ref = GitSnapshot.refName(world.id(), backupId);
        Path bucket = lfsObject(repository, pointedObject(repository, ref, "region/r.1.0.mca", "b00"));
        if (damage == Damage.FLIPPED_BYTE) {
            flipMiddleByte(bucket);
        } else if (damage == Damage.MISSING_OBJECT) {
            Files.delete(bucket);
        } else {
            replaceBucketPointer(repository, ref, "region/r.1.0.mca", "region/r.-1.-1.mca");
        }

        GitVerification checked = await(engine.git.verifyCurrentSnapshot(world.id(), backupId));
        DestinationResult verified = destination(await(engine.recovery.verifyBackup(backupId, ProgressListener.NO_OP)));
        ExecutionException restore = assertThrows(ExecutionException.class, () -> restore(backupId, "Damaged restored"));

        assertFalse(checked.valid());
        assertTrue(checked.message().contains("region/r.1.0.mca"), checked.message());
        assertEquals(VerificationStatus.FAILED, verified.verificationStatus());
        assertTrue(restore.getCause().getMessage().contains("region/r.1.0.mca"), restore.getCause().getMessage());
        try (Stream<Path> saves = Files.list(engine.saves)) {
            assertEquals(List.of(world.path()), saves.toList(), "the failed restore left no world behind");
        }
    }

    /** The test world, whose region files do not parse, plus real region files and an empty one. */
    private static TestWorld regionWorld(Path saves, String name) throws IOException {
        TestWorld world = TestWorld.create(saves, name);
        world.write(REGIONS.get(0), RegionFileBuilder.scattered(60));
        world.write(REGIONS.get(1), RegionFileBuilder.scattered(30));
        world.write(REGIONS.get(2), RegionFileBuilder.scattered(5));
        world.write(REGIONS.get(3), new RegionFileBuilder(2).bytes());
        return world;
    }

    /**
     * Real region files come back as compacted files with every chunk and timestamp and no free
     * space; every other file comes back byte for byte.
     */
    private void assertRestoresChunks(Map<String, byte[]> expected, Path restored) throws IOException {
        Map<String, byte[]> actual = TestWorld.filesOf(restored);
        assertEquals(expected.keySet(), actual.keySet());
        for (Map.Entry<String, byte[]> file : expected.entrySet()) {
            if (REGIONS.contains(file.getKey())) {
                Path original = Files.write(Files.createTempFile(root, "original", ".mca"), file.getValue());
                assertEquals(RegionFileBuilder.chunks(original), RegionFileBuilder.chunks(restored.resolve(file.getKey())),
                        file.getKey());
                assertTrue(actual.get(file.getKey()).length <= file.getValue().length, file.getKey());
                Files.delete(original);
            } else {
                assertArrayEquals(file.getValue(), actual.get(file.getKey()), file.getKey());
            }
        }
        assertNotEquals(expected.get(REGIONS.getFirst()).length, actual.get(REGIONS.getFirst()).length,
                "the dead space is gone");
    }

    private Path restore(BackupId backupId, String name) throws Exception {
        return await(engine.recovery.restoreBackup(
                new RestoreBackupRequest(backupId, engine.saves, name), ProgressListener.NO_OP)).restoredWorldDirectory();
    }

    private Set<String> lfsObjects(TestWorld world) throws IOException {
        return objectsIn(engine.git.repositoryFor(world.id()).resolve("lfs").resolve("objects"));
    }

    private static Set<String> objectsIn(Path objects) throws IOException {
        if (!Files.isDirectory(objects)) {
            return Set.of();
        }
        try (Stream<Path> files = Files.walk(objects)) {
            return files.filter(Files::isRegularFile).map(file -> file.getFileName().toString()).collect(Collectors.toSet());
        }
    }

    /** The SHA-256 of every LFS object the snapshot's pointers name. */
    private Set<String> referencedObjects(TestWorld world, BackupId backupId) throws Exception {
        Path repository = engine.git.repositoryFor(world.id());
        Set<String> objects = new HashSet<>();
        for (String line : git(repository, "ls-tree", "-r", GitSnapshot.refName(world.id(), backupId)).lines().toList()) {
            String blob = git(repository, "cat-file", "-p", line.split("\\s+")[2]);
            if (blob.startsWith("version https://git-lfs.github.com/spec/v1\n")) {
                objects.add(blob.lines().filter(text -> text.startsWith("oid sha256:")).findFirst().orElseThrow()
                        .substring("oid sha256:".length()));
            }
        }
        return objects;
    }

    private static String pointedObject(Path repository, String ref, String region, String bucket) throws Exception {
        String pointer = git(repository, "show", ref + ":.worldarchive/regions/" + region + "/" + bucket);
        return pointer.lines().filter(line -> line.startsWith("oid sha256:")).findFirst().orElseThrow()
                .substring("oid sha256:".length());
    }

    private static Path lfsObject(Path repository, String sha256) {
        return repository.resolve("lfs").resolve("objects")
                .resolve(sha256.substring(0, 2)).resolve(sha256.substring(2, 4)).resolve(sha256);
    }

    private static void flipMiddleByte(Path object) throws IOException {
        byte[] rotten = Files.readAllBytes(object);
        rotten[rotten.length / 2] ^= 0x40;
        Files.move(Files.write(object.resolveSibling("rotten"), rotten), object, StandardCopyOption.REPLACE_EXISTING);
    }

    /**
     * Points bucket b00 of {@code region} at bucket b00 of {@code other}, and commits the tree
     * again with the same message and time so the snapshot still names its backup.
     */
    private void replaceBucketPointer(Path repository, String ref, String region, String other) throws Exception {
        String otherBlob = git(repository, "rev-parse", ref + ":.worldarchive/regions/" + other + "/b00").strip();
        Map<String, String> environment = Map.of("GIT_INDEX_FILE", root.resolve("swap-index").toString());
        git(repository, environment, "read-tree", ref);
        git(repository, environment, "update-index", "--cacheinfo",
                "100644," + otherBlob + ",.worldarchive/regions/" + region + "/b00");
        String tree = git(repository, environment, "write-tree").strip();
        String time = "@" + git(repository, "log", "-1", "--format=%ct", ref).strip() + " +0000";
        Path message = Files.writeString(root.resolve("message"), git(repository, "log", "-1", "--format=%B", ref));
        String commit = git(repository, Map.of("GIT_AUTHOR_NAME", "WorldArchive", "GIT_AUTHOR_EMAIL", "a@localhost",
                "GIT_COMMITTER_NAME", "WorldArchive", "GIT_COMMITTER_EMAIL", "a@localhost",
                "GIT_AUTHOR_DATE", time, "GIT_COMMITTER_DATE", time), "commit-tree", tree, "-F", message.toString()).strip();
        git(repository, "update-ref", ref, commit);
    }

    private static DestinationResult destination(BackupResult result) {
        return result.destinations().stream()
                .filter(destination -> destination.destination() == DestinationType.GIT)
                .findFirst()
                .orElseThrow();
    }

    private static Engine start(Path home, WorldArchiveConfig config) {
        return new Engine(home, new Engine.TestClock(Instant.parse("2026-09-01T12:00:00Z")), config, SourceCaptureObserver.NONE);
    }

    private static String git(Path repository, String... arguments) throws Exception {
        return git(repository, Map.of(), arguments);
    }

    private static String git(Path repository, Map<String, String> environment, String... arguments) throws Exception {
        List<String> command = new ArrayList<>(List.of("git", "--git-dir", repository.toString()));
        command.addAll(List.of(arguments));
        ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
        builder.environment().putAll(environment);
        Process process = builder.start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (!process.waitFor(30, TimeUnit.SECONDS) || process.exitValue() != 0) {
            throw new IllegalStateException("git " + String.join(" ", arguments) + " failed: " + output);
        }
        return output;
    }
}
