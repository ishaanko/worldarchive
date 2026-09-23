package dev.ishaanko.worldarchive.storage.zip;

import static dev.ishaanko.worldarchive.storage.zip.ZipTestFixtures.CREATED_AT;
import static dev.ishaanko.worldarchive.storage.zip.ZipTestFixtures.bytes;
import static dev.ishaanko.worldarchive.storage.zip.ZipTestFixtures.capture;
import static dev.ishaanko.worldarchive.storage.zip.ZipTestFixtures.files;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.ishaanko.worldarchive.config.FolderOrigin;
import dev.ishaanko.worldarchive.core.BackupCapture;
import dev.ishaanko.worldarchive.core.TestCaptures;
import dev.ishaanko.worldarchive.core.WorldInventory;
import dev.ishaanko.worldarchive.model.DestinationResult;
import dev.ishaanko.worldarchive.model.DestinationStatus;
import dev.ishaanko.worldarchive.model.OperationPhase;
import dev.ishaanko.worldarchive.model.OperationProgress;
import dev.ishaanko.worldarchive.model.VerificationStatus;
import dev.ishaanko.worldarchive.model.WorldId;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ZipBackupBackendTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void aFailingProgressListenerCannotFailTheBackup() throws Exception {
        Path world = ZipTestFixtures.world(temporaryDirectory.resolve("world"), files("level.dat", "world data"));
        List<OperationPhase> reported = new CopyOnWriteArrayList<>();
        ZipBackupBackend backend = new ZipBackupBackend(worldId -> store("archives"));

        DestinationResult result = backend.createBackup(capture(world, WorldId.create(), CREATED_AT), progress -> {
            reported.add(progress.phase());
            throw new IllegalStateException("listener failed");
        });

        assertEquals(DestinationStatus.SUCCESS, result.status());
        assertEquals(VerificationStatus.VERIFIED, result.verificationStatus());
        assertTrue(result.artifactId().isPresent());
        assertEquals(OperationPhase.COMPLETE, reported.getLast());
    }

    @Test
    void writingProgressIsReportedOncePerPercent() throws Exception {
        Path world = ZipTestFixtures.world(
                temporaryDirectory.resolve("world"), Map.of("region/r.0.0.mca", bytes(8 * 1_024 * 1_024, 1)));
        List<OperationProgress> writing = new CopyOnWriteArrayList<>();
        ZipBackupBackend backend = new ZipBackupBackend(worldId -> store("archives"));

        backend.createBackup(capture(world, WorldId.create(), CREATED_AT), progress -> {
            if (progress.phase() == OperationPhase.WRITING) {
                writing.add(progress);
            }
        });

        assertTrue(writing.size() > 10 && writing.size() <= 101, "events: " + writing.size());
        assertEquals(writing.getLast().totalUnits(), writing.getLast().completedUnits());
        for (int index = 1; index < writing.size(); index++) {
            assertTrue(writing.get(index).completedUnits() > writing.get(index - 1).completedUnits());
        }
    }

    @Test
    void aCaptureThatDoesNotMatchItsManifestFailsWithAPlainMessage() throws Exception {
        Path world = ZipTestFixtures.world(temporaryDirectory.resolve("world"), files("level.dat", "unexpected file"));
        BackupCapture capture = capture(world, WorldId.create(), CREATED_AT);
        BackupCapture stale = new BackupCapture(world, capture.manifest(), WorldInventory.create(List.of()));
        ZipBackupBackend backend = new ZipBackupBackend(worldId -> store("archives"));

        DestinationResult result = backend.createBackup(stale, progress -> {
        });

        assertEquals(DestinationStatus.FAILED, result.status());
        assertEquals(
                "The prepared copy of the world does not match its backup manifest. Try the backup again.",
                result.message().orElseThrow());
        assertFalse(Files.exists(temporaryDirectory.resolve("archives")));
    }

    @Test
    void eachWorldIsWrittenToItsOwnZipFolder() throws Exception {
        Path world = ZipTestFixtures.world(temporaryDirectory.resolve("world"), files("level.dat", "routed"));
        WorldId routed = WorldId.create();
        ZipBackupBackend backend = new ZipBackupBackend(
                worldId -> store(worldId.equals(routed) ? "override-archives" : "default-archives"));

        DestinationResult result = backend.createBackup(
                TestCaptures.of(world, capture(world, routed, CREATED_AT).manifest()), progress -> {
                });

        assertEquals(DestinationStatus.SUCCESS, result.status());
        assertTrue(Files.isDirectory(temporaryDirectory.resolve("override-archives").resolve(routed.toString())));
        assertFalse(Files.exists(temporaryDirectory.resolve("default-archives")));
    }

    private ZipBackupStore store(String name) {
        return new ZipBackupStore(temporaryDirectory.resolve(name), FolderOrigin.DEFAULT);
    }
}
