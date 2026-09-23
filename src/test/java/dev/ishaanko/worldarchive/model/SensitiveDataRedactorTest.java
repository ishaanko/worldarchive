package dev.ishaanko.worldarchive.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class SensitiveDataRedactorTest {
    @Test
    void failureMessagesAreRedactedWhereTheyAreMade() {
        String secret = "ghp_abcdefghijklmnopqrstuvwxyz";
        DestinationResult result = DestinationResult.failed(
                DestinationType.GIT,
                "push https://user:password@example.invalid failed token=" + secret);

        String message = result.message().orElseThrow();
        assertTrue(message.contains(SensitiveDataRedactor.REDACTED));
        assertFalse(message.contains("user:password"));
        assertFalse(message.contains(secret));

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
    void bearerTokensAreRedacted() {
        assertEquals("Bearer [REDACTED]", SensitiveDataRedactor.redact("Bearer glpat-abcdefghijklmnopqrstuvwxyz"));
    }

    @Test
    void boundedDecodingPreservesBenignAndMalformedPercentText() {
        assertEquals("Progress is 100% complete", SensitiveDataRedactor.redact("Progress is 100% complete"));
        assertEquals("Malformed %ZZ text", SensitiveDataRedactor.redact("Malformed %ZZ text"));
        // A credential prefix followed by a bare percent sign is ordinary text, not an escape.
        assertEquals("Task100% Done", SensitiveDataRedactor.redact("Task100% Done"));
        assertEquals("Disk-90%/backups", SensitiveDataRedactor.redact("Disk-90%/backups"));
        assertEquals(SensitiveDataRedactor.REDACTED, SensitiveDataRedactor.redact("token%2Fabc"));
        DestinationResult zip = DestinationResult.success(
                DestinationType.ZIP, "world/Task100% Done - Backup - id.zip");
        assertEquals("world/Task100% Done - Backup - id.zip", zip.artifactId().orElseThrow());

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
