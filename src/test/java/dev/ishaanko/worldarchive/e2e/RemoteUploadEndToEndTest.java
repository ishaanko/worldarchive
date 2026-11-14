package dev.ishaanko.worldarchive.e2e;

import static dev.ishaanko.worldarchive.e2e.Engine.await;
import static dev.ishaanko.worldarchive.e2e.TestWorld.bytes;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.ishaanko.worldarchive.config.GitDestinationConfig;
import dev.ishaanko.worldarchive.config.RemoteUpload;
import dev.ishaanko.worldarchive.config.StoragePolicy;
import dev.ishaanko.worldarchive.config.WorldArchiveConfig;
import dev.ishaanko.worldarchive.core.SourceCaptureObserver;
import dev.ishaanko.worldarchive.model.BackupId;
import dev.ishaanko.worldarchive.model.BackupResult;
import dev.ishaanko.worldarchive.model.BackupStatus;
import dev.ishaanko.worldarchive.model.BackupTrigger;
import dev.ishaanko.worldarchive.model.DestinationResult;
import dev.ishaanko.worldarchive.model.DestinationStatus;
import dev.ishaanko.worldarchive.model.DestinationType;
import dev.ishaanko.worldarchive.model.ProgressListener;
import dev.ishaanko.worldarchive.model.SyncStatus;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The Upload to remote setting through the real engine, with a local bare repository as the remote. */
class RemoteUploadEndToEndTest {
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
    void onceADayUploadsTheFirstBackupOfEachDayAndSyncUploadsTheRest() throws Exception {
        Path remote = bareRepository("daily.git");
        TestWorld world = TestWorld.create(root.resolve("saves"), "Daily");
        engine = start(config(world, remote, RemoteUpload.ONCE_A_DAY));

        BackupResult morning = backup(world, 1);
        engine.clock.advance(Duration.ofHours(3));
        BackupResult afternoon = backup(world, 2);

        assertEquals(SyncStatus.SYNCED, git(morning).syncStatus());
        assertEquals(BackupStatus.SUCCESS, afternoon.status(), "a backup kept here is not a problem");
        assertEquals(DestinationStatus.SUCCESS, git(afternoon).status());
        assertEquals(Optional.empty(), git(afternoon).message());
        assertEquals(SyncStatus.NOT_SYNCED, listed(afternoon.backupId()).syncStatus());
        assertEquals(List.of(morning.backupId()), onRemote(remote));

        BackupResult synced = await(engine.recovery.syncBackup(afternoon.backupId(), ProgressListener.NO_OP));

        assertEquals(SyncStatus.SYNCED, git(synced).syncStatus());
        assertEquals(SyncStatus.SYNCED, listed(afternoon.backupId()).syncStatus());
        assertEquals(List.of(morning.backupId(), afternoon.backupId()), onRemote(remote));

        engine.clock.advance(Duration.ofDays(1));
        BackupResult nextDay = backup(world, 3);

        assertEquals(SyncStatus.SYNCED, git(nextDay).syncStatus());
        assertEquals(List.of(morning.backupId(), afternoon.backupId(), nextDay.backupId()), onRemote(remote));
    }

    @Test
    void onlyWithSyncNeverUploadsOnItsOwn() throws Exception {
        Path remote = bareRepository("manual.git");
        TestWorld world = TestWorld.create(root.resolve("saves"), "Manual");
        engine = start(config(world, remote, RemoteUpload.ONLY_WITH_SYNC));

        BackupResult first = backup(world, 1);
        engine.clock.advance(Duration.ofDays(1));
        BackupResult second = backup(world, 2);

        assertEquals(SyncStatus.NOT_SYNCED, listed(first.backupId()).syncStatus());
        assertEquals(SyncStatus.NOT_SYNCED, listed(second.backupId()).syncStatus());
        assertTrue(onRemote(remote).isEmpty());
    }

    @Test
    void deletingABackupThatNeverUploadedLeavesTheRemoteAsItWas() throws Exception {
        Path remote = bareRepository("kept.git");
        TestWorld world = TestWorld.create(root.resolve("saves"), "Kept");
        engine = start(config(world, remote, RemoteUpload.ONCE_A_DAY));
        BackupResult uploaded = backup(world, 1);
        BackupResult local = backup(world, 2);
        String remoteRefs = remoteGit(remote, "for-each-ref");

        BackupResult deleted = engine.delete(local.backupId(), ProgressListener.NO_OP);

        assertEquals(BackupStatus.SUCCESS, deleted.status(), deleted.destinations().toString());
        assertEquals(List.of(uploaded.backupId()), engine.records(world.id()).stream()
                .map(record -> record.manifest().backupId())
                .toList());
        assertEquals(remoteRefs, remoteGit(remote, "for-each-ref"));
    }

    private BackupResult backup(TestWorld world, int seed) throws Exception {
        world.write("region/r.0.0.mca", bytes(256 * 1_024, 100 + seed));
        return engine.backupNow(world, BackupTrigger.MANUAL);
    }

    private DestinationResult listed(BackupId backupId) throws Exception {
        return git(engine.catalog.find(backupId).orElseThrow().result());
    }

    private static DestinationResult git(BackupResult result) {
        return result.destinations().stream()
                .filter(destination -> destination.destination() == DestinationType.GIT)
                .findFirst()
                .orElseThrow();
    }

    private static WorldArchiveConfig config(TestWorld world, Path remote, RemoteUpload upload) {
        WorldArchiveConfig gitOnly = Engine.onlyDestination(DestinationType.GIT,
                Engine.world(world.id(), world.path(), Optional.of(remote.toString()), StoragePolicy.defaults()));
        GitDestinationConfig git = gitOnly.git();
        return gitOnly.withGit(new GitDestinationConfig(
                git.enabled(), git.repository(), git.remoteName(), git.triggers(), git.lfsPatterns(), upload));
    }

    private Engine start(WorldArchiveConfig config) {
        return new Engine(root, new Engine.TestClock(Instant.parse("2026-09-01T08:00:00Z")), config,
                SourceCaptureObserver.NONE);
    }

    /** The backups whose branches the remote holds, oldest first. */
    private static List<BackupId> onRemote(Path remote) throws Exception {
        return remoteGit(remote, "for-each-ref", "--sort=committerdate", "--format=%(refname)", "refs/heads/backups/")
                .lines()
                .filter(line -> !line.isBlank())
                .map(ref -> BackupId.parse(ref.substring(ref.length() - 36)))
                .toList();
    }

    private Path bareRepository(String name) throws Exception {
        Path repository = Files.createDirectories(root.resolve(name));
        remoteGit(repository, "init", "--bare", "--quiet");
        return repository;
    }

    private static String remoteGit(Path repository, String... arguments) throws Exception {
        List<String> command = new ArrayList<>(List.of("git", "--git-dir", repository.toString()));
        command.addAll(List.of(arguments));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (!process.waitFor(30, TimeUnit.SECONDS) || process.exitValue() != 0) {
            throw new IllegalStateException("git " + String.join(" ", arguments) + " failed: " + output);
        }
        return output;
    }
}
