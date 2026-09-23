package dev.ishaanko.worldarchive.runtime;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.ishaanko.worldarchive.model.BackupId;
import dev.ishaanko.worldarchive.model.BackupManifest;
import dev.ishaanko.worldarchive.model.BackupRecord;
import dev.ishaanko.worldarchive.model.BackupResult;
import dev.ishaanko.worldarchive.model.BackupTrigger;
import dev.ishaanko.worldarchive.model.DestinationResult;
import dev.ishaanko.worldarchive.model.DestinationType;
import dev.ishaanko.worldarchive.model.WorldId;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class RuntimeDestinationPathGuardTest {
    private final WorldId north = WorldId.create();

    private final WorldId south = WorldId.create();

    @TempDir
    Path folder;

    @Test
    void theGitFolderStaysWhileItHoldsAListedCopy() {
        RuntimeStoragePaths current = paths("old.git", "archives", Map.of());
        RuntimeStoragePaths moved = paths("new.git", "archives", Map.of());

        assertThrows(IllegalArgumentException.class, () -> RuntimeDestinationPathGuard.requireAllowed(
                current, moved, List.of(record(north, DestinationResult.success(DestinationType.GIT, "ref")))));
        assertThrows(IllegalArgumentException.class, () -> RuntimeDestinationPathGuard.requireAllowed(
                current, moved, List.of(record(north, DestinationResult.pendingSync(
                        DestinationType.GIT, "ref", "The upload waits for the remote")))));
        assertDoesNotThrow(() -> RuntimeDestinationPathGuard.requireAllowed(
                current, moved, List.of(record(north, DestinationResult.failed(DestinationType.GIT, "No Git")))));
    }

    /** A world with its own ZIP folder keeps its backups there, so only that folder is held in place for it. */
    @Test
    void eachZipFolderStaysWhileItHoldsAListedCopyOfItsWorlds() {
        RuntimeStoragePaths current = paths("repo.git", "shared", Map.of(south, folder.resolve("south")));
        List<BackupRecord> records = List.of(
                record(north, DestinationResult.success(DestinationType.ZIP, north + "/north.zip")),
                record(south, DestinationResult.success(DestinationType.ZIP, south + "/south.zip")));

        assertThrows(IllegalArgumentException.class, () -> RuntimeDestinationPathGuard.requireAllowed(
                current, paths("repo.git", "shared", Map.of(south, folder.resolve("elsewhere"))), records));
        assertThrows(IllegalArgumentException.class, () -> RuntimeDestinationPathGuard.requireAllowed(
                current, paths("repo.git", "moved", Map.of(south, folder.resolve("south"))), records));
        assertDoesNotThrow(() -> RuntimeDestinationPathGuard.requireAllowed(
                current, paths("repo.git", "moved", Map.of(south, folder.resolve("south"))), records.subList(1, 2)));
    }

    private RuntimeStoragePaths paths(String git, String zip, Map<WorldId, Path> worldZip) {
        return new RuntimeStoragePaths(folder.resolve(git), folder.resolve(zip), worldZip);
    }

    private static BackupRecord record(WorldId worldId, DestinationResult destination) {
        Instant createdAt = Instant.parse("2026-09-01T12:00:00Z");
        String digest = "0".repeat(64);
        BackupManifest manifest = new BackupManifest(BackupManifest.CURRENT_FORMAT_VERSION, BackupId.create(), worldId,
                "World", Optional.empty(), createdAt, BackupTrigger.MANUAL, 1, 1, 1, digest, digest, Optional.empty());
        return new BackupRecord(manifest, new BackupResult(manifest.backupId(), worldId, List.of(destination), createdAt));
    }
}
