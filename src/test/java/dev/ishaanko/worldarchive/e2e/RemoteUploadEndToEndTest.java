package dev.ishaanko.worldarchive.e2e;

import static dev.ishaanko.worldarchive.e2e.Engine.await;
import static dev.ishaanko.worldarchive.e2e.TestWorld.bytes;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
import dev.ishaanko.worldarchive.model.OperationPhase;
import dev.ishaanko.worldarchive.model.ProgressListener;
import dev.ishaanko.worldarchive.model.SyncStatus;
import dev.ishaanko.worldarchive.ui.model.BackupRow;
import dev.ishaanko.worldarchive.ui.model.BackupText;
import dev.ishaanko.worldarchive.ui.model.SyncBatch;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
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

    /** A newer backup that stayed here must not keep the remote's main behind the backups it has. */
    @Test
    void syncMovesMainToTheNewestBackupTheRemoteHolds() throws Exception {
        Path remote = bareRepository("main.git");
        TestWorld world = TestWorld.create(root.resolve("saves"), "Main");
        engine = start(config(world, remote, RemoteUpload.ONLY_WITH_SYNC));
        BackupResult older = backup(world, 1);
        engine.clock.advance(Duration.ofHours(1));
        BackupResult newer = backup(world, 2);

        await(engine.recovery.syncBackup(older.backupId(), ProgressListener.NO_OP));
        assertEquals(branchCommit(remote, older.backupId()), remoteGit(remote, "rev-parse", "refs/heads/main").strip());

        await(engine.recovery.syncBackup(newer.backupId(), ProgressListener.NO_OP));
        await(engine.recovery.syncBackup(older.backupId(), ProgressListener.NO_OP));
        assertEquals(branchCommit(remote, newer.backupId()), remoteGit(remote, "rev-parse", "refs/heads/main").strip());
    }

    @Test
    void syncingSeveralBackupsUploadsThemOldestFirstInOneOperation() throws Exception {
        Path remote = bareRepository("batch.git");
        TestWorld world = TestWorld.create(root.resolve("saves"), "Batch");
        engine = start(config(world, remote, RemoteUpload.ONLY_WITH_SYNC));
        List<BackupId> made = threeBackups(world);
        SyncBatch batch = selected(made.get(2), made.get(0), made.get(1));

        List<BackupResult> results = await(engine.recovery.syncBackups(
                world.id(), batch.backupIds(), ProgressListener.NO_OP));

        assertEquals(made, batch.backupIds());
        assertEquals(made, results.stream().map(BackupResult::backupId).toList());
        assertEquals(3, batch.summarize(results).uploaded());
        for (BackupId backupId : made) {
            assertEquals(SyncStatus.SYNCED, listed(backupId).syncStatus());
        }
        assertEquals(made, onRemote(remote));
        assertEquals(branchCommit(remote, made.get(2)), remoteGit(remote, "rev-parse", "refs/heads/main").strip());
    }

    /** The remote is away only while the middle backup uploads; the batch goes on without it. */
    @Test
    void aFailedUploadIsNamedAndTheRestOfTheBatchStillUploads() throws Exception {
        Path remote = bareRepository("flaky.git");
        Path away = root.resolve("flaky-away.git");
        TestWorld world = TestWorld.create(root.resolve("saves"), "Flaky");
        engine = start(config(world, remote, RemoteUpload.ONLY_WITH_SYNC));
        List<BackupId> made = threeBackups(world);
        SyncBatch batch = selected(made.toArray(BackupId[]::new));
        ProgressListener unplugMiddle = update -> {
            if (update.phase() != OperationPhase.PREPARING) {
                return;
            }
            try {
                if (update.backupId().equals(Optional.of(made.get(1)))) {
                    Files.move(remote, away);
                } else if (update.backupId().equals(Optional.of(made.get(2)))) {
                    Files.move(away, remote);
                }
            } catch (IOException failure) {
                throw new UncheckedIOException(failure);
            }
        };

        List<BackupResult> results = await(engine.recovery.syncBackups(world.id(), batch.backupIds(), unplugMiddle));

        SyncBatch.Summary summary = batch.summarize(results);
        assertEquals(BackupStatus.PARTIAL_SUCCESS, summary.status());
        assertEquals(2, summary.uploaded());
        assertEquals(1, summary.failures().size());
        assertEquals(BackupText.name(batch.rows().get(1)), summary.failures().getFirst().name());
        assertTrue(summary.failures().getFirst().reason().isPresent(), summary.toString());
        assertEquals(SyncStatus.SYNCED, listed(made.get(0)).syncStatus());
        assertTrue(listed(made.get(1)).syncStatus() != SyncStatus.SYNCED, listed(made.get(1)).toString());
        assertEquals(SyncStatus.SYNCED, listed(made.get(2)).syncStatus());
        assertEquals(List.of(made.get(0), made.get(2)), onRemote(remote));
    }

    @Test
    void cancelKeepsTheUploadsThatFinishedAndStartsNoMore() throws Exception {
        Path remote = bareRepository("cancel.git");
        TestWorld world = TestWorld.create(root.resolve("saves"), "Cancel");
        engine = start(config(world, remote, RemoteUpload.ONLY_WITH_SYNC));
        List<BackupId> made = threeBackups(world);
        AtomicReference<CompletableFuture<List<BackupResult>>> running = new AtomicReference<>();
        CountDownLatch started = new CountDownLatch(1);
        ProgressListener cancelAtMiddle = update -> {
            if (update.backupId().equals(Optional.of(made.get(1))) && update.phase() == OperationPhase.PREPARING) {
                awaitQuietly(started);
                running.get().cancel(true);
            }
        };

        running.set(engine.recovery.syncBackups(world.id(), made, cancelAtMiddle).toCompletableFuture());
        started.countDown();

        assertThrows(CancellationException.class, () -> running.get().join());
        assertEquals(SyncStatus.SYNCED, listed(made.get(0)).syncStatus());
        assertEquals(SyncStatus.NOT_SYNCED, listed(made.get(1)).syncStatus());
        assertEquals(SyncStatus.NOT_SYNCED, listed(made.get(2)).syncStatus());
        assertEquals(List.of(made.get(0)), onRemote(remote));
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await(30, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    /** Three backups an hour apart that stayed on this computer, oldest first. */
    private List<BackupId> threeBackups(TestWorld world) throws Exception {
        List<BackupId> made = new ArrayList<>();
        for (int seed = 1; seed <= 3; seed++) {
            BackupId backupId = backup(world, seed).backupId();
            assertEquals(SyncStatus.NOT_SYNCED, listed(backupId).syncStatus());
            made.add(backupId);
            engine.clock.advance(Duration.ofHours(1));
        }
        return made;
    }

    /** The browser's batch for these backups, selected in this order. */
    private SyncBatch selected(BackupId... backupIds) throws Exception {
        List<BackupRow> rows = new ArrayList<>();
        for (BackupId backupId : backupIds) {
            rows.add(BackupRow.from(engine.catalog.find(backupId).orElseThrow()));
        }
        return SyncBatch.of(rows);
    }

    /** A remote that was deleted and created again is empty, whatever the backup list still says. */
    @Test
    void syncUploadsAgainToARemoteThatWasReplaced() throws Exception {
        Path remote = bareRepository("replaced.git");
        TestWorld world = TestWorld.create(root.resolve("saves"), "Replaced");
        engine = start(config(world, remote, RemoteUpload.EVERY_BACKUP));
        BackupResult uploaded = backup(world, 1);
        assertEquals(SyncStatus.SYNCED, listed(uploaded.backupId()).syncStatus());
        try (Stream<Path> files = Files.walk(remote)) {
            for (Path file : files.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(file);
            }
        }
        bareRepository("replaced.git");

        BackupResult synced = await(engine.recovery.syncBackup(uploaded.backupId(), ProgressListener.NO_OP));

        assertEquals(SyncStatus.SYNCED, git(synced).syncStatus());
        assertEquals(List.of(uploaded.backupId()), onRemote(remote));
    }

    /** Cleanup can leave a backup only on the remote; Sync must neither fail it nor move main behind it. */
    @Test
    void syncLeavesABackupThatIsOnlyOnTheRemoteAsItIs() throws Exception {
        Path remote = bareRepository("remote-only.git");
        TestWorld world = TestWorld.create(root.resolve("saves"), "RemoteOnly");
        engine = start(config(world, remote, RemoteUpload.EVERY_BACKUP));
        BackupResult older = backup(world, 1);
        engine.clock.advance(Duration.ofHours(1));
        BackupResult newer = backup(world, 2);
        String newest = branchCommit(remote, newer.backupId());
        assertTrue(await(engine.git.deleteLocalSnapshot(world.id(), newer.backupId())));

        BackupResult remoteOnly = await(engine.recovery.syncBackup(newer.backupId(), ProgressListener.NO_OP));
        await(engine.recovery.syncBackup(older.backupId(), ProgressListener.NO_OP));

        assertEquals(SyncStatus.SYNCED, git(remoteOnly).syncStatus());
        assertEquals(DestinationStatus.SUCCESS, listed(newer.backupId()).status());
        assertEquals(SyncStatus.SYNCED, listed(newer.backupId()).syncStatus());
        assertEquals(newest, remoteGit(remote, "rev-parse", "refs/heads/main").strip());
    }

    @Test
    void aRebuiltBackupListShowsACopyTheRemoteLacksAsNotSynced() throws Exception {
        Path remote = bareRepository("rebuilt.git");
        TestWorld world = TestWorld.create(root.resolve("saves"), "Rebuilt");
        WorldArchiveConfig config = config(world, remote, RemoteUpload.ONCE_A_DAY);
        engine = start(config);
        BackupResult uploaded = backup(world, 1);
        BackupResult local = backup(world, 2);
        engine.close();
        Files.writeString(engine.catalogFile(), "not-json");

        engine = start(config);
        await(engine.imports.rebuildLocal());

        assertEquals(SyncStatus.SYNCED, listed(uploaded.backupId()).syncStatus());
        assertEquals(SyncStatus.NOT_SYNCED, listed(local.backupId()).syncStatus());
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

    private static String branchCommit(Path remote, BackupId backupId) throws Exception {
        return remoteGit(remote, "for-each-ref", "--format=%(objectname) %(refname)", "refs/heads/backups/")
                .lines()
                .filter(line -> line.endsWith(backupId.toString()))
                .map(line -> line.substring(0, line.indexOf(' ')))
                .findFirst()
                .orElseThrow();
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
