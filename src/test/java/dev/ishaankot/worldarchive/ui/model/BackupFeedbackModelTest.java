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
import dev.ishaankot.worldarchive.model.DestinationType;
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
