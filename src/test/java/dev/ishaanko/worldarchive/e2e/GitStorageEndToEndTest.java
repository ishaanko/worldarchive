package dev.ishaanko.worldarchive.e2e;

import static dev.ishaanko.worldarchive.e2e.Engine.await;
import static dev.ishaanko.worldarchive.e2e.TestWorld.assertSameFiles;
import static dev.ishaanko.worldarchive.e2e.TestWorld.bytes;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.ishaanko.worldarchive.config.StoragePolicy;
import dev.ishaanko.worldarchive.config.WorldArchiveConfig;
import dev.ishaanko.worldarchive.core.DeleteBackupRequest;
import dev.ishaanko.worldarchive.core.RestoreBackupRequest;
import dev.ishaanko.worldarchive.core.SourceCaptureObserver;
import dev.ishaanko.worldarchive.importing.ImportDisposition;
import dev.ishaanko.worldarchive.importing.ImportPreview;
import dev.ishaanko.worldarchive.model.BackupId;
import dev.ishaanko.worldarchive.model.BackupResult;
import dev.ishaanko.worldarchive.model.BackupStatus;
import dev.ishaanko.worldarchive.model.BackupTrigger;
import dev.ishaanko.worldarchive.model.DestinationResult;
import dev.ishaanko.worldarchive.model.DestinationStatus;
import dev.ishaanko.worldarchive.model.DestinationType;
import dev.ishaanko.worldarchive.model.ProgressListener;
import dev.ishaanko.worldarchive.model.SyncStatus;
import dev.ishaanko.worldarchive.model.VerificationStatus;
import dev.ishaanko.worldarchive.storage.git.GitSnapshot;
import dev.ishaanko.worldarchive.support.Digests;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Git storage through the real engine: odd world content, deletes, broken remotes, repairs and imports. */
class GitStorageEndToEndTest {
    private static final String NO_COMMIT = "0000000000000000000000000000000000000000";

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
    void aWorldWithTrickyFilesRestoresByteForByte() throws Exception {
        engine = start(root, Engine.onlyDestination(DestinationType.GIT));
        TestWorld world = TestWorld.create(engine, "Tricky");
        world.write(".gitignore", "*.dat\nregion/\n*.txt\n".getBytes(StandardCharsets.UTF_8));
        world.write(".gitattributes", "*.txt ident eol=crlf\n*.json filter=lfs\n* text\n".getBytes(StandardCharsets.UTF_8));
        world.write("notes.txt", "$Id: kept exactly$\nline one\nline two\n".getBytes(StandardCharsets.UTF_8));
        world.write("data/large.json", bytes(5 * 1_024 * 1_024, 51));
        world.write("nested/session.lock", "a nested lock is world data".getBytes(StandardCharsets.UTF_8));
        world.write("names/Cafe\u0301.txt", "a decomposed name".getBytes(StandardCharsets.UTF_8));
        world.write("names/pointer-like.txt",
                "version https://git-lfs.github.com/spec/v1 is only text here\n".getBytes(StandardCharsets.UTF_8));
        Map<String, byte[]> files = world.files();

        BackupResult result = engine.backupNow(world, BackupTrigger.MANUAL);

        assertEquals(BackupStatus.SUCCESS, result.status(), result.destinations().toString());
        assertSameFiles(files, restore(result.backupId(), "Tricky restored"));
        assertSameFiles(files, world.path());
    }

    /**
     * A file outside the LFS patterns can hold the text of an LFS pointer, as in a datapack cloned
     * without Git LFS. Such files back up and restore byte for byte, as does one under a pattern.
     */
    @Test
    void filesThatHoldLfsPointerTextBackUpAndRestore() throws Exception {
        engine = start(root, Engine.onlyDestination(DestinationType.GIT));
        TestWorld world = TestWorld.create(engine, "Pointers");
        String pointer = "version https://git-lfs.github.com/spec/v1\n"
                + "oid sha256:4d7a214614ab2935c943f9e0ff69d22eadbb8f32b1258daaa5e2ca24d17e2393\n"
                + "size 12345\n";
        world.write("datapacks/pack/pack.png", pointer.getBytes(StandardCharsets.UTF_8));
        world.write("datapacks/pack/README.md",
                pointer.replace("oid", "ext-0-foo sha256:ffff\noid").getBytes(StandardCharsets.UTF_8));
        world.write("data/pointer-like.dat", pointer.getBytes(StandardCharsets.UTF_8));
        Map<String, byte[]> files = world.files();

        BackupResult result = engine.backupNow(world, BackupTrigger.MANUAL);

        assertEquals(BackupStatus.SUCCESS, result.status(), result.destinations().toString());
        assertSameFiles(files, restore(result.backupId(), "Pointers restored"));
    }

    @Test
    void deletedBackupsLeaveTheRemoteAndThisComputerAndStayGoneAfterARestart() throws Exception {
        Path remote = bareRepository("remote.git");
        TestWorld world = TestWorld.create(root.resolve("saves"), "Pruned");
        WorldArchiveConfig config = gitOnly(world, remote);
        engine = start(root, config);
        List<BackupId> backups = backupOnThreeMinutes(world);
        assertTrue(await(engine.git.deleteLocalSnapshot(world.id(), backups.get(0))), "cleanup kept only the remote copy");

        List<BackupResult> deleted = await(engine.recovery.deleteBackups(
                List.of(request(backups.get(0)), request(backups.get(1))), ProgressListener.NO_OP));

        assertTrue(deleted.stream().allMatch(result -> result.status() == BackupStatus.SUCCESS), deleted.toString());
        assertEquals(List.of(branch(remote, backups.get(2))), branches(remote));
        assertEquals(commit(backups.get(2), world), git(remote, "rev-parse", "refs/heads/main").strip());
        assertEquals(4, lfsObjects(world), "only the kept backup's LFS objects remain");
        engine.close();
        engine = start(root, config);
        await(engine.imports.rebuildLocal());
        assertEquals(List.of(backups.get(2)), engine.records(world.id()).stream()
                .map(record -> record.manifest().backupId())
                .toList());
    }

    @Test
    void locksLeftByAStoppedGitDoNotBlockTheNextBackupOrDelete() throws Exception {
        Path remote = bareRepository("locked.git");
        TestWorld world = TestWorld.create(root.resolve("saves"), "Locked");
        WorldArchiveConfig config = gitOnly(world, remote);
        engine = start(root, config);
        BackupId first = engine.backupNow(world, BackupTrigger.MANUAL).backupId();
        engine.close();
        Path repository = engine.git.repositoryFor(world.id());
        List<Path> locks = List.of(
                repository.resolve("config.lock"),
                repository.resolve("packed-refs.lock"),
                repository.resolve(GitSnapshot.refName(world.id(), first) + ".lock"));
        for (Path lock : locks) {
            Files.write(lock, new byte[0]);
            Files.setLastModifiedTime(lock, FileTime.from(Instant.now().minus(Duration.ofMinutes(10))));
        }
        engine = start(root, config);
        engine.clock.advance(Duration.ofMinutes(1));
        world.write("level.dat", bytes(4_096, 71));

        BackupResult next = engine.backupNow(world, BackupTrigger.MANUAL);
        BackupResult deleted = delete(first);

        assertEquals(SyncStatus.SYNCED, destination(next).syncStatus(), next.destinations().toString());
        assertEquals(BackupStatus.SUCCESS, deleted.status(), deleted.destinations().toString());
        assertTrue(locks.stream().noneMatch(Files::exists));
    }

    @Test
    void anEmptyRepositoryFolderMadeBeforeTheFirstBackupIsSetUpByIt() throws Exception {
        engine = start(root, Engine.onlyDestination(DestinationType.GIT));
        TestWorld world = TestWorld.create(engine, "Opened");
        Map<String, byte[]> files = world.files();
        // WorldArchive 0.4.0 made this folder when the player opened the backup folder first.
        Files.createDirectories(engine.git.repositoryFor(world.id()));

        BackupResult result = engine.backupNow(world, BackupTrigger.MANUAL);

        assertEquals(BackupStatus.SUCCESS, result.status(), result.destinations().toString());
        assertSameFiles(files, restore(result.backupId(), "Opened restored"));
    }

    /** A sync tool removed the empty refs folder after Git packed the refs: the next backup keeps every snapshot. */
    @Test
    void aRepositoryWhoseRefsFolderWasRemovedKeepsItsSnapshots() throws Exception {
        engine = start(root, Engine.onlyDestination(DestinationType.GIT));
        TestWorld world = TestWorld.create(engine, "Packed");
        Map<String, byte[]> files = world.files();
        BackupId first = engine.backupNow(world, BackupTrigger.MANUAL).backupId();
        Path repository = engine.git.repositoryFor(world.id());
        git(repository, "pack-refs", "--all");
        deleteTree(repository.resolve("refs"));
        engine.clock.advance(Duration.ofMinutes(1));
        world.write("level.dat", bytes(4_096, 9));

        BackupResult second = engine.backupNow(world, BackupTrigger.MANUAL);

        assertEquals(BackupStatus.SUCCESS, second.status(), second.destinations().toString());
        assertEquals(2, await(engine.git.listSnapshots(Optional.of(world.id()))).size());
        assertSameFiles(files, restore(first, "Packed first"));
    }

    @Test
    void aProtectedMainNeverFailsAnUploadAndOnlyBlocksDeletingTheBackupItShows() throws Exception {
        Path remote = bareRepository("protected.git");
        Path hook = remote.resolve("hooks").resolve("pre-receive");
        Files.writeString(hook, """
                #!/bin/sh
                while read old new ref; do
                  if [ "$ref" = "refs/heads/main" ] && [ "$old" != "%s" ]; then
                    echo "main is protected" >&2
                    exit 1
                  fi
                done
                """.formatted(NO_COMMIT));
        assertTrue(hook.toFile().setExecutable(true));
        TestWorld world = TestWorld.create(root.resolve("saves"), "Protected");
        engine = start(root, gitOnly(world, remote));
        BackupId shown = engine.backupNow(world, BackupTrigger.MANUAL).backupId();
        engine.clock.advance(Duration.ofMinutes(1));
        world.write("level.dat", bytes(4_096, 72));

        BackupResult newer = engine.backupNow(world, BackupTrigger.MANUAL);
        String before = git(remote, "for-each-ref", "--format=%(refname) %(objectname)");
        BackupResult refused = delete(shown);
        String afterRefusal = git(remote, "for-each-ref", "--format=%(refname) %(objectname)");
        BackupResult allowed = delete(newer.backupId());

        assertEquals(SyncStatus.SYNCED, destination(newer).syncStatus(), newer.destinations().toString());
        assertEquals(commit(shown, world), git(remote, "rev-parse", "refs/heads/main").strip());
        assertEquals(DestinationStatus.FAILED, destination(refused).status());
        assertEquals(before, afterRefusal);
        assertEquals(BackupStatus.SUCCESS, allowed.status(), allowed.destinations().toString());
        assertEquals(List.of(branch(remote, shown)), branches(remote));
    }

    @Test
    void deletingWhileTheRemoteIsUnreachableRemovesNothingAndWorksLater() throws Exception {
        Path remote = bareRepository("travelling.git");
        TestWorld world = TestWorld.create(root.resolve("saves"), "Offline");
        engine = start(root, gitOnly(world, remote));
        BackupId backupId = engine.backupNow(world, BackupTrigger.MANUAL).backupId();
        Path away = Files.move(remote, root.resolve("unplugged.git"));

        BackupResult refused = delete(backupId);

        assertEquals(DestinationStatus.FAILED, destination(refused).status());
        assertTrue(destination(refused).message().orElseThrow().contains("could not be reached"),
                destination(refused).message().orElseThrow());
        assertEquals(1, engine.records(world.id()).size());
        assertEquals(1, await(engine.git.listSnapshots(Optional.of(world.id()))).size());
        Files.move(away, remote);
        assertEquals(BackupStatus.SUCCESS, delete(backupId).status());
        assertTrue(branches(remote).isEmpty());
    }

    @Test
    void aLostLfsObjectOrRepositoryIsRestoredFromTheRemoteAndRepairedHere() throws Exception {
        Path remote = bareRepository("rescue.git");
        TestWorld world = TestWorld.create(root.resolve("saves"), "Rescued");
        engine = start(root, gitOnly(world, remote));
        Map<String, byte[]> files = world.files();
        BackupId backupId = engine.backupNow(world, BackupTrigger.MANUAL).backupId();
        Path repository = engine.git.repositoryFor(world.id());
        Path region = lfsObject(repository, files.get("region/r.0.0.mca"));
        Files.delete(region);

        assertSameFiles(files, restore(backupId, "Rescued once"));
        BackupResult verified = await(engine.recovery.verifyBackup(backupId, ProgressListener.NO_OP));
        assertEquals(VerificationStatus.VERIFIED, destination(verified).verificationStatus());
        assertTrue(Files.isRegularFile(region), "the object is back on this computer");

        deleteTree(repository);
        assertSameFiles(files, restore(backupId, "Rescued twice"));
        assertEquals(1, await(engine.git.listSnapshots(Optional.of(world.id()))).size());
    }

    /**
     * An LFS object rotted in place: same size, other bytes. Verify finds it and sets it aside, so
     * the next backup of the unchanged file writes the object again from the capture.
     */
    @Test
    void aBackupAfterVerifyFoundADamagedLfsObjectStoresTheFileIntactAgain() throws Exception {
        engine = start(root, Engine.onlyDestination(DestinationType.GIT));
        TestWorld world = TestWorld.create(engine, "Rot");
        BackupId first = engine.backupNow(world, BackupTrigger.MANUAL).backupId();
        rot(lfsObject(engine.git.repositoryFor(world.id()), world.files().get("region/r.0.0.mca")));
        assertEquals(VerificationStatus.FAILED, destination(await(engine.recovery.verifyBackup(
                first, ProgressListener.NO_OP))).verificationStatus());
        engine.clock.advance(Duration.ofMinutes(5));
        world.write("level.dat", bytes(4_096, 77));

        BackupResult second = engine.backupNow(world, BackupTrigger.MANUAL);

        assertEquals(BackupStatus.SUCCESS, second.status(), second.destinations().toString());
        BackupResult verified = await(engine.recovery.verifyBackup(second.backupId(), ProgressListener.NO_OP));
        assertEquals(VerificationStatus.VERIFIED, destination(verified).verificationStatus(), verified.toString());
    }

    /** With a remote, an LFS object that rotted in place is downloaded again, and the restore goes on. */
    @Test
    void aRestoreWithARemoteReplacesAnLfsObjectThatRottedInPlace() throws Exception {
        Path remote = bareRepository("rot.git");
        TestWorld world = TestWorld.create(root.resolve("saves"), "Rot");
        engine = start(root, gitOnly(world, remote));
        Map<String, byte[]> files = world.files();
        BackupResult backup = engine.backupNow(world, BackupTrigger.MANUAL);
        assertEquals(SyncStatus.SYNCED, destination(backup).syncStatus(), backup.toString());
        Path object = lfsObject(engine.git.repositoryFor(world.id()), files.get("region/r.0.0.mca"));
        rot(object);

        assertSameFiles(files, restore(backup.backupId(), "Rot restored"));
        assertArrayEquals(files.get("region/r.0.0.mca"), Files.readAllBytes(object), "the object is repaired here");
    }

    @Test
    void aDamagedGitCopyWithoutARemoteIsNeverRestored() throws Exception {
        engine = start(root, Engine.onlyDestination(DestinationType.GIT));
        TestWorld world = TestWorld.create(engine, "Damaged");
        BackupId backupId = engine.backupNow(world, BackupTrigger.MANUAL).backupId();
        Files.delete(lfsObject(engine.git.repositoryFor(world.id()), world.files().get("region/r.0.0.mca")));

        assertThrows(ExecutionException.class, () -> restore(backupId, "Damaged restored"));

        try (Stream<Path> saves = Files.list(engine.saves)) {
            assertEquals(List.of(world.path()), saves.toList());
        }
    }

    @Test
    void hooksInsideTheRepositoryNeverRun() throws Exception {
        Path remote = bareRepository("hooked.git");
        TestWorld world = TestWorld.create(root.resolve("saves"), "Hooked");
        engine = start(root, gitOnly(world, remote));
        BackupId first = engine.backupNow(world, BackupTrigger.MANUAL).backupId();
        Path marker = root.resolve("a hook ran");
        for (String name : List.of("pre-push", "reference-transaction", "post-checkout", "pre-auto-gc")) {
            Path hook = engine.git.repositoryFor(world.id()).resolve("hooks").resolve(name);
            Files.createDirectories(hook.getParent());
            Files.writeString(hook, "#!/bin/sh\ntouch '" + marker + "'\nexit 1\n");
            assertTrue(hook.toFile().setExecutable(true));
        }
        engine.clock.advance(Duration.ofMinutes(1));
        world.write("level.dat", bytes(4_096, 73));

        BackupResult second = engine.backupNow(world, BackupTrigger.MANUAL);
        BackupResult deleted = delete(first);

        assertEquals(SyncStatus.SYNCED, destination(second).syncStatus(), second.destinations().toString());
        assertEquals(BackupStatus.SUCCESS, deleted.status(), deleted.destinations().toString());
        assertFalse(Files.exists(marker));
    }

    @Test
    void aGameFolderReachedThroughALinkBacksUpAndRestores() throws Exception {
        Assumptions.assumeFalse(System.getProperty("os.name").startsWith("Windows"), "links need extra rights on Windows");
        Path linked = Files.createSymbolicLink(root.resolve("linked-game"), Files.createDirectories(root.resolve("real-game")));
        engine = start(linked, Engine.onlyDestination(DestinationType.GIT));
        TestWorld world = TestWorld.create(engine, "Linked");
        Map<String, byte[]> files = world.files();

        BackupResult result = engine.backupNow(world, BackupTrigger.MANUAL);

        assertEquals(BackupStatus.SUCCESS, result.status(), result.destinations().toString());
        assertSameFiles(files, restore(result.backupId(), "Linked restored"));
    }

    @Test
    void anImportSkipsBackupsWhoseIdentityTwoCommitsClaimAndKeepsTheRest() throws Exception {
        Path remote = bareRepository("shared.git");
        Path laptop = root.resolve("laptop");
        TestWorld world = TestWorld.create(laptop.resolve("saves"), "Shared");
        engine = start(laptop, gitOnly(world, remote));
        List<BackupId> backups = backupOnThreeMinutes(world);
        engine.close();
        String claimed = git(remote, "rev-parse", branch(remote, backups.get(0))).strip();
        String rival = commitTree(remote, claimed);
        git(remote, "update-ref", "refs/heads/backups/rival/" + backups.get(0), rival);

        engine = start(root.resolve("desktop"), Engine.onlyDestination(DestinationType.GIT));
        ImportPreview preview = await(engine.imports.previewGit(remote.toString()));
        engine.importAll(preview);

        assertNotEquals(claimed, rival);
        assertEquals(List.of(ImportDisposition.ADD, ImportDisposition.ADD), preview.items().stream()
                .map(item -> item.disposition()).toList());
        assertTrue(preview.issues().stream().anyMatch(issue -> issue.contains(backups.get(0).toString())), preview.issues().toString());
        assertTrue(engine.catalog.find(backups.get(0)).isEmpty());
        assertTrue(engine.catalog.find(backups.get(1)).isPresent());
        assertTrue(engine.catalog.find(backups.get(2)).isPresent());
    }

    private List<BackupId> backupOnThreeMinutes(TestWorld world) throws Exception {
        List<BackupId> backups = new ArrayList<>();
        for (int minute = 0; minute < 3; minute++) {
            if (minute > 0) {
                engine.clock.advance(Duration.ofMinutes(1));
                world.write("level.dat", bytes(4_096, 60 + minute));
            }
            BackupResult result = engine.backupNow(world, BackupTrigger.MANUAL);
            assertEquals(SyncStatus.SYNCED, destination(result).syncStatus(), result.destinations().toString());
            backups.add(result.backupId());
        }
        return backups;
    }

    private static WorldArchiveConfig gitOnly(TestWorld world, Path remote) {
        return Engine.onlyDestination(DestinationType.GIT,
                Engine.world(world.id(), world.path(), Optional.of(remote.toString()), StoragePolicy.defaults()));
    }

    private static Engine start(Path home, WorldArchiveConfig config) {
        return new Engine(home, new Engine.TestClock(Instant.parse("2026-09-01T12:00:00Z")), config, SourceCaptureObserver.NONE);
    }

    private Path restore(BackupId backupId, String name) throws Exception {
        return await(engine.recovery.restoreBackup(
                new RestoreBackupRequest(backupId, engine.saves, name), ProgressListener.NO_OP)).restoredWorldDirectory();
    }

    private BackupResult delete(BackupId backupId) throws Exception {
        return engine.delete(backupId, ProgressListener.NO_OP);
    }

    private DeleteBackupRequest request(BackupId backupId) throws Exception {
        return engine.confirmedDelete(backupId);
    }

    private static DestinationResult destination(BackupResult result) {
        return result.destinations().stream()
                .filter(destination -> destination.destination() == DestinationType.GIT)
                .findFirst()
                .orElseThrow();
    }

    private String commit(BackupId backupId, TestWorld world) throws Exception {
        return git(engine.git.repositoryFor(world.id()), "rev-parse", GitSnapshot.refName(world.id(), backupId)).strip();
    }

    private long lfsObjects(TestWorld world) throws IOException {
        try (Stream<Path> files = Files.walk(engine.git.repositoryFor(world.id()).resolve("lfs").resolve("objects"))) {
            return files.filter(Files::isRegularFile).count();
        }
    }

    private static Path lfsObject(Path repository, byte[] contents) {
        String sha256 = Digests.hex(Digests.sha256().digest(contents));
        return repository.resolve("lfs").resolve("objects")
                .resolve(sha256.substring(0, 2)).resolve(sha256.substring(2, 4)).resolve(sha256);
    }

    /**
     * Flips one byte in the middle of the object, which keeps its size, as when a disk sector goes
     * bad. The bytes go to a new file that replaces the object: Git LFS hard-links objects into a
     * remote on the same drive, and a remote elsewhere keeps its own intact copy.
     */
    private static void rot(Path object) throws IOException {
        byte[] rotten = Files.readAllBytes(object);
        rotten[rotten.length / 2] ^= 0x40;
        Path replacement = Files.write(object.resolveSibling("rotten"), rotten);
        Files.move(replacement, object, StandardCopyOption.REPLACE_EXISTING);
    }

    /** The same snapshot committed by someone else: same tree, message and time, another commit. */
    private static String commitTree(Path repository, String commit) throws Exception {
        String tree = git(repository, "rev-parse", commit + "^{tree}").strip();
        String time = "@" + git(repository, "log", "-1", "--format=%ct", commit).strip() + " +0000";
        ProcessBuilder builder = new ProcessBuilder("git", "--git-dir", repository.toString(), "commit-tree", tree)
                .redirectErrorStream(true);
        builder.environment().putAll(Map.of(
                "GIT_AUTHOR_NAME", "Someone Else", "GIT_AUTHOR_EMAIL", "someone@example.invalid",
                "GIT_COMMITTER_NAME", "Someone Else", "GIT_COMMITTER_EMAIL", "someone@example.invalid",
                "GIT_AUTHOR_DATE", time, "GIT_COMMITTER_DATE", time));
        Process process = builder.start();
        try (var input = process.getOutputStream()) {
            input.write(git(repository, "log", "-1", "--format=%B", commit).getBytes(StandardCharsets.UTF_8));
        }
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(process.waitFor(30, TimeUnit.SECONDS));
        assertEquals(0, process.exitValue(), output);
        return output.strip();
    }

    private static String branch(Path remote, BackupId backupId) throws Exception {
        return branches(remote).stream().filter(ref -> ref.endsWith(backupId.toString())).findFirst().orElseThrow();
    }

    private static List<String> branches(Path remote) throws Exception {
        return git(remote, "for-each-ref", "--format=%(refname)", "refs/heads/backups/").lines()
                .filter(line -> !line.isBlank())
                .toList();
    }

    private Path bareRepository(String name) throws Exception {
        Path repository = Files.createDirectories(root.resolve(name));
        git(repository, "init", "--bare", "--quiet");
        return repository;
    }

    private static String git(Path repository, String... arguments) throws Exception {
        List<String> command = new ArrayList<>(List.of("git", "--git-dir", repository.toString()));
        command.addAll(List.of(arguments));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (!process.waitFor(30, TimeUnit.SECONDS) || process.exitValue() != 0) {
            throw new IllegalStateException("git " + String.join(" ", arguments) + " failed: " + output);
        }
        return output;
    }

    private static void deleteTree(Path folder) throws IOException {
        try (Stream<Path> paths = Files.walk(folder)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(path);
            }
        }
    }
}
