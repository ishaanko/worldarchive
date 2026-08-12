package dev.ishaanko.worldarchive.model;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;

final class BackupManifestTest {
    private static final String SHA256 = "0".repeat(64);

    @Test
    void requiresAPortableFourDigitUtcYear() {
        assertDoesNotThrow(() -> manifest(
                Instant.parse("9999-12-31T23:59:59Z")));
        assertThrows(
                IllegalArgumentException.class,
                () -> manifest(Instant.MAX));
        assertThrows(
                IllegalArgumentException.class,
                () -> manifest(Instant.parse("-0001-01-01T00:00:00Z")));
    }

    @Test
    void rejectsMalformedUnicodeText() {
        assertThrows(
                IllegalArgumentException.class,
                () -> BackupManifest.create(
                        BackupId.create(),
                        WorldId.create(),
                        "World\uD800",
                        Optional.empty(),
                        Instant.parse("2026-07-31T12:00:00Z"),
                        BackupTrigger.MANUAL,
                        0,
                        0,
                        0,
                        SHA256,
                        SHA256));
    }

    @Test
    void recordsNoGameVersionForManifestsBuiltWithoutOne() {
        assertEquals(Optional.empty(), manifest(Instant.parse("2026-07-31T12:00:00Z")).gameVersion());
    }

    @Test
    void keepsTheGameVersionItWasStampedWith() {
        BackupManifest manifest = BackupManifest.create(
                BackupId.create(),
                WorldId.create(),
                "World",
                Optional.empty(),
                Instant.parse("2026-07-31T12:00:00Z"),
                BackupTrigger.MANUAL,
                0,
                0,
                0,
                SHA256,
                SHA256,
                Optional.of(new GameVersionStamp("26.2", 4_820)));

        assertEquals(Optional.of(new GameVersionStamp("26.2", 4_820)), manifest.gameVersion());
    }

    @Test
    void rejectsAnUnusableGameVersionStamp() {
        assertThrows(IllegalArgumentException.class, () -> new GameVersionStamp("26.2", 0));
        assertThrows(IllegalArgumentException.class, () -> new GameVersionStamp(" ", 1));
        assertThrows(IllegalArgumentException.class, () -> new GameVersionStamp("26.2\n", 1));
    }

    private static BackupManifest manifest(Instant createdAt) {
        return BackupManifest.create(
                BackupId.create(),
                WorldId.create(),
                "World",
                Optional.empty(),
                createdAt,
                BackupTrigger.MANUAL,
                0,
                0,
                0,
                SHA256,
                SHA256);
    }
}
