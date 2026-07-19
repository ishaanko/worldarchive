package dev.ishaankot.worldarchive.ui.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.ishaankot.worldarchive.core.BackupOperation;
import dev.ishaankot.worldarchive.core.OperationId;
import dev.ishaankot.worldarchive.core.OperationPhase;
import dev.ishaankot.worldarchive.core.OperationProgress;
import dev.ishaankot.worldarchive.model.BackupId;
import dev.ishaankot.worldarchive.model.BackupManifest;
import dev.ishaankot.worldarchive.model.BackupRecord;
import dev.ishaankot.worldarchive.model.BackupResult;
import dev.ishaankot.worldarchive.model.BackupStatus;
import dev.ishaankot.worldarchive.model.BackupTrigger;
import dev.ishaankot.worldarchive.model.DestinationResult;
import dev.ishaankot.worldarchive.model.DestinationStatus;
import dev.ishaankot.worldarchive.model.DestinationType;
import dev.ishaankot.worldarchive.model.SyncStatus;
import dev.ishaankot.worldarchive.model.VerificationStatus;
import dev.ishaankot.worldarchive.model.WorldId;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class BackupFeedbackModelTest {
    private static final BackupId BACKUP_ID = new BackupId(
            UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"));

    private static final WorldId WORLD_ID = new WorldId(
            UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"));

    @Test
    void retainsAndRedactsPartialDestinationFailures() {
        BackupResult result = BackupResult.aggregate(
                BACKUP_ID,
                WORLD_ID,
                List.of(
                        DestinationResult.success(DestinationType.GIT, "git-object"),
                        DestinationResult.failed(
                                DestinationType.ZIP,
                                "Upload failed token=secret-value")),
                Instant.EPOCH.plusSeconds(2));

        BackupOutcomeSummary summary = BackupOutcomeSummary.from(result);

        assertEquals(BackupStatus.PARTIAL_SUCCESS, summary.status());
        assertTrue(summary.partialFailure());
        assertEquals("Backup completed with destination issues", summary.headline());
        assertEquals(2, summary.destinations().size());
        assertEquals(
                "Upload failed token=[REDACTED]",
                summary.destinations().get(1).detail().orElseThrow());
    }

    @Test
    void deletionSummaryUsesDeletionSpecificLanguage() {
        BackupResult deleted = BackupResult.aggregate(
                BACKUP_ID,
                WORLD_ID,
                List.of(
                        DestinationResult.success(DestinationType.GIT, "git-object"),
                        DestinationResult.success(DestinationType.ZIP, "zip-object")),
                Instant.EPOCH.plusSeconds(2));
        BackupOutcomeSummary success = BackupOutcomeSummary.from(
                BackupOperation.DELETE,
                deleted);

        assertEquals(BackupOperation.DELETE, success.operation());
        assertEquals("Backup deleted", success.headline());
        assertEquals("deleted", success.destinationStatus(success.destinations().get(0)));
        assertEquals("deleted", success.destinationStatus(success.destinations().get(1)));

        BackupResult partialDelete = BackupResult.aggregate(
                BACKUP_ID,
                WORLD_ID,
                List.of(
                        DestinationResult.success(DestinationType.GIT, "git-object"),
                        DestinationResult.failed(DestinationType.ZIP, "Delete failed")),
                Instant.EPOCH.plusSeconds(3));
        BackupOutcomeSummary partial = BackupOutcomeSummary.from(
                BackupOperation.DELETE,
                partialDelete);

        assertEquals("Backup deleted from some destinations", partial.headline());
        assertEquals("not deleted", partial.destinationStatus(partial.destinations().get(1)));

        BackupOutcomeSummary empty = BackupOutcomeSummary.from(
                BackupOperation.DELETE,
                BackupResult.aggregate(BACKUP_ID, WORLD_ID, List.of(), Instant.EPOCH.plusSeconds(4)));
        assertEquals("Backup removed", empty.headline());
    }

    @Test
    void synchronizationSummaryUsesRemoteSyncOutcome() {
        DestinationResult git = new DestinationResult(
                DestinationType.GIT,
                DestinationStatus.SUCCESS,
                Optional.of("git-object"),
                Optional.empty(),
                VerificationStatus.NOT_VERIFIED,
                SyncStatus.SYNCED);
        BackupResult result = BackupResult.aggregate(
                BACKUP_ID,
                WORLD_ID,
                List.of(
                        git,
                        DestinationResult.success(DestinationType.ZIP, "zip-object")),
                Instant.EPOCH.plusSeconds(2));

        BackupOutcomeSummary synchronizedSummary = BackupOutcomeSummary.from(
                BackupOperation.SYNC,
                result);

        assertEquals(BackupStatus.SUCCESS, synchronizedSummary.status());
        assertEquals("Backup synchronized", synchronizedSummary.headline());
        assertEquals(1, synchronizedSummary.destinations().size());
        assertEquals(
                "synced",
                synchronizedSummary.destinationStatus(synchronizedSummary.destinations().getFirst()));

        DestinationResult failedGit = new DestinationResult(
                DestinationType.GIT,
                DestinationStatus.PENDING_SYNC,
                Optional.of("git-object"),
                Optional.of("Remote rejected the update"),
                VerificationStatus.NOT_VERIFIED,
                SyncStatus.FAILED);
        BackupOutcomeSummary failed = BackupOutcomeSummary.from(
                BackupOperation.SYNC,
                BackupResult.aggregate(
                        BACKUP_ID,
                        WORLD_ID,
                        List.of(failedGit),
                        Instant.EPOCH.plusSeconds(3)));

        assertEquals(BackupStatus.FAILED, failed.status());
        assertEquals("Backup synchronization failed", failed.headline());
        assertEquals("sync failed", failed.destinationStatus(failed.destinations().getFirst()));
    }

    @Test
    void verificationSummaryUsesIntegrityOutcome() {
        DestinationResult verifiedGit = new DestinationResult(
                DestinationType.GIT,
                DestinationStatus.SUCCESS,
                Optional.of("git-object"),
                Optional.empty(),
                VerificationStatus.VERIFIED,
                SyncStatus.NOT_CONFIGURED);
        DestinationResult unavailableZip = new DestinationResult(
                DestinationType.ZIP,
                DestinationStatus.SUCCESS,
                Optional.of("zip-object"),
                Optional.empty(),
                VerificationStatus.UNAVAILABLE,
                SyncStatus.NOT_CONFIGURED);

        BackupOutcomeSummary summary = BackupOutcomeSummary.from(
                BackupOperation.VERIFY,
                BackupResult.aggregate(
                        BACKUP_ID,
                        WORLD_ID,
                        List.of(verifiedGit, unavailableZip),
                        Instant.EPOCH.plusSeconds(2)));

        assertEquals(BackupStatus.PARTIAL_SUCCESS, summary.status());
        assertEquals("Backup verification incomplete", summary.headline());
        assertEquals("verified", summary.destinationStatus(summary.destinations().get(0)));
        assertEquals(
                "verification unavailable",
                summary.destinationStatus(summary.destinations().get(1)));
    }

    @Test
    void confirmationCarriesDeleteAndRestoreIntent() {
        BackupRow row = BackupRow.from(record());

        ConfirmationState delete = ConfirmationState.delete(row);
        ConfirmationState restore = ConfirmationState.restore(row, RestoreChoice.PLAY);

        assertEquals(ConfirmationKind.DELETE, delete.kind());
        assertTrue(delete.destructive());
        assertTrue(delete.restoreChoice().isEmpty());
        assertEquals(ConfirmationKind.RESTORE, restore.kind());
        assertFalse(restore.destructive());
        assertEquals(Optional.of(RestoreChoice.PLAY), restore.restoreChoice());
    }

    @Test
    void progressIsCredentialSafeAndRendererNeutral() {
        OperationProgress progress = new OperationProgress(
                new OperationId(UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc")),
                WORLD_ID,
                Optional.of(BACKUP_ID),
                BackupOperation.CREATE,
                OperationPhase.WRITING,
                1,
                4,
                "Pushing with token=secret-value");

        ProgressState state = ProgressState.from(progress);

        assertEquals(0.25, state.fraction().orElseThrow());
        assertEquals("Pushing with token=[REDACTED]", state.message());
        assertFalse(state.terminal());
        assertFalse(state.successful());

        OperationProgress completed = new OperationProgress(
                progress.operationId(),
                WORLD_ID,
                Optional.of(BACKUP_ID),
                BackupOperation.CREATE,
                OperationPhase.COMPLETE,
                4,
                4,
                "Complete");
        assertTrue(ProgressState.from(completed).terminal());
        assertTrue(ProgressState.from(completed).successful());
    }

    private static BackupRecord record() {
        Instant createdAt = Instant.EPOCH.plusSeconds(1);
        BackupManifest manifest = BackupManifest.create(
                BACKUP_ID,
                WORLD_ID,
                "Fixture World",
                Optional.empty(),
                createdAt,
                BackupTrigger.MANUAL,
                1,
                10,
                1,
                "a".repeat(64),
                "b".repeat(64));
        BackupResult result = BackupResult.aggregate(
                BACKUP_ID,
                WORLD_ID,
                List.of(DestinationResult.success(DestinationType.ZIP, "archive.zip")),
                createdAt.plusSeconds(1));
        return new BackupRecord(manifest, result);
    }
}
