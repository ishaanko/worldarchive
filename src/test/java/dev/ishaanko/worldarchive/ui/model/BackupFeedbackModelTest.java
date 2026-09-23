package dev.ishaanko.worldarchive.ui.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.ishaanko.worldarchive.model.BackupId;
import dev.ishaanko.worldarchive.model.BackupManifest;
import dev.ishaanko.worldarchive.model.BackupOperation;
import dev.ishaanko.worldarchive.model.BackupRecord;
import dev.ishaanko.worldarchive.model.BackupResult;
import dev.ishaanko.worldarchive.model.BackupStatus;
import dev.ishaanko.worldarchive.model.BackupTrigger;
import dev.ishaanko.worldarchive.model.DestinationResult;
import dev.ishaanko.worldarchive.model.DestinationType;
import dev.ishaanko.worldarchive.model.SyncStatus;
import dev.ishaanko.worldarchive.model.VerificationStatus;
import dev.ishaanko.worldarchive.model.WorldId;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class BackupFeedbackModelTest {
    private static final BackupId BACKUP_ID = BackupId.parse("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");

    private static final WorldId WORLD_ID = WorldId.parse("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");

    @Test
    void synchronizationReportsTheGitCopyOnly() {
        BackupOutcomeSummary synced = BackupOutcomeSummary.from(BackupOperation.SYNC, result(BACKUP_ID,
                DestinationResult.success(DestinationType.GIT, "git-object").withSync(SyncStatus.SYNCED),
                DestinationResult.success(DestinationType.ZIP, "zip-object")));
        assertEquals(BackupStatus.SUCCESS, synced.status());
        assertEquals("Backup synchronized", synced.headline());
        assertEquals(List.of("GIT: synced"), synced.lines());

        BackupOutcomeSummary failed = BackupOutcomeSummary.from(BackupOperation.SYNC, result(BACKUP_ID,
                DestinationResult.pendingSync(DestinationType.GIT, "git-object", "Remote rejected the update")
                        .withSync(SyncStatus.FAILED)));
        assertEquals(BackupStatus.FAILED, failed.status());
        assertEquals("Backup synchronization failed", failed.headline());
        assertEquals(List.of("GIT: sync failed · Remote rejected the update"), failed.lines());
    }

    @Test
    void verificationIsIncompleteWhileACopyCouldNotBeChecked() {
        BackupOutcomeSummary summary = BackupOutcomeSummary.from(BackupOperation.VERIFY, result(BACKUP_ID,
                DestinationResult.success(DestinationType.GIT, "git-object")
                        .withVerification(VerificationStatus.VERIFIED),
                DestinationResult.success(DestinationType.ZIP, "zip-object")
                        .withVerification(VerificationStatus.UNAVAILABLE)));

        assertEquals(BackupStatus.PARTIAL_SUCCESS, summary.status());
        assertEquals("Backup verification incomplete", summary.headline());
        assertEquals(List.of("GIT: verified", "ZIP: verification unavailable"), summary.lines());
    }

    @Test
    void aBatchDeleteCountsOnlyBackupsWithNoCopyLeftAndNamesTheRest() {
        BackupId removed = BackupId.parse("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa1");
        BackupId refused = BackupId.parse("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa2");
        BackupId pending = BackupId.parse("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa3");
        List<BackupRow> rows = List.of(row(removed, "Kept base"), row(refused, "Before the raid"), row(pending, "Farm"));
        BackupResult success = result(removed, DestinationResult.success(DestinationType.ZIP, "zip"));
        BackupResult partlyDeleted = result(refused,
                DestinationResult.success(DestinationType.ZIP, "zip"),
                DestinationResult.failed(DestinationType.GIT, "remote refused"));
        BackupResult remoteWaits = result(pending,
                DestinationResult.success(DestinationType.ZIP, "zip"),
                DestinationResult.pendingSync(DestinationType.GIT, "refs/heads/x", "deletion pending on the remote"));

        DeleteBatchSummary all = DeleteBatchSummary.from(List.of(success), rows);
        assertEquals(BackupStatus.SUCCESS, all.status());
        assertEquals("Deleted 1 backup", all.headline());
        assertTrue(all.details().isEmpty());

        DeleteBatchSummary partial = DeleteBatchSummary.from(List.of(success, partlyDeleted), rows);
        assertEquals(BackupStatus.PARTIAL_SUCCESS, partial.status());
        assertEquals("Deleted 1 of 2 backups", partial.headline());
        assertEquals(List.of(BackupText.name(rows.get(1)) + " · GIT: remote refused"), partial.details());

        DeleteBatchSummary none = DeleteBatchSummary.from(List.of(partlyDeleted, remoteWaits), rows);
        assertEquals(BackupStatus.FAILED, none.status());
        assertEquals("No backups were deleted", none.headline());
        assertEquals(2, none.details().size());
        assertTrue(none.details().get(1).startsWith(BackupText.name(rows.get(2))), none.details().toString());
    }

    private static BackupResult result(BackupId backupId, DestinationResult... destinations) {
        return new BackupResult(backupId, WORLD_ID, List.of(destinations), Instant.EPOCH.plusSeconds(2));
    }

    private static BackupRow row(BackupId backupId, String label) {
        BackupManifest manifest = BackupManifest.create(
                backupId,
                WORLD_ID,
                "Fixture World",
                Optional.of(label),
                Instant.EPOCH.plusSeconds(1),
                BackupTrigger.MANUAL,
                1,
                10,
                1,
                "a".repeat(64),
                "b".repeat(64),
                Optional.empty());
        return BackupRow.from(new BackupRecord(
                manifest,
                result(backupId, DestinationResult.success(DestinationType.ZIP, "archive.zip"))));
    }
}
