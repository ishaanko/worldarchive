package dev.ishaanko.worldarchive.model;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import org.junit.jupiter.api.Test;

final class SafeTextTest {
    @Test
    void makesAMessageSafeToShowOnOneLine() {
        assertEquals(
                "Push failed: token=[REDACTED] rejected",
                SafeText.of("  Push failed:\r\n token=abc123\u202e rejected\n", "Push failed", 200));
        assertEquals("🌍🌍🌍🌍…", SafeText.of("🌍".repeat(10), "fallback", 5));
        assertEquals("fallback", SafeText.of(" \n\t ", "fallback", 5));
        assertEquals("fallback", SafeText.of(null, "fallback", 5));
    }

    @Test
    void usesTheMessageOfTheRealCause() {
        Throwable wrapped = new CompletionException(new ExecutionException(new IOException("No space left")));

        assertEquals("No space left", SafeText.from(wrapped, "Backup failed", 100));
        assertEquals("Backup failed", SafeText.from(new CompletionException(new IOException()), "Backup failed", 100));
    }

    @Test
    void aFailureMessageWithLineBreaksBecomesAResultInsteadOfAnError() {
        DestinationResult failed = DestinationResult.failed(DestinationType.ZIP, "Could not write\nD:\\Backups");

        assertEquals("Could not write D:\\Backups", failed.message().orElseThrow());
    }
}
