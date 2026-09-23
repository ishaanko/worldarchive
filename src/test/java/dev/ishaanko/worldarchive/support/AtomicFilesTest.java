package dev.ishaanko.worldarchive.support;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class AtomicFilesTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void rejectsMalformedUtf8() throws IOException {
        Path source = Files.write(
                temporaryDirectory.resolve("invalid.json"),
                new byte[] {(byte) 0xc3, 0x28});

        IOException failure = assertThrows(
                IOException.class,
                () -> AtomicFiles.readUtf8(source));

        assertTrue(failure.getMessage().contains("not valid UTF-8"));
    }

    @Test
    void aFailedReplaceKeepsTheOldFileAndLeavesNoTemporaryFile() throws IOException {
        Path catalog = Files.writeString(temporaryDirectory.resolve("catalog.json"), "old");

        // Channel writes fail on an interrupted thread, as they do when a backup is cancelled.
        Thread.currentThread().interrupt();
        try {
            assertThrows(IOException.class, () -> AtomicFiles.writeUtf8(catalog, "new"));
        } finally {
            Thread.interrupted();
        }

        assertEquals("old", Files.readString(catalog));
        assertEquals(List.of(catalog), filesIn(temporaryDirectory));
    }

    @Test
    void aReplaceThatKeepsFailingGivesUpAndLeavesNoTemporaryFile() throws IOException {
        Path occupied = Files.createDirectory(temporaryDirectory.resolve("catalog.json"));
        Path kept = Files.writeString(occupied.resolve("kept.txt"), "kept");

        assertThrows(IOException.class, () -> AtomicFiles.writeUtf8(occupied, "new"));

        assertEquals("kept", Files.readString(kept));
        assertEquals(List.of(occupied), filesIn(temporaryDirectory));
    }

    private static List<Path> filesIn(Path directory) throws IOException {
        try (Stream<Path> entries = Files.list(directory)) {
            return entries.toList();
        }
    }
}
