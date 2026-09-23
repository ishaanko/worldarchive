package dev.ishaanko.worldarchive.model;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

final class BackupResultTest {
    @Test
    void derivesTheOverallStatusFromTheDestinations() {
        DestinationResult git = DestinationResult.success(DestinationType.GIT, "ref");

        assertEquals(BackupStatus.SUCCESS, status(git, DestinationResult.skipped(DestinationType.ZIP, "off")));
        assertEquals(BackupStatus.PARTIAL_SUCCESS, status(git, DestinationResult.failed(DestinationType.ZIP, "full")));
        assertEquals(BackupStatus.PARTIAL_SUCCESS, status(
                DestinationResult.pendingSync(DestinationType.GIT, "ref", "Remote unavailable")));
        assertEquals(BackupStatus.FAILED, status(DestinationResult.failed(DestinationType.GIT, "no Git LFS")));
        assertEquals(BackupStatus.SKIPPED, status());
    }

    private static BackupStatus status(DestinationResult... destinations) {
        return new BackupResult(
                BackupId.create(),
                WorldId.create(),
                List.of(destinations),
                Instant.parse("2026-07-17T12:00:00Z")).status();
    }
}
