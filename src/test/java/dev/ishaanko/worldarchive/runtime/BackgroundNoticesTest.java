package dev.ishaanko.worldarchive.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.ishaanko.worldarchive.model.BackupId;
import dev.ishaanko.worldarchive.model.BackupResult;
import dev.ishaanko.worldarchive.model.DestinationResult;
import dev.ishaanko.worldarchive.model.DestinationType;
import dev.ishaanko.worldarchive.model.WorldId;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;
import org.junit.jupiter.api.Test;

final class BackgroundNoticesTest {
    private static final BackupResult SUCCESS = result(DestinationResult.success(DestinationType.ZIP, "archive"));

    private static final BackupResult PARTIAL = result(
            DestinationResult.success(DestinationType.ZIP, "archive"),
            DestinationResult.failed(DestinationType.GIT, "The remote refused the push"));

    private static final BackupResult FAILED = result(DestinationResult.failed(DestinationType.GIT, "Disk full"));

    private static final BackupResult SKIPPED = result();

    @Test
    void eachOutcomeHasOneSeverityAndOneText() {
        assertNotice(Notice.Severity.SUCCESS, "exit_done", BackgroundNotices.exitOutcome(SUCCESS, null));
        assertNotice(Notice.Severity.WARNING, "exit_warnings", BackgroundNotices.exitOutcome(PARTIAL, null));
        assertNotice(Notice.Severity.ERROR, "exit_failed", BackgroundNotices.exitOutcome(FAILED, null));
        assertNotice(Notice.Severity.WARNING, "exit_skipped", BackgroundNotices.exitOutcome(SKIPPED, null));
        Notice notMade = BackgroundNotices.exitOutcome(null, new CompletionException(new IllegalStateException("No space")));
        assertNotice(Notice.Severity.ERROR, "exit_not_made", notMade);
        assertEquals(List.of("No space"), notMade.arguments());

        assertEquals(Optional.empty(), BackgroundNotices.scheduledOutcome(SUCCESS, null));
        assertEquals(Optional.empty(), BackgroundNotices.scheduledOutcome(SKIPPED, null));
        assertNotice(Notice.Severity.WARNING, "scheduled_warnings", BackgroundNotices.scheduledOutcome(PARTIAL, null).orElseThrow());
        assertNotice(Notice.Severity.WARNING, "scheduled_failed", BackgroundNotices.scheduledOutcome(FAILED, null).orElseThrow());
    }

    @Test
    void aCancelIsThePlayersChoiceAndNeverAWarning() {
        CompletionException cancelled = new CompletionException(new CancellationException("Backup was cancelled"));

        assertTrue(BackgroundNotices.scheduledOutcome(null, cancelled).isEmpty());
        assertNotice(Notice.Severity.WARNING, "exit_cancelled", BackgroundNotices.exitOutcome(null, cancelled));
    }

    private static void assertNotice(Notice.Severity severity, String key, Notice notice) {
        assertEquals(severity, notice.severity());
        assertEquals("screen.worldarchive.notice." + key, notice.key());
    }

    private static BackupResult result(DestinationResult... destinations) {
        return new BackupResult(BackupId.create(), WorldId.create(), List.of(destinations), Instant.EPOCH);
    }
}
