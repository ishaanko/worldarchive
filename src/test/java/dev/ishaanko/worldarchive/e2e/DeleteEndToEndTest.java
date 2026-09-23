package dev.ishaanko.worldarchive.e2e;

import static dev.ishaanko.worldarchive.e2e.Engine.await;
import static dev.ishaanko.worldarchive.e2e.TestWorld.bytes;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.ishaanko.worldarchive.config.StoragePolicy;
import dev.ishaanko.worldarchive.config.WorldArchiveConfig;
import dev.ishaanko.worldarchive.core.DeleteBackupRequest;
import dev.ishaanko.worldarchive.core.RestoreBackupRequest;
import dev.ishaanko.worldarchive.core.SourceCaptureObserver;
import dev.ishaanko.worldarchive.importing.ImportSummary;
import dev.ishaanko.worldarchive.model.BackupId;
import dev.ishaanko.worldarchive.model.BackupRecord;
import dev.ishaanko.worldarchive.model.BackupResult;
import dev.ishaanko.worldarchive.model.BackupStatus;
import dev.ishaanko.worldarchive.model.BackupTrigger;
import dev.ishaanko.worldarchive.model.DestinationResult;
import dev.ishaanko.worldarchive.model.DestinationStatus;
import dev.ishaanko.worldarchive.model.DestinationType;
import dev.ishaanko.worldarchive.model.OperationPhase;
import dev.ishaanko.worldarchive.model.OperationProgress;
import dev.ishaanko.worldarchive.model.ProgressListener;
import dev.ishaanko.worldarchive.model.SyncStatus;
import dev.ishaanko.worldarchive.ui.model.BackupRow;
import dev.ishaanko.worldarchive.ui.model.DeleteBatchSummary;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DeleteEndToEndTest {
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
    void deleteRemovesTheLocalCopiesAndTheRemoteCopy() throws Exception {
        Path remote = bareRepository("remote.git");
        TestWorld world = TestWorld.create(root.resolve("saves"), "Synced");
        engine = start(Engine.defaultConfig(
                Engine.world(world.id(), world.path(), Optional.of(remote.toString()), StoragePolicy.defaults())));
        BackupResult created = engine.backupNow(world, BackupTrigger.MANUAL);
        assertEquals(BackupStatus.SUCCESS, created.status());
        assertEquals(SyncStatus.SYNCED, destination(created, DestinationType.GIT).syncStatus());
        List<String> remoteRefs = refs(remote);
        assertFalse(remoteRefs.isEmpty());

        BackupResult deleted = delete(created.backupId());

        assertEquals(BackupStatus.SUCCESS, deleted.status());
        assertTrue(engine.records(world.id()).isEmpty());
        assertTrue(archives(engine.zipFolder(world.id())).isEmpty());
        assertTrue(snapshotRefs(remote, created.backupId()).isEmpty(), refs(remote).toString());
    }

    @Test
    void aRemoteThatRefusesTheDeleteFailsTheDeleteAndKeepsTheBackupListed() throws Exception {
        Path remote = bareRepository("refusing.git");
        TestWorld world = TestWorld.create(root.resolve("saves"), "Refused");
        engine = start(Engine.defaultConfig(
                Engine.world(world.id(), world.path(), Optional.of(remote.toString()), StoragePolicy.defaults())));
        BackupResult created = engine.backupNow(world, BackupTrigger.MANUAL);
        git(remote, "config", "receive.denyDeletes", "true");
        List<String> exposed = snapshotRefs(remote, created.backupId()).stream()
                .filter(ref -> !ref.equals("refs/heads/main"))
                .toList();
        assertFalse(exposed.isEmpty());

        BackupResult deleted = delete(created.backupId());

        assertNotEquals(BackupStatus.SUCCESS, deleted.status());
        assertEquals(DestinationStatus.FAILED, destination(deleted, DestinationType.GIT).status());
        assertTrue(refs(remote).containsAll(exposed), refs(remote).toString());
        List<BackupRecord> records = engine.records(world.id());
        assertEquals(1, records.size());
        assertTrue(records.getFirst().result().destinations().stream()
                .anyMatch(result -> result.destination() == DestinationType.GIT));
        // The player reads that the delete failed.
        DeleteBatchSummary batch = DeleteBatchSummary.from(List.of(deleted), List.of(BackupRow.from(records.getFirst())));
        assertEquals(BackupStatus.FAILED, batch.status());
        assertEquals("No backups were deleted", batch.headline());
    }

    @Test
    void aRefusedDeleteLeavesEveryRemoteRefWhereItWas() throws Exception {
        Path remote = bareRepository("untouched.git");
        TestWorld world = TestWorld.create(root.resolve("saves"), "Untouched");
        engine = start(Engine.defaultConfig(
                Engine.world(world.id(), world.path(), Optional.of(remote.toString()), StoragePolicy.defaults())));
        BackupId older = engine.backupNow(world, BackupTrigger.MANUAL).backupId();
        engine.clock.advance(Duration.ofMinutes(1));
        world.write("level.dat", bytes(4_096, 41));
        BackupId newest = engine.backupNow(world, BackupTrigger.MANUAL).backupId();
        git(remote, "config", "receive.denyDeletes", "true");
        String before = git(remote, "for-each-ref", "--format=%(refname) %(objectname)");

        BackupResult deleted = delete(newest);

        assertEquals(DestinationStatus.FAILED, destination(deleted, DestinationType.GIT).status());
        assertEquals(before, git(remote, "for-each-ref", "--format=%(refname) %(objectname)"));
        assertEquals(2, engine.records(world.id()).size());
        assertFalse(snapshotRefs(remote, older).isEmpty());
    }

    /**
     * A delete names the copies the player saw listed. A backup whose copies changed since then is
     * left alone; a fresh confirmation deletes it, and only it, once.
     */
    @Test
    void aConfirmationForABackupThatChangedDeletesNothing() throws Exception {
        engine = start(Engine.defaultConfig());
        TestWorld world = TestWorld.create(engine, "Confirmed");
        List<BackupId> backups = backups(world, 2);
        BackupId first = backups.getFirst();
        BackupRecord listed = engine.catalog.find(first).orElseThrow();
        // The list the player confirmed from showed only the ZIP copy; the catalog now also lists Git.
        DeleteBackupRequest stale = new DeleteBackupRequest(
                world.id(), first, List.of(destination(listed.result(), DestinationType.ZIP)));

        BackupResult refused = await(engine.recovery.deleteBackups(List.of(stale), ProgressListener.NO_OP)).getFirst();

        assertEquals(BackupStatus.FAILED, refused.status());
        String reason = destination(refused, DestinationType.ZIP).message().orElseThrow();
        assertTrue(reason.contains("changed after you confirmed"), reason);
        assertEquals(listed, engine.catalog.find(first).orElseThrow());
        assertEquals(4, archives(engine.zipFolder(world.id())).size());

        DeleteBackupRequest confirmed = engine.confirmedDelete(first);
        assertEquals(BackupStatus.SUCCESS, await(engine.recovery.deleteBackups(
                List.of(confirmed), ProgressListener.NO_OP)).getFirst().status());
        BackupResult again = await(engine.recovery.deleteBackups(List.of(confirmed), ProgressListener.NO_OP)).getFirst();

        assertEquals(BackupStatus.FAILED, again.status());
        assertEquals(List.of(backups.getLast()), backupIds(engine.records(world.id())));
        assertEquals(1, await(engine.git.listSnapshots(Optional.of(world.id()))).size());
    }

    /**
     * The player confirmed the delete while the backup waited for its upload, so the prompt named
     * no copy on the remote. The upload then finished: the delete stops and removes nothing there.
     */
    @Test
    void aConfirmationMadeBeforeASyncDoesNotDeleteTheRemoteCopy() throws Exception {
        Path remote = bareRepository("pending.git");
        TestWorld world = TestWorld.create(root.resolve("saves"), "Pending");
        engine = start(Engine.onlyDestination(DestinationType.GIT,
                Engine.world(world.id(), world.path(), Optional.of(remote.toString()), StoragePolicy.defaults())));
        Path away = Files.move(remote, root.resolve("remote-away.git"));
        BackupId backupId = engine.backupNow(world, BackupTrigger.MANUAL).backupId();
        assertEquals(SyncStatus.PENDING, destination(engine.catalog.find(backupId).orElseThrow().result(),
                DestinationType.GIT).syncStatus());
        DeleteBackupRequest confirmed = request(backupId);
        Files.move(away, remote);
        BackupResult synced = await(engine.recovery.syncBackup(backupId, ProgressListener.NO_OP));
        assertEquals(SyncStatus.SYNCED, destination(synced, DestinationType.GIT).syncStatus(), synced.toString());

        BackupResult refused = await(engine.recovery.deleteBackups(List.of(confirmed), ProgressListener.NO_OP)).getFirst();

        assertEquals(BackupStatus.FAILED, refused.status(), refused.toString());
        String reason = destination(refused, DestinationType.GIT).message().orElseThrow();
        assertTrue(reason.contains("changed after you confirmed"), reason);
        assertFalse(snapshotRefs(remote, backupId).isEmpty(), "the remote copy the player never saw is kept");
        assertTrue(engine.catalog.find(backupId).isPresent());
    }

    @Test
    void batchDeleteRemovesExactlyTheChosenBackupsAcrossWorlds() throws Exception {
        engine = start(Engine.defaultConfig());
        TestWorld north = TestWorld.create(engine, "North");
        TestWorld south = TestWorld.create(engine, "South");
        BackupId northOld = engine.backupNow(north, BackupTrigger.MANUAL).backupId();
        BackupId southOld = engine.backupNow(south, BackupTrigger.MANUAL).backupId();
        engine.clock.advance(Duration.ofMinutes(1));
        north.write("level.dat", bytes(4_096, 31));
        south.write("level.dat", bytes(4_096, 32));
        BackupId northNew = engine.backupNow(north, BackupTrigger.MANUAL).backupId();
        BackupId southNew = engine.backupNow(south, BackupTrigger.MANUAL).backupId();

        List<DeleteBackupRequest> requests = List.of(
                request(northOld), request(northNew), request(southOld));
        List<BackupResult> results = await(engine.recovery.deleteBackups(requests, ProgressListener.NO_OP));

        assertEquals(
                List.of(northOld, northNew, southOld),
                results.stream().map(BackupResult::backupId).toList());
        assertTrue(results.stream().allMatch(result -> result.status() == BackupStatus.SUCCESS));
        assertTrue(engine.records(north.id()).isEmpty());
        assertEquals(List.of(southNew), engine.records(south.id()).stream()
                .map(record -> record.manifest().backupId())
                .toList());
        List<Path> remaining = archives(engine.zipFolder(north.id()));
        assertEquals(2, remaining.size(), remaining.toString());
        assertTrue(remaining.stream().allMatch(file -> file.getFileName().toString().contains(southNew.toString())));
    }

    @Test
    void aBackupThatCouldNotReachTheRemoteIsPendingUntilSynced() throws Exception {
        Path remote = root.resolve("later.git");
        TestWorld world = TestWorld.create(root.resolve("saves"), "Offline");
        engine = start(Engine.defaultConfig(
                Engine.world(world.id(), world.path(), Optional.of(remote.toString()), StoragePolicy.defaults())));

        BackupResult created = engine.backupNow(world, BackupTrigger.MANUAL);
        assertEquals(BackupStatus.PARTIAL_SUCCESS, created.status());
        assertEquals(DestinationStatus.PENDING_SYNC, destination(created, DestinationType.GIT).status());

        bareRepository("later.git");
        BackupResult synced = await(engine.recovery.syncBackup(created.backupId(), ProgressListener.NO_OP));

        assertEquals(SyncStatus.SYNCED, destination(synced, DestinationType.GIT).syncStatus());
        assertFalse(snapshotRefs(remote, created.backupId()).isEmpty());
        assertEquals(BackupStatus.SUCCESS, engine.records(world.id()).getFirst().result().status());
    }

    @Test
    void aBatchDeleteWhileTheRemoteIsUnreachableKeepsEveryGitCopyAndARetryFinishes() throws Exception {
        Path remote = bareRepository("travelling.git");
        TestWorld world = TestWorld.create(root.resolve("saves"), "Travelling");
        engine = start(Engine.defaultConfig(
                Engine.world(world.id(), world.path(), Optional.of(remote.toString()), StoragePolicy.defaults())));
        List<BackupId> backups = backups(world, 3);
        Path unplugged = Files.move(remote, root.resolve("unplugged.git"));

        List<BackupResult> refused = await(engine.recovery.deleteBackups(requests(backups), ProgressListener.NO_OP));

        for (BackupResult result : refused) {
            assertEquals(BackupStatus.PARTIAL_SUCCESS, result.status());
            assertEquals(DestinationStatus.SUCCESS, destination(result, DestinationType.ZIP).status());
            String reason = destination(result, DestinationType.GIT).message().orElseThrow();
            assertTrue(reason.contains("could not be reached"), reason);
        }
        assertEquals(3, await(engine.git.listSnapshots(Optional.of(world.id()))).size());
        assertTrue(engine.records(world.id()).stream().allMatch(record -> record.result().destinations().stream()
                .map(DestinationResult::destination).toList().equals(List.of(DestinationType.GIT))));
        assertTrue(archives(engine.zipFolder(world.id())).isEmpty());
        Files.move(unplugged, remote);

        List<BackupResult> retried = await(engine.recovery.deleteBackups(requests(backups), ProgressListener.NO_OP));

        assertTrue(retried.stream().allMatch(result -> result.status() == BackupStatus.SUCCESS), retried.toString());
        assertTrue(engine.records(world.id()).isEmpty());
        for (BackupId backupId : backups) {
            assertTrue(snapshotRefs(remote, backupId).isEmpty(), refs(remote).toString());
        }
    }

    @Test
    void aBatchDeleteReportsEachBackupHonestlyWhenOneWorldsZipFolderIsReadOnly() throws Exception {
        Assumptions.assumeTrue(FileSystems.getDefault().supportedFileAttributeViews().contains("posix"));
        engine = start(Engine.onlyDestination(DestinationType.ZIP));
        TestWorld north = TestWorld.create(engine, "North");
        TestWorld south = TestWorld.create(engine, "South");
        Map<String, byte[]> southFiles = south.files();
        BackupId northOld = engine.backupNow(north, BackupTrigger.MANUAL).backupId();
        BackupId southOnly = engine.backupNow(south, BackupTrigger.MANUAL).backupId();
        engine.clock.advance(Duration.ofMinutes(1));
        north.write("level.dat", bytes(4_096, 33));
        BackupId northNew = engine.backupNow(north, BackupTrigger.MANUAL).backupId();
        Path southFolder = engine.zipFolder(south.id()).resolve(south.id().toString());
        Set<PosixFilePermission> writable = Files.getPosixFilePermissions(southFolder);
        List<BackupResult> results;
        Files.setPosixFilePermissions(southFolder, PosixFilePermissions.fromString("r-xr-xr-x"));
        try {
            Assumptions.assumeFalse(Files.isWritable(southFolder), "permissions do not apply to this user");
            results = await(engine.recovery.deleteBackups(
                    requests(List.of(northOld, southOnly, northNew)), ProgressListener.NO_OP));
        } finally {
            Files.setPosixFilePermissions(southFolder, writable);
        }

        assertEquals(List.of(BackupStatus.SUCCESS, BackupStatus.FAILED, BackupStatus.SUCCESS),
                results.stream().map(BackupResult::status).toList());
        String reason = destination(results.get(1), DestinationType.ZIP).message().orElseThrow();
        assertTrue(reason.contains("could not be deleted"), reason);
        assertTrue(engine.records(north.id()).isEmpty());
        assertEquals(List.of(southOnly), backupIds(engine.records(south.id())));
        TestWorld.assertSameFiles(southFiles, await(engine.recovery.restoreBackup(
                new RestoreBackupRequest(southOnly, engine.saves, "South kept"), ProgressListener.NO_OP))
                .restoredWorldDirectory());
    }

    @Test
    void aWorldWhoseBackupListCannotBeSavedFailsOnlyItsOwnBackupsAndTheBatchGoesOn() throws Exception {
        Assumptions.assumeTrue(FileSystems.getDefault().supportedFileAttributeViews().contains("posix"));
        engine = start(Engine.onlyDestination(DestinationType.ZIP));
        TestWorld north = TestWorld.create(engine, "North");
        TestWorld south = TestWorld.create(engine, "South");
        Map<String, byte[]> southFiles = south.files();
        BackupId northBackup = engine.backupNow(north, BackupTrigger.MANUAL).backupId();
        BackupId southBackup = engine.backupNow(south, BackupTrigger.MANUAL).backupId();
        Set<PosixFilePermission> writable = Files.getPosixFilePermissions(engine.storage);
        List<BackupResult> results;
        try {
            results = await(engine.recovery.deleteBackups(requests(List.of(northBackup, southBackup)), readOnlyWhen(
                    engine.storage,
                    progress -> progress.worldId().equals(south.id()) && progress.phase() == OperationPhase.PREPARING)));
            Assumptions.assumeFalse(Files.isWritable(engine.storage), "permissions do not apply to this user");
        } finally {
            Files.setPosixFilePermissions(engine.storage, writable);
        }

        assertEquals(List.of(BackupStatus.SUCCESS, BackupStatus.FAILED),
                results.stream().map(BackupResult::status).toList());
        String reason = destination(results.get(1), DestinationType.ZIP).message().orElseThrow();
        assertTrue(reason.startsWith("Nothing of this backup was deleted"), reason);
        assertTrue(engine.records(north.id()).isEmpty());
        assertEquals(List.of(southBackup), backupIds(engine.records(south.id())));
        TestWorld.assertSameFiles(southFiles, await(engine.recovery.restoreBackup(
                new RestoreBackupRequest(southBackup, engine.saves, "South kept"), ProgressListener.NO_OP))
                .restoredWorldDirectory());
    }

    @Test
    void copiesDeletedWhileTheBackupListCannotBeSavedAreReportedAndTheNextDeleteFinishes() throws Exception {
        Assumptions.assumeTrue(FileSystems.getDefault().supportedFileAttributeViews().contains("posix"));
        engine = start(Engine.onlyDestination(DestinationType.ZIP));
        TestWorld world = TestWorld.create(engine, "World");
        BackupId backupId = engine.backupNow(world, BackupTrigger.MANUAL).backupId();
        Set<PosixFilePermission> writable = Files.getPosixFilePermissions(engine.storage);
        BackupResult result;
        try {
            result = engine.delete(backupId,
                    readOnlyWhen(engine.storage, progress -> progress.phase() == OperationPhase.WRITING));
            Assumptions.assumeFalse(Files.isWritable(engine.storage), "permissions do not apply to this user");
        } finally {
            Files.setPosixFilePermissions(engine.storage, writable);
        }

        assertEquals(BackupStatus.FAILED, result.status());
        String reason = destination(result, DestinationType.ZIP).message().orElseThrow();
        assertTrue(reason.startsWith("Deleted, but the backup list could not be saved"), reason);
        assertEquals(List.of(backupId), backupIds(engine.records(world.id())));
        assertTrue(archives(engine.zipFolder(world.id())).isEmpty());
        assertEquals(BackupStatus.SUCCESS, delete(backupId).status());
        assertTrue(engine.records(world.id()).isEmpty());
    }

    @Test
    void aDeletedBackupStaysGoneAfterARestartEvenWhenACrashLeftItsArchiveBehind() throws Exception {
        engine = start(Engine.defaultConfig());
        TestWorld world = TestWorld.create(engine, "Gone");
        List<BackupId> backups = backups(world, 2);
        Path archive = archives(engine.zipFolder(world.id())).stream()
                .filter(file -> file.toString().endsWith(backups.getFirst() + ".zip"))
                .findFirst()
                .orElseThrow();
        byte[] leftover = Files.readAllBytes(archive);
        assertEquals(BackupStatus.SUCCESS, delete(backups.getFirst()).status());
        Files.write(archive, leftover);
        engine.close();

        engine = start(Engine.defaultConfig());
        ImportSummary rebuilt = await(engine.imports.rebuildLocal());

        assertEquals(0, rebuilt.added());
        assertEquals(List.of(backups.getLast()), backupIds(engine.records(world.id())));
        assertEquals(List.of(backups.getLast()), await(engine.git.listSnapshots(Optional.of(world.id()))).stream()
                .map(snapshot -> snapshot.backupId())
                .toList());
    }

    /** The drive that holds the chosen Git folder is unplugged: the delete fails, touches no copy, and creates nothing. */
    @Test
    void deletingWhileTheGitFolderIsUnpluggedFailsAndCreatesNothing() throws Exception {
        Path drive = Files.createDirectories(root.resolve("usb"));
        Path gitFolder = Files.createDirectories(drive.resolve("Git backups"));
        WorldArchiveConfig gitOnly = Engine.onlyDestination(DestinationType.GIT);
        engine = start(gitOnly.withGit(gitOnly.git().withRepository(Optional.of(gitFolder))));
        TestWorld world = TestWorld.create(engine, "Travelling");
        BackupId backupId = backups(world, 1).getFirst();
        Path unplugged = Files.move(drive, root.resolve("unplugged-drive"));

        BackupResult refused = delete(backupId);

        assertEquals(BackupStatus.FAILED, refused.status(), refused.toString());
        String reason = destination(refused, DestinationType.GIT).message().orElseThrow();
        assertTrue(reason.contains("cannot be reached"), reason);
        assertFalse(Files.exists(drive), "nothing was created where the drive was");
        Files.move(unplugged, drive);
        assertEquals(1, await(engine.git.listSnapshots(Optional.of(world.id()))).size());
        assertEquals(BackupStatus.SUCCESS, delete(backupId).status());
    }

    /**
     * The player removed a world's repository from WorldArchive's default Git folder by hand. Its
     * backups can still be deleted: nothing is left here, and the copy on the remote goes too.
     */
    @Test
    void aRepositoryRemovedByHandFromTheDefaultFolderIsStillDeletedEverywhere() throws Exception {
        Path remote = bareRepository("kept-remote.git");
        TestWorld world = TestWorld.create(root.resolve("saves"), "Tidied");
        engine = start(Engine.onlyDestination(DestinationType.GIT,
                Engine.world(world.id(), world.path(), Optional.of(remote.toString()), StoragePolicy.defaults())));
        BackupId backupId = backups(world, 1).getFirst();
        assertFalse(snapshotRefs(remote, backupId).isEmpty());
        deleteTree(engine.git.repositoryFor(world.id()));

        BackupResult deleted = delete(backupId);

        assertEquals(BackupStatus.SUCCESS, deleted.status(), deleted.toString());
        assertEquals(List.of(), backupIds(engine.records(world.id())));
        assertEquals(List.of(), snapshotRefs(remote, backupId));
    }

    /** The player removed a world's folder from WorldArchive's default ZIP folder by hand: its backups leave the list. */
    @Test
    void aZipFolderRemovedByHandFromTheDefaultFolderLetsItsBackupsBeDeleted() throws Exception {
        engine = start(Engine.onlyDestination(DestinationType.ZIP));
        TestWorld world = TestWorld.create(engine, "Tidied");
        BackupId backupId = backups(world, 1).getFirst();
        Path worldFolder = engine.zipFolder(world.id()).resolve(world.id().toString());
        assertTrue(Files.isDirectory(worldFolder));
        deleteTree(worldFolder);

        BackupResult deleted = delete(backupId);

        assertEquals(BackupStatus.SUCCESS, deleted.status(), deleted.toString());
        assertEquals(List.of(), backupIds(engine.records(world.id())));
        assertFalse(Files.exists(worldFolder), "nothing was created in place of the removed folder");
    }

    /**
     * A crash left the archive of a deleted backup on a USB drive, and the drive was away when the
     * game started again. That rebuild could not see the drive, so the backup stays deleted once
     * the drive is back.
     */
    @Test
    void aDeletedBackupsArchiveOnADriveThatWasAwayAtARestartIsNotListedAgain() throws Exception {
        Path drive = Files.createDirectories(root.resolve("usb"));
        Path zips = Files.createDirectories(drive.resolve("Backups"));
        WorldArchiveConfig zipOnly = Engine.onlyDestination(DestinationType.ZIP);
        WorldArchiveConfig config = zipOnly.withZip(zipOnly.zip().withDestination(Optional.of(zips)));
        engine = start(config);
        TestWorld world = TestWorld.create(engine, "Travelling");
        BackupId backupId = backups(world, 1).getFirst();
        Path archive = archives(zips).stream().filter(file -> file.toString().endsWith(".zip")).findFirst().orElseThrow();
        byte[] leftover = Files.readAllBytes(archive);
        assertEquals(BackupStatus.SUCCESS, delete(backupId).status());
        Files.write(archive, leftover);
        engine.close();
        Path unplugged = Files.move(drive, root.resolve("unplugged"));
        engine = start(config);
        await(engine.imports.rebuildLocal());
        engine.close();
        Files.move(unplugged, drive);

        engine = start(config);
        await(engine.imports.rebuildLocal());

        assertEquals(List.of(), backupIds(engine.records(world.id())));
    }

    @Test
    void aSyncedBackupWhoseOnlyCopyIsOnARemoteThatLeftTheSettingsStaysListed() throws Exception {
        Path remote = bareRepository("former.git");
        TestWorld world = TestWorld.create(root.resolve("saves"), "Moved");
        engine = start(Engine.onlyDestination(DestinationType.GIT,
                Engine.world(world.id(), world.path(), Optional.of(remote.toString()), StoragePolicy.defaults())));
        List<BackupId> backups = backups(world, 2);
        BackupId withLocalCopy = backups.getFirst();
        BackupId remoteOnly = backups.getLast();
        assertTrue(await(engine.git.deleteLocalSnapshot(world.id(), remoteOnly)), "cleanup left only the remote copy");
        engine.close();
        engine = start(Engine.onlyDestination(DestinationType.GIT,
                Engine.world(world.id(), world.path(), Optional.empty(), StoragePolicy.defaults())));

        List<BackupResult> results = await(engine.recovery.deleteBackups(
                requests(List.of(withLocalCopy, remoteOnly)), ProgressListener.NO_OP));

        DestinationResult deletedHere = destination(results.getFirst(), DestinationType.GIT);
        assertEquals(DestinationStatus.SUCCESS, deletedHere.status());
        assertTrue(deletedHere.message().orElseThrow().contains("was not deleted there"), deletedHere.toString());
        DestinationResult kept = destination(results.getLast(), DestinationType.GIT);
        assertEquals(DestinationStatus.FAILED, kept.status());
        assertTrue(kept.message().orElseThrow().contains("Add the remote again"), kept.toString());
        assertEquals(List.of(remoteOnly), backupIds(engine.records(world.id())));
        assertFalse(snapshotRefs(remote, withLocalCopy).isEmpty());
        assertFalse(snapshotRefs(remote, remoteOnly).isEmpty());
    }

    /** Backs up the world the given number of times, a minute apart, changing it each time. */
    private List<BackupId> backups(TestWorld world, int count) throws Exception {
        List<BackupId> backups = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            engine.clock.advance(Duration.ofMinutes(1));
            world.write("level.dat", bytes(4_096, 500 + index));
            BackupResult result = engine.backupNow(world, BackupTrigger.MANUAL);
            assertEquals(BackupStatus.SUCCESS, result.status(), result.destinations().toString());
            backups.add(result.backupId());
        }
        return backups;
    }

    private List<DeleteBackupRequest> requests(List<BackupId> backups) throws Exception {
        List<DeleteBackupRequest> requests = new ArrayList<>();
        for (BackupId backupId : backups) {
            requests.add(request(backupId));
        }
        return requests;
    }

    private static List<BackupId> backupIds(List<BackupRecord> records) {
        return records.stream().map(record -> record.manifest().backupId()).toList();
    }

    private Engine start(WorldArchiveConfig config) {
        return new Engine(
                root,
                new Engine.TestClock(Instant.parse("2026-09-01T12:00:00Z")),
                config,
                SourceCaptureObserver.NONE);
    }

    private BackupResult delete(BackupId backupId) throws Exception {
        return engine.delete(backupId, ProgressListener.NO_OP);
    }

    private DeleteBackupRequest request(BackupId backupId) throws Exception {
        return engine.confirmedDelete(backupId);
    }

    /** A progress listener that makes the folder read-only when a matching step is reported. */
    private static ProgressListener readOnlyWhen(Path folder, Predicate<OperationProgress> step) {
        return progress -> {
            if (step.test(progress)) {
                try {
                    Files.setPosixFilePermissions(folder, PosixFilePermissions.fromString("r-xr-xr-x"));
                } catch (IOException exception) {
                    throw new UncheckedIOException(exception);
                }
            }
        };
    }

    private static DestinationResult destination(BackupResult result, DestinationType type) {
        return result.destinations().stream()
                .filter(destination -> destination.destination() == type)
                .findFirst()
                .orElseThrow();
    }

    private Path bareRepository(String name) throws Exception {
        Path repository = root.resolve(name);
        Files.createDirectories(repository);
        git(repository, "init", "--bare", "--quiet");
        return repository;
    }

    /** Remote refs whose snapshot commit message names this backup. */
    private static List<String> snapshotRefs(Path repository, BackupId backupId) throws Exception {
        return refs(repository).stream()
                .filter(ref -> {
                    try {
                        return git(repository, "log", "-1", "--format=%B", ref).contains(backupId.toString());
                    } catch (Exception exception) {
                        throw new IllegalStateException(exception);
                    }
                })
                .toList();
    }

    private static List<String> refs(Path repository) throws Exception {
        return git(repository, "for-each-ref", "--format=%(refname)").lines()
                .filter(line -> !line.isBlank())
                .toList();
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

    /** ZIP archives and checksum files under a ZIP folder; lock files are not backup copies. */
    private static List<Path> archives(Path folder) throws IOException {
        if (!Files.exists(folder)) {
            return List.of();
        }
        try (Stream<Path> files = Files.walk(folder)) {
            return files.filter(file -> file.toString().endsWith(".zip") || file.toString().endsWith(".sha256"))
                    .toList();
        }
    }

    private static void deleteTree(Path folder) throws IOException {
        try (Stream<Path> paths = Files.walk(folder)) {
            for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.delete(path);
            }
        }
    }
}
