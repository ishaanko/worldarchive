package dev.ishaankot.worldarchive.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class RuntimeNoticeStoreTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void warningSurvivesStoreReopenUntilCleared() throws IOException {
        Path file = temporaryDirectory.resolve("runtime/last-warning.txt");
        RuntimeNoticeStore store = new RuntimeNoticeStore(file);

        store.save("World-exit backup failed");

        assertEquals(
                "World-exit backup failed",
                new RuntimeNoticeStore(file).load().orElseThrow());
        store.clear();
        assertTrue(store.load().isEmpty());
    }

    @Test
    void laterOutcomesCannotEraseOrReplaceAnUnacknowledgedWarning() throws IOException {
        RuntimeNoticeStore store = new RuntimeNoticeStore(
                temporaryDirectory.resolve("runtime/retained-warning.txt"));

        store.retain("World-exit backup failed");
        store.retain("World-exit backup completed with warnings");

        assertEquals("World-exit backup failed", store.load().orElseThrow());
    }
}
