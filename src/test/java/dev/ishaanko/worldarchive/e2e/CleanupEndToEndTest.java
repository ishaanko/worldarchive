package dev.ishaanko.worldarchive.e2e;

import static dev.ishaanko.worldarchive.e2e.Engine.await;
import static dev.ishaanko.worldarchive.e2e.TestWorld.assertSameFiles;
import static dev.ishaanko.worldarchive.e2e.TestWorld.bytes;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.ishaanko.worldarchive.config.StoragePolicy;
import dev.ishaanko.worldarchive.config.WorldArchiveConfig;
import dev.ishaanko.worldarchive.core.RestoreBackupRequest;
import dev.ishaanko.worldarchive.core.SourceCaptureObserver;
import dev.ishaanko.worldarchive.model.BackupId;
import dev.ishaanko.worldarchive.model.BackupRecord;
import dev.ishaanko.worldarchive.model.BackupResult;
import dev.ishaanko.worldarchive.model.BackupTrigger;
import dev.ishaanko.worldarchive.model.DestinationType;
import dev.ishaanko.worldarchive.model.ProgressListener;
import dev.ishaanko.worldarchive.model.VerificationStatus;
import dev.ishaanko.worldarchive.storage.management.CleanupItem;
import dev.ishaanko.worldarchive.storage.management.CleanupPlan;
import dev.ishaanko.worldarchive.storage.management.CleanupRequest;
import dev.ishaanko.worldarchive.storage.management.CleanupResult;
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
import java.util.concurrent.ExecutionException;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CleanupEndToEndTest {
    /** Over budget from the first backup, and only the newest day is kept by the calendar. */
    private static final StoragePolicy TIGHT = new StoragePolicy(1_024, 1, 0, 0);

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
    void cleanupKeepsLabeledBackupsAndDeletesOnlyWhatWasSelected() throws Exception {
        TestWorld world = TestWorld.create(root.resolve("saves"), "Crowded");
        engine = start(Engine.defaultConfig(Engine.world(world.id(), world.path(), Optional.empty(), TIGHT)));
        Map<String, byte[]> labeledFiles = world.files();
        List<BackupId> days = backupOnFourDays(world, Optional.of("Base"));
        BackupId labeled = days.get(0);

        CleanupPlan plan = await(engine.cleanup.prepareCleanup(world.id()));

        Map<BackupId, CleanupItem> items = plan.items().stream()
                .collect(Collectors.toMap(CleanupItem::backupId, Function.identity()));
        assertTrue(plan.protectedBackups().containsAll(List.of(labeled, days.get(3))));
        for (BackupId unprotected : List.of(days.get(1), days.get(2))) {
            assertTrue(items.get(unprotected).removeZip() && items.get(unprotected).removeGit());
        }
        for (BackupId kept : plan.protectedBackups()) {
            assertTrue(!items.containsKey(kept) || !items.get(kept).removeZip(), kept.toString());
        }
        CleanupResult result = await(engine.cleanup.applyCleanup(
                new CleanupRequest(plan.confirmationToken(), Set.of(days.get(1)))));

        assertTrue(result.failures().isEmpty(), result.failures().toString());
        assertEquals(Set.of(labeled, days.get(2), days.get(3)), backupIds(world));
        assertSameFiles(labeledFiles, await(engine.recovery.restoreBackup(
                new RestoreBackupRequest(labeled, engine.saves, "Crowded base"),
                ProgressListener.NO_OP)).restoredWorldDirectory());
    }

    /**
     * Verify found the ZIP of a labeled backup damaged, so its Git copy is the last intact one: no
     * item of the plan deletes it, and after the whole plan the backup still restores.
     */
    @Test
    void theGitCopyOfALabeledBackupWhoseZipIsDamagedIsNeverOffered() throws Exception {
        TestWorld world = TestWorld.create(root.resolve("saves"), "Labeled");
        engine = start(Engine.defaultConfig(Engine.world(world.id(), world.path(), Optional.empty(), TIGHT)));
        Map<String, byte[]> labeledFiles = world.files();
        BackupId labeled = backupOnFourDays(world, Optional.of("Base")).getFirst();
        Path archive = archiveOf(world, labeled);
        byte[] damaged = Files.readAllBytes(archive);
        damaged[damaged.length / 2] ^= 0x10;
        Files.write(archive, damaged);
        BackupResult verified = await(engine.recovery.verifyBackup(labeled, ProgressListener.NO_OP));
        assertEquals(VerificationStatus.FAILED, verified.destinations().stream()
                .filter(copy -> copy.destination() == DestinationType.ZIP)
                .findFirst().orElseThrow().verificationStatus(), verified.destinations().toString());

        CleanupPlan plan = await(engine.cleanup.prepareCleanup(world.id()));
        assertTrue(plan.items().stream().noneMatch(item -> item.backupId().equals(labeled) && item.removeGit()),
                plan.items().toString());
        await(engine.cleanup.applyCleanup(new CleanupRequest(plan.confirmationToken(),
                Set.copyOf(plan.items().stream().map(CleanupItem::backupId).toList()))));

        assertSameFiles(labeledFiles, await(engine.recovery.restoreBackup(
                new RestoreBackupRequest(labeled, engine.saves, "Labeled base"),
                ProgressListener.NO_OP)).restoredWorldDirectory());
    }

    @Test
    void cleanupLeavesTheRemoteCopyAndKeepsTheBackupListed() throws Exception {
        Path remote = root.resolve("remote.git");
        Files.createDirectories(remote);
        new ProcessBuilder("git", "init", "--bare", "--quiet", remote.toString()).start().waitFor();
        TestWorld world = TestWorld.create(root.resolve("saves"), "Mirrored");
        engine = start(Engine.defaultConfig(
                Engine.world(world.id(), world.path(), Optional.of(remote.toString()), TIGHT)));
        Map<String, byte[]> oldest = world.files();
        List<BackupId> days = backupOnFourDays(world, Optional.empty());

        CleanupPlan plan = await(engine.cleanup.prepareCleanup(world.id()));
        assertTrue(plan.items().stream().anyMatch(item -> item.backupId().equals(days.get(0))));
        CleanupResult result = await(engine.cleanup.applyCleanup(
                new CleanupRequest(plan.confirmationToken(), Set.of(days.get(0)))));

        assertTrue(result.failures().isEmpty(), result.failures().toString());
        BackupRecord remoteOnly = engine.catalog.find(days.get(0)).orElseThrow();
        assertEquals(
                List.of(DestinationType.GIT),
                remoteOnly.result().destinations().stream().map(destination -> destination.destination()).toList());
        assertSameFiles(oldest, await(engine.recovery.restoreBackup(
                new RestoreBackupRequest(days.get(0), engine.saves, "Mirrored first"),
                ProgressListener.NO_OP)).restoredWorldDirectory());
    }

    @Test
    void aRemoteThatCannotBeReachedKeepsEveryCopyOfTheBackupsThatRelyOnIt() throws Exception {
        Path remote = root.resolve("remote.git");
        Files.createDirectories(remote);
        new ProcessBuilder("git", "init", "--bare", "--quiet", remote.toString()).start().waitFor();
        TestWorld world = TestWorld.create(root.resolve("saves"), "Offline");
        engine = start(Engine.defaultConfig(
                Engine.world(world.id(), world.path(), Optional.of(remote.toString()), TIGHT)));
        List<BackupId> days = backupOnFourDays(world, Optional.empty());
        CleanupPlan plan = await(engine.cleanup.prepareCleanup(world.id()));
        BackupRecord oldest = engine.catalog.find(days.getFirst()).orElseThrow();
        Files.move(remote, root.resolve("unplugged.git"));

        CleanupResult result = await(engine.cleanup.applyCleanup(
                new CleanupRequest(plan.confirmationToken(), Set.of(days.getFirst()))));

        String reason = result.failures().getOrDefault(days.getFirst(), "");
        assertTrue(reason.contains("could not be reached"), result.failures().toString());
        assertEquals(oldest, engine.catalog.find(days.getFirst()).orElseThrow());
        assertEquals(4, zipArchives(world));
    }

    @Test
    void aSelectionThatSplitsTheProtectedGitCopiesDeletesNothing() throws Exception {
        TestWorld world = TestWorld.create(root.resolve("saves"), "Grouped");
        engine = start(Engine.defaultConfig(Engine.world(world.id(), world.path(), Optional.empty(), TIGHT)));
        backupOnFourDays(world, Optional.of("Base"));
        CleanupPlan plan = await(engine.cleanup.prepareCleanup(world.id()));
        List<BackupId> protectedGit = plan.items().stream()
                .filter(item -> item.removeGit() && plan.protectedBackups().contains(item.backupId()))
                .map(CleanupItem::backupId)
                .toList();
        assertTrue(protectedGit.size() > 1, plan.items().toString());
        List<BackupRecord> before = engine.records(world.id());

        ExecutionException refused = assertThrows(ExecutionException.class, () -> await(engine.cleanup.applyCleanup(
                new CleanupRequest(plan.confirmationToken(), Set.of(protectedGit.getFirst())))));

        assertTrue(refused.getCause().getMessage().contains("every protected backup"), refused.toString());
        assertEquals(before, engine.records(world.id()));
        assertEquals(4, await(engine.git.listSnapshots(Optional.of(world.id()))).size());
        assertEquals(4, zipArchives(world));
    }

    @Test
    void aPreviewThatWentStaleDeletesNothing() throws Exception {
        TestWorld world = TestWorld.create(root.resolve("saves"), "Stale");
        engine = start(Engine.defaultConfig(Engine.world(world.id(), world.path(), Optional.empty(), TIGHT)));
        List<BackupId> days = backupOnFourDays(world, Optional.empty());
        CleanupPlan plan = await(engine.cleanup.prepareCleanup(world.id()));
        assertFalse(plan.items().isEmpty());
        engine.clock.advance(Duration.ofMinutes(1));
        world.write("level.dat", bytes(4_096, 77));
        BackupId newest = engine.backupNow(world, BackupTrigger.MANUAL).backupId();

        assertThrows(ExecutionException.class, () -> await(engine.cleanup.applyCleanup(new CleanupRequest(
                plan.confirmationToken(),
                Set.copyOf(plan.items().stream().map(CleanupItem::backupId).toList())))));

        List<BackupId> expected = new ArrayList<>(days);
        expected.add(newest);
        assertEquals(Set.copyOf(expected), backupIds(world));
    }

    @Test
    void cleanupNeverLeavesARecordWhoseCopiesAreAllGone() throws Exception {
        TestWorld world = TestWorld.create(root.resolve("saves"), "Phantom");
        engine = start(Engine.defaultConfig(Engine.world(world.id(), world.path(), Optional.empty(), TIGHT)));
        List<BackupId> days = backupOnFourDays(world, Optional.empty());
        BackupId oldest = days.getFirst();
        try (Stream<Path> files = Files.walk(engine.zipFolder(world.id()))) {
            for (Path file : files.filter(path -> path.getFileName().toString().contains(oldest.toString())).toList()) {
                Files.delete(file);
            }
        }

        CleanupPlan plan = await(engine.cleanup.prepareCleanup(world.id()));
        CleanupItem item = plan.items().stream()
                .filter(candidate -> candidate.backupId().equals(oldest))
                .findFirst()
                .orElseThrow();
        assertTrue(item.removesRestorePoint(), "the preview must not promise another copy");
        CleanupResult result = await(engine.cleanup.applyCleanup(
                new CleanupRequest(plan.confirmationToken(), Set.of(oldest))));

        assertTrue(result.failures().isEmpty(), result.failures().toString());
        assertTrue(engine.catalog.find(oldest).isEmpty(), "a backup with no copy left must leave the catalog");
    }

    @Test
    void aZipArchiveThatCannotBeDeletedKeepsItsBackupListedAndTheRestIsCleaned() throws Exception {
        Assumptions.assumeTrue(FileSystems.getDefault().supportedFileAttributeViews().contains("posix"));
        TestWorld world = TestWorld.create(root.resolve("saves"), "Sticky");
        engine = start(Engine.defaultConfig(Engine.world(world.id(), world.path(), Optional.empty(), TIGHT)));
        Map<String, byte[]> first = world.files();
        List<BackupId> days = backupOnFourDays(world, Optional.empty());
        CleanupPlan plan = await(engine.cleanup.prepareCleanup(world.id()));
        Set<BackupId> selected = Set.copyOf(plan.items().stream().map(CleanupItem::backupId).toList());
        Path folder = engine.zipFolder(world.id()).resolve(world.id().toString());
        Set<PosixFilePermission> writable = Files.getPosixFilePermissions(folder);
        CleanupResult result;
        Files.setPosixFilePermissions(folder, PosixFilePermissions.fromString("r-xr-xr-x"));
        try {
            Assumptions.assumeFalse(Files.isWritable(folder), "permissions do not apply to this user");
            result = await(engine.cleanup.applyCleanup(new CleanupRequest(plan.confirmationToken(), selected)));
        } finally {
            Files.setPosixFilePermissions(folder, writable);
        }

        assertEquals(Set.copyOf(plan.items().stream().filter(CleanupItem::removeZip).map(CleanupItem::backupId).toList()),
                result.failures().keySet());
        for (BackupId backupId : selected) {
            assertEquals(List.of(DestinationType.ZIP), engine.catalog.find(backupId).orElseThrow()
                    .result().destinations().stream().map(destination -> destination.destination()).toList());
        }
        assertEquals(Set.copyOf(days), backupIds(world));
        assertSameFiles(first, await(engine.recovery.restoreBackup(
                new RestoreBackupRequest(days.getFirst(), engine.saves, "Sticky first"),
                ProgressListener.NO_OP)).restoredWorldDirectory());
    }

    /**
     * The remote keeps the oldest backup listed, so cleanup marks nothing deleted. Its ZIP archive
     * cannot be deleted, and the deleted-backup list cannot be changed either: the archive is
     * listed again all the same.
     */
    @Test
    void aZipArchiveThatCannotBeDeletedIsListedAgainWhenTheDeletedBackupListCannotBeChanged() throws Exception {
        Assumptions.assumeTrue(FileSystems.getDefault().supportedFileAttributeViews().contains("posix"));
        Path remote = root.resolve("remote.git");
        Files.createDirectories(remote);
        new ProcessBuilder("git", "init", "--bare", "--quiet", remote.toString()).start().waitFor();
        TestWorld world = TestWorld.create(root.resolve("saves"), "Stubborn");
        engine = start(Engine.defaultConfig(
                Engine.world(world.id(), world.path(), Optional.of(remote.toString()), TIGHT)));
        BackupId oldest = backupOnFourDays(world, Optional.empty()).getFirst();
        CleanupPlan plan = await(engine.cleanup.prepareCleanup(world.id()));
        Files.createDirectories(engine.catalogFile().resolveSibling("deleted-backups.txt.lock"));
        Path folder = engine.zipFolder(world.id()).resolve(world.id().toString());
        Set<PosixFilePermission> writable = Files.getPosixFilePermissions(folder);
        CleanupResult result;
        Files.setPosixFilePermissions(folder, PosixFilePermissions.fromString("r-xr-xr-x"));
        try {
            Assumptions.assumeFalse(Files.isWritable(folder), "permissions do not apply to this user");
            result = await(engine.cleanup.applyCleanup(new CleanupRequest(plan.confirmationToken(), Set.of(oldest))));
        } finally {
            Files.setPosixFilePermissions(folder, writable);
        }

        assertEquals(Set.of(oldest), result.failures().keySet());
        assertEquals(Set.of(DestinationType.GIT, DestinationType.ZIP), engine.catalog.find(oldest).orElseThrow()
                .result().destinations().stream().map(destination -> destination.destination()).collect(Collectors.toSet()));
    }

    /** Backs up on four consecutive days, changing the world each day, and verifies the newest. */
    private List<BackupId> backupOnFourDays(TestWorld world, Optional<String> firstLabel) throws Exception {
        List<BackupId> backups = new ArrayList<>();
        for (int day = 0; day < 4; day++) {
            if (day > 0) {
                engine.clock.advance(Duration.ofDays(1));
                world.write("level.dat", bytes(4_096, 100 + day));
            }
            Optional<String> label = day == 0 ? firstLabel : Optional.empty();
            backups.add(await(engine.backup(world, BackupTrigger.MANUAL, label)).backupId());
        }
        await(engine.recovery.verifyBackup(backups.getLast(), ProgressListener.NO_OP));
        return backups;
    }

    private Path archiveOf(TestWorld world, BackupId backupId) throws Exception {
        try (Stream<Path> files = Files.walk(engine.zipFolder(world.id()))) {
            return files.filter(path -> path.getFileName().toString().endsWith(backupId + ".zip"))
                    .findFirst()
                    .orElseThrow();
        }
    }

    private long zipArchives(TestWorld world) throws Exception {
        try (Stream<Path> files = Files.walk(engine.zipFolder(world.id()))) {
            return files.filter(path -> path.getFileName().toString().endsWith(".zip")).count();
        }
    }

    private Set<BackupId> backupIds(TestWorld world) throws Exception {
        return Set.copyOf(engine.records(world.id()).stream()
                .map(record -> record.manifest().backupId())
                .toList());
    }

    private Engine start(WorldArchiveConfig config) {
        return new Engine(
                root,
                new Engine.TestClock(Instant.parse("2026-09-01T12:00:00Z")),
                config,
                SourceCaptureObserver.NONE);
    }
}
