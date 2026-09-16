package dev.ishaanko.worldarchive.storage.management;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.ishaanko.worldarchive.model.BackupId;
import dev.ishaanko.worldarchive.model.BackupManifest;
import dev.ishaanko.worldarchive.model.BackupRecord;
import dev.ishaanko.worldarchive.model.BackupResult;
import dev.ishaanko.worldarchive.model.BackupTrigger;
import dev.ishaanko.worldarchive.model.DestinationResult;
import dev.ishaanko.worldarchive.model.DestinationType;
import dev.ishaanko.worldarchive.model.WorldId;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

final class ManagedStorageSupportTest {
    private static final WorldId WORLD_ID = WorldId.create();

    @Test
    void onlyADestinationWithAnArtifactCountsAsManaged() {
        BackupRecord failed = record(DestinationResult.failed(DestinationType.ZIP, "disk full"));
        BackupRecord written = record(DestinationResult.success(DestinationType.ZIP, WORLD_ID + "/archive.zip"));

        assertFalse(ManagedStorageSupport.managedDestination(failed, DestinationType.ZIP));
        assertTrue(ManagedStorageSupport.managedDestination(written, DestinationType.ZIP));
        assertFalse(ManagedStorageSupport.ownGitSnapshot(
                record(DestinationResult.failed(DestinationType.GIT, "git missing"))));
    }

    private static BackupRecord record(DestinationResult destination) {
        BackupId backupId = BackupId.create();
        Instant created = Instant.parse("2026-09-15T10:00:00Z");
        BackupManifest manifest = BackupManifest.create(
                backupId,
                WORLD_ID,
                "Support Test World",
                Optional.empty(),
                created,
                BackupTrigger.MANUAL,
                1,
                100,
                0,
                "a".repeat(64),
                "b".repeat(64));
        return new BackupRecord(
                manifest,
                BackupResult.aggregate(backupId, WORLD_ID, List.of(destination), created.plusSeconds(1)));
    }
}
