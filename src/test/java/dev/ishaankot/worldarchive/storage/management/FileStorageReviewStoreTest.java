package dev.ishaankot.worldarchive.storage.management;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.ishaankot.worldarchive.model.WorldId;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class FileStorageReviewStoreTest {
    private static final WorldId WORLD_ID =
            WorldId.parse("00000000-0000-4000-8000-000000000001");

    @TempDir
    Path temporaryDirectory;

    @Test
    void allowsOneReviewClaimPerSevenDays() throws Exception {
        FileStorageReviewStore store = new FileStorageReviewStore(temporaryDirectory);
        Instant first = Instant.parse("2026-07-20T12:00:00Z");

        assertTrue(store.claimIfDue(WORLD_ID, first));
        assertFalse(store.claimIfDue(WORLD_ID, first.plus(6, ChronoUnit.DAYS)));
        assertTrue(store.claimIfDue(WORLD_ID, first.plus(7, ChronoUnit.DAYS)));
    }
}
