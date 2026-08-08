package dev.ishaankot.worldarchive.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Assumptions;
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
    void rejectsSymbolicLinkSourceWhenSupported() throws IOException {
        Path target = Files.writeString(
                temporaryDirectory.resolve("target.json"),
                "{}");
        Path link = temporaryDirectory.resolve("linked.json");
        try {
            Files.createSymbolicLink(link, target.getFileName());
        } catch (IOException | UnsupportedOperationException | SecurityException exception) {
            Assumptions.assumeTrue(
                    false,
                    "Symbolic links are unavailable: " + exception.getMessage());
        }

        assertThrows(IOException.class, () -> AtomicFiles.readUtf8(link));
    }

    @Test
    void enforcesSmallReadAndWriteLimitsWithoutLargeAllocations()
            throws IOException {
        Path source = Files.writeString(
                temporaryDirectory.resolve("source.json"),
                "123456789");
        Path existing = Files.writeString(
                temporaryDirectory.resolve("existing.json"),
                "stable");
        Path missingParent = temporaryDirectory
                .resolve("missing")
                .resolve("target.json");

        assertThrows(
                IOException.class,
                () -> AtomicFiles.readUtf8(source, 8));
        assertThrows(
                IOException.class,
                () -> AtomicFiles.writeUtf8(existing, "123456789", 8));
        assertThrows(
                IOException.class,
                () -> AtomicFiles.writeUtf8(missingParent, "123456789", 8));

        assertEquals("stable", Files.readString(existing));
        assertFalse(Files.exists(missingParent.getParent()));

        AtomicFiles.writeUtf8(existing, "12345678", 8);
        assertEquals("12345678", AtomicFiles.readUtf8(existing, 8));
    }
}
