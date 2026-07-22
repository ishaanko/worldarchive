package dev.ishaankot.worldarchive.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.ishaankot.worldarchive.model.BackupId;
import dev.ishaankot.worldarchive.model.BackupResult;
import dev.ishaankot.worldarchive.model.DestinationResult;
import dev.ishaankot.worldarchive.model.DestinationType;
import dev.ishaankot.worldarchive.model.WorldId;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

final class BackgroundBackupWarningsTest {
    @Test
    void scheduledPartialAndFailedResultsProduceCredentialSafeWarnings() {
        BackupResult partial = result(List.of(
                DestinationResult.success(DestinationType.ZIP, "archive"),
                DestinationResult.failed(DestinationType.GIT, "https://user:secret@example.invalid")));
        BackupResult failed = result(List.of(
                DestinationResult.failed(DestinationType.GIT, "secret failure")));

        assertEquals(
                "Scheduled backup completed with warnings",
                BackgroundBackupWarnings.scheduled(partial, null).orElseThrow());
        assertEquals(
                "Scheduled backup failed",
                BackgroundBackupWarnings.scheduled(failed, null).orElseThrow());
        assertEquals(
                "World-exit backup completed with warnings",
                BackgroundBackupWarnings.worldExit(partial, null).orElseThrow());
        assertEquals(
                "World-exit backup failed",
                BackgroundBackupWarnings.worldExit(failed, null).orElseThrow());
    }

    @Test
    void successfulAndSkippedBackgroundResultsDoNotWarn() {
        BackupResult success = result(List.of(
                DestinationResult.success(DestinationType.ZIP, "archive")));
        BackupResult skipped = result(List.of());

        assertTrue(BackgroundBackupWarnings.scheduled(success, null).isEmpty());
        assertTrue(BackgroundBackupWarnings.scheduled(skipped, null).isEmpty());
        assertTrue(BackgroundBackupWarnings.worldExit(success, null).isEmpty());
        assertTrue(BackgroundBackupWarnings.worldExit(skipped, null).isEmpty());
    }

    @Test
    void worldExitNoticeConfirmsSaveAndReportsBackupOutcome() {
        BackupResult success = result(List.of(
                DestinationResult.success(DestinationType.ZIP, "archive")));
        BackupResult partial = result(List.of(
                DestinationResult.success(DestinationType.ZIP, "archive"),
                DestinationResult.failed(DestinationType.GIT, "sync failed")));
        BackupResult failed = result(List.of(
                DestinationResult.failed(DestinationType.GIT, "write failed")));
        BackupResult skipped = result(List.of());

        assertNotice(
                "Creating backup... Keep Minecraft open until it finishes.",
                BackgroundBackupWarnings.NoticeSeverity.SUCCESS,
                BackgroundBackupWarnings.worldExitStartedNotice());
        assertNotice(
                "Backup finished. You can safely quit Minecraft.",
                BackgroundBackupWarnings.NoticeSeverity.SUCCESS,
                BackgroundBackupWarnings.worldExitNotice(success, null));
        assertNotice(
                "Backup created with warnings",
                BackgroundBackupWarnings.NoticeSeverity.WARNING,
                BackgroundBackupWarnings.worldExitNotice(partial, null));
        assertNotice(
                "Backup failed; world was saved",
                BackgroundBackupWarnings.NoticeSeverity.ERROR,
                BackgroundBackupWarnings.worldExitNotice(failed, null));
        assertNotice(
                "Backup skipped; world was saved",
                BackgroundBackupWarnings.NoticeSeverity.WARNING,
                BackgroundBackupWarnings.worldExitNotice(skipped, null));
        assertNotice(
                "World save or backup did not complete",
                BackgroundBackupWarnings.NoticeSeverity.ERROR,
                BackgroundBackupWarnings.worldExitNotice(
                        null,
                        new IllegalStateException("save failed")));
    }

    private static void assertNotice(
            String message,
            BackgroundBackupWarnings.NoticeSeverity severity,
            BackgroundBackupWarnings.ExitNotice actual) {
        assertEquals(message, actual.message());
        assertEquals(severity, actual.severity());
    }

    private static BackupResult result(List<DestinationResult> destinations) {
        return BackupResult.aggregate(
                BackupId.create(),
                WorldId.create(),
                destinations,
                Instant.parse("2026-07-17T12:00:00Z"));
    }
}
