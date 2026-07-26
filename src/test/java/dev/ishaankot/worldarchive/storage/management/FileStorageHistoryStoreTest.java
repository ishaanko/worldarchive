package dev.ishaankot.worldarchive.storage.management;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.ishaankot.worldarchive.model.WorldId;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class FileStorageHistoryStoreTest {
    private static final WorldId WORLD_ID =
            WorldId.parse("00000000-0000-4000-8000-000000000001");

    @TempDir
    Path temporaryDirectory;

    @Test
    void storesSamplesInTimeOrderAndReplacesMatchingInstants() throws Exception {
        FileStorageHistoryStore store = new FileStorageHistoryStore(temporaryDirectory);
        Instant first = Instant.parse("2026-07-20T12:00:00Z");
        Instant second = Instant.parse("2026-07-21T12:00:00Z");

        store.append(WORLD_ID, new StorageSample(second, 200));
        store.append(WORLD_ID, new StorageSample(first, 100));
        store.append(WORLD_ID, new StorageSample(second, 250));

        assertEquals(
                List.of(new StorageSample(first, 100), new StorageSample(second, 250)),
                store.load(WORLD_ID));
    }

    @Test
    void appendRecoversFromMalformedLocalHistory() throws Exception {
        Files.writeString(
                temporaryDirectory.resolve(WORLD_ID + ".json"),
                "{\"schemaVersion\":1}",
                StandardCharsets.UTF_8);
        FileStorageHistoryStore store = new FileStorageHistoryStore(temporaryDirectory);
        StorageSample replacement =
                new StorageSample(Instant.parse("2026-07-21T12:00:00Z"), 250);

        store.append(WORLD_ID, replacement);

        assertEquals(List.of(replacement), store.load(WORLD_ID));
    }
}
