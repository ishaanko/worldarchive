package dev.ishaanko.worldarchive.ui.model;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.ishaanko.worldarchive.model.BackupId;
import dev.ishaanko.worldarchive.model.BackupManifest;
import dev.ishaanko.worldarchive.model.BackupOperation;
import dev.ishaanko.worldarchive.model.BackupRecord;
import dev.ishaanko.worldarchive.model.BackupResult;
import dev.ishaanko.worldarchive.model.BackupStatus;
import dev.ishaanko.worldarchive.model.BackupTrigger;
import dev.ishaanko.worldarchive.model.DestinationResult;
import dev.ishaanko.worldarchive.model.DestinationType;
import dev.ishaanko.worldarchive.model.OperationId;
import dev.ishaanko.worldarchive.model.OperationPhase;
import dev.ishaanko.worldarchive.model.OperationProgress;
import dev.ishaanko.worldarchive.model.SyncStatus;
import dev.ishaanko.worldarchive.model.WorldId;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class SyncBatchTest {
    private static final WorldId WORLD_ID = WorldId.parse("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");

    private final BackupRow oldest = row("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa3", "Oldest", 1);

    private final BackupRow middle = row("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa1", "Middle", 2);

    private final BackupRow newest = row("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa2", "Newest", 3);

    @Test
    void uploadsOldestFirstAndCountsProgressOverTheWholeBatch() {
        SyncBatch batch = SyncBatch.of(List.of(newest, oldest, middle));

        assertEquals(List.of(oldest.backupId(), middle.backupId(), newest.backupId()), batch.backupIds());

        OperationProgress second = batch.inBatch(progress(middle.backupId(), 1, 2, "Uploading"),
                        position -> position + " of 3: Uploading")
                .orElseThrow();
        assertEquals("2 of 3: Uploading", second.message());
        assertEquals(0.5, second.fraction().orElseThrow());
        assertEquals(Optional.empty(), batch.inBatch(progress(BackupId.create(), 0, 1, "Other"), String::valueOf));
    }

    @Test
    void theSummaryCountsOnlyBackupsNowOnTheRemoteAndNamesEveryOtherOne() {
        SyncBatch batch = SyncBatch.of(List.of(oldest, middle, newest));
        BackupResult uploaded = result(oldest, DestinationResult.success(DestinationType.GIT, "git")
                .withSync(SyncStatus.SYNCED));
        BackupResult rejected = result(middle, DestinationResult.pendingSync(DestinationType.GIT, "git",
                "The remote is unreachable").withSync(SyncStatus.FAILED));
        BackupResult gone = result(newest, DestinationResult.failed(DestinationType.GIT, "It left the backup list"));

        SyncBatch.Summary partial = batch.summarize(List.of(uploaded, rejected, gone));
        assertEquals(BackupStatus.PARTIAL_SUCCESS, partial.status());
        assertEquals(1, partial.uploaded());
        assertEquals(3, partial.total());
        assertEquals(List.of(
                new SyncBatch.Failure(BackupText.name(middle), Optional.of("The remote is unreachable")),
                new SyncBatch.Failure(BackupText.name(newest), Optional.of("It left the backup list"))),
                partial.failures());

        assertEquals(BackupStatus.SUCCESS, batch.summarize(List.of(uploaded)).status());
        assertEquals(BackupStatus.FAILED, batch.summarize(List.of(rejected, gone)).status());
    }

    private static OperationProgress progress(BackupId backupId, long completed, long total, String message) {
        return new OperationProgress(OperationId.create(), WORLD_ID, Optional.of(backupId), BackupOperation.SYNC,
                OperationPhase.PREPARING, completed, total, message);
    }

    private static BackupResult result(BackupRow row, DestinationResult git) {
        return new BackupResult(row.backupId(), WORLD_ID, List.of(git), row.createdAt().plusSeconds(10));
    }

    private static BackupRow row(String backupId, String label, long epochSecond) {
        BackupId id = BackupId.parse(backupId);
        Instant createdAt = Instant.ofEpochSecond(epochSecond);
        BackupManifest manifest = BackupManifest.create(id, WORLD_ID, "My Base", Optional.of(label), createdAt,
                BackupTrigger.MANUAL, 1, 10, 1, "a".repeat(64), "b".repeat(64), Optional.empty());
        return BackupRow.from(new BackupRecord(manifest, new BackupResult(id, WORLD_ID,
                List.of(DestinationResult.success(DestinationType.GIT, "git")), createdAt.plusSeconds(1))));
    }
}
