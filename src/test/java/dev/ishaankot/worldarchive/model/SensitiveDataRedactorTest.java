package dev.ishaankot.worldarchive.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;

final class SensitiveDataRedactorTest {
    @Test
    void destinationDiagnosticsAreRedactedAtConstruction() {
        String secret = "ghp_abcdefghijklmnopqrstuvwxyz";
        DestinationResult result = DestinationResult.failed(
                DestinationType.GIT,
                "push https://user:password@example.invalid failed token=" + secret);

        String message = result.message().orElseThrow();
        assertTrue(message.contains(SensitiveDataRedactor.REDACTED));
        assertFalse(message.contains("user:password"));
        assertFalse(message.contains(secret));
        assertThrows(IllegalArgumentException.class, () -> DestinationResult.success(
                DestinationType.GIT,
                "https://user:password@example.invalid/artifact"));

        String adjacent = "prefix_x_ghp_abcdefghijklmnopqrstuvwxyz_suffix";
        DestinationResult adjacentResult = DestinationResult.failed(DestinationType.GIT, adjacent);
        assertFalse(adjacentResult.message().orElseThrow().contains("ghp_abcdefghijklmnopqrstuvwxyz"));
        DestinationResult encodedResult = DestinationResult.failed(
                DestinationType.GIT,
                "ghp%255Fabcdefghijklmnopqrstuvwxyz");
        assertEquals(SensitiveDataRedactor.REDACTED, encodedResult.message().orElseThrow());

        DestinationResult mixedResult = DestinationResult.failed(
                DestinationType.GIT,
                "password=hunter2 and ghp%255Fabcdefghijklmnopqrstuvwxyz");
        assertEquals(SensitiveDataRedactor.REDACTED, mixedResult.message().orElseThrow());

        String malformedAdjacent = "ghp%255Fabcdefghijklmnopqrstuvwxyz malformed %ZZ";
        DestinationResult malformedAdjacentResult = DestinationResult.failed(
                DestinationType.GIT,
                malformedAdjacent);
        assertEquals(SensitiveDataRedactor.REDACTED, malformedAdjacentResult.message().orElseThrow());
    }

    @Test
    void healthAndLabelsCannotExposeKnownTokens() {
        String token = "glpat-abcdefghijklmnopqrstuvwxyz";
        DestinationHealth health = new DestinationHealth(
                DestinationType.GIT,
                DestinationHealthStatus.AUTHENTICATION_REQUIRED,
                "Bearer " + token,
                Instant.parse("2026-07-17T12:00:00Z"));
        BackupManifest manifest = BackupManifest.create(
                BackupId.create(),
                WorldId.create(),
                "World",
                Optional.of("token=" + token),
                Instant.parse("2026-07-17T12:00:00Z"),
                BackupTrigger.MANUAL,
                8,
                1_024,
                3,
                "a".repeat(64),
                "b".repeat(64));

        assertEquals("Bearer [REDACTED]", health.message());
        assertFalse(manifest.label().orElseThrow().contains(token));
        assertEquals(3, manifest.changedFileCount());
        assertEquals("a".repeat(64), manifest.contentSha256());
        assertEquals("b".repeat(64), manifest.inventorySha256());
    }

    @Test
    void boundedDecodingPreservesBenignAndMalformedPercentText() {
        assertEquals("Progress is 100% complete", SensitiveDataRedactor.redact("Progress is 100% complete"));
        assertEquals("Malformed %ZZ text", SensitiveDataRedactor.redact("Malformed %ZZ text"));

        String benign = nestedPercentEncoding("benign", 32);
        assertEquals(benign, SensitiveDataRedactor.redact(benign));

        String excessiveSecret = nestedPercentEncoding("ghp_abcdefghijklmnopqrstuvwxyz", 32);
        assertEquals(SensitiveDataRedactor.REDACTED, SensitiveDataRedactor.redact(excessiveSecret));
    }

    private static String nestedPercentEncoding(String value, int depth) {
        StringBuilder encoded = new StringBuilder(value.length() * 3);
        for (int index = 0; index < value.length(); index++) {
            encoded.append('%').append(String.format("%02X", (int) value.charAt(index)));
        }
        String nested = encoded.toString();
        for (int round = 1; round < depth; round++) {
            nested = nested.replace("%", "%25");
        }
        return nested;
    }
}
