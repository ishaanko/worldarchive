package dev.ishaanko.worldarchive.ui.model;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.ishaanko.worldarchive.model.BackupId;
import dev.ishaanko.worldarchive.model.BackupStatus;
import dev.ishaanko.worldarchive.storage.git.GitConversion;
import java.time.Instant;
import java.util.List;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class GitConversionSummaryTest {
    private static final Instant START = Instant.parse("2026-09-01T12:00:00Z");

    @Test
    void theOutcomeFollowsWhatConvertedAndWhatFailed() {
        assertEquals(GitConversionSummary.Outcome.CONVERTED, summary(2, 0).outcome());
        assertEquals(GitConversionSummary.Outcome.NOTHING_TO_CONVERT, summary(0, 0).outcome());
        assertEquals(GitConversionSummary.Outcome.PARTIAL, summary(1, 1).outcome());
        assertEquals(BackupStatus.PARTIAL_SUCCESS, summary(1, 1).status());
        assertEquals(GitConversionSummary.Outcome.NONE_CONVERTED, summary(0, 2).outcome());
        assertEquals(BackupStatus.FAILED, summary(0, 2).status());
    }

    @Test
    void namesTheOldestFailuresByTimeAndCountsTheRest() {
        GitConversionSummary summary = summary(1, 5);

        assertEquals(GitConversionSummary.MAXIMUM_SHOWN, summary.shown().size());
        assertEquals(2, summary.more());
        assertEquals(BackupText.dateTime(START), summary.shown().getFirst().name());
        assertEquals("damaged 0", summary.shown().getFirst().reason());
        assertEquals(BackupText.bytes(3_000_000), summary.freed());
    }

    /** A conversion with {@code converted} converted backups and {@code failed} failures, newest failure first. */
    private static GitConversionSummary summary(int converted, int failed) {
        List<BackupId> done = Stream.generate(BackupId::create).limit(converted).toList();
        List<GitConversion.Failure> failures = IntStream.range(0, failed)
                .map(index -> failed - 1 - index)
                .mapToObj(index -> new GitConversion.Failure(BackupId.create(), START.plusSeconds(index), "damaged " + index))
                .toList();
        return GitConversionSummary.of(new GitConversion(done, 1, failures, 3_000_000, false));
    }
}
