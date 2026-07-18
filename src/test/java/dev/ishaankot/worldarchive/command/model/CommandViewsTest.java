package dev.ishaankot.worldarchive.command.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.ishaankot.worldarchive.core.BackupOperation;
import dev.ishaankot.worldarchive.core.OperationId;
import dev.ishaankot.worldarchive.core.OperationPhase;
import dev.ishaankot.worldarchive.core.OperationProgress;
import dev.ishaankot.worldarchive.model.BackupId;
import dev.ishaankot.worldarchive.model.BackupManifest;
import dev.ishaankot.worldarchive.model.BackupRecord;
import dev.ishaankot.worldarchive.model.BackupResult;
import dev.ishaankot.worldarchive.model.BackupStatus;
import dev.ishaankot.worldarchive.model.BackupTrigger;
import dev.ishaankot.worldarchive.model.DestinationHealth;
import dev.ishaankot.worldarchive.model.DestinationHealthStatus;
import dev.ishaankot.worldarchive.model.DestinationResult;
import dev.ishaankot.worldarchive.model.DestinationType;
import dev.ishaankot.worldarchive.model.WorldId;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CommandViewsTest {
    private static final WorldId WORLD_ID = new WorldId(
            UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"));

    @Test
    void commandListUsesOneBasedNewestFirstPagination() {
        BackupRecord oldest = record("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa1", 1, "old");
        BackupRecord middle = record("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa2", 2, "middle");
        BackupRecord newest = record("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa3", 3, "new");

        CommandBackupListPage first = CommandBackupListPage.create(
                List.of(oldest, newest, middle), 1, 2);
        CommandBackupListPage second = CommandBackupListPage.create(
                List.of(oldest, newest, middle), 9, 2);

        assertEquals(List.of(
                        newest.manifest().backupId(), middle.manifest().backupId()),
                first.entries().stream().map(CommandBackupEntry::backupId).toList());
        assertEquals(36, first.entries().getFirst().shortId().length());
        assertFalse(first.hasPreviousPage());
        assertTrue(first.hasNextPage());
        assertEquals(2, second.pageNumber());
        assertEquals(List.of(oldest.manifest().backupId()),
                second.entries().stream().map(CommandBackupEntry::backupId).toList());
        assertTrue(second.hasPreviousPage());
        assertFalse(second.hasNextPage());
    }

    @Test
    void standardHelpCoversThePlannedSurface() {
        CommandHelpView help = CommandHelpView.standard();

        assertEquals(10, help.entries().size());
        assertTrue(help.entries().stream().anyMatch(entry -> entry.usage().equals("/backup create [label]")));
        assertTrue(help.entries().stream().anyMatch(entry -> entry.usage().equals("/backup restore <id>")));
        assertTrue(help.entries().stream().anyMatch(entry -> entry.usage().equals("/backup config")));
    }

    @Test
    void outcomePreservesPartialFailureAndRedactsDetails() {
        BackupId id = BackupId.parse("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
        BackupResult result = BackupResult.aggregate(
                id,
                WORLD_ID,
                List.of(
                        DestinationResult.success(DestinationType.GIT, "git-object"),
                        DestinationResult.failed(DestinationType.ZIP, "password=hunter2")),
                Instant.EPOCH.plusSeconds(2));

        CommandOutcomeView view = CommandOutcomeView.from(result);

        assertEquals(BackupStatus.PARTIAL_SUCCESS, view.status());
        assertEquals("Backup completed with destination issues", view.headline());
        assertEquals("password=[REDACTED]", view.destinations().get(1).detail().orElseThrow());
    }

    @Test
    void statusSortsHealthAndRedactsCurrentProgress() {
        OperationProgress progress = new OperationProgress(
                new OperationId(UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc")),
                WORLD_ID,
                Optional.empty(),
                BackupOperation.VERIFY,
                OperationPhase.READING,
                0,
                0,
                "Checking Bearer abcdefghijklmnop");
        Instant checkedAt = Instant.parse("2026-07-17T12:00:00Z");
        DestinationHealth zip = new DestinationHealth(
                DestinationType.ZIP,
                DestinationHealthStatus.HEALTHY,
                "Ready",
                checkedAt);
        DestinationHealth git = new DestinationHealth(
                DestinationType.GIT,
                DestinationHealthStatus.AUTHENTICATION_REQUIRED,
                "token=secret-value",
                checkedAt);

        CommandStatusView status = CommandStatusView.create(
                WORLD_ID, 12, Optional.of(progress), List.of(zip, git));

        assertEquals(12, status.backupCount());
        assertEquals(List.of(DestinationType.GIT, DestinationType.ZIP),
                status.destinations().stream().map(CommandDestinationHealth::destination).toList());
        assertEquals("token=[REDACTED]", status.destinations().getFirst().detail());
        assertEquals(
                "Checking Bearer [REDACTED]",
                status.currentOperation().orElseThrow().detail());
        assertTrue(status.currentOperation().orElseThrow().fraction().isEmpty());

        WorldId otherWorld = new WorldId(UUID.fromString("dddddddd-dddd-dddd-dddd-dddddddddddd"));
        assertThrows(
                IllegalArgumentException.class,
                () -> CommandStatusView.create(otherWorld, 0, Optional.of(progress), List.of()));
    }

    private static BackupRecord record(String id, long epochSecond, String label) {
        BackupId backupId = BackupId.parse(id);
        Instant createdAt = Instant.ofEpochSecond(epochSecond);
        BackupManifest manifest = BackupManifest.create(
                backupId,
                WORLD_ID,
                "Fixture World",
                Optional.of(label),
                createdAt,
                BackupTrigger.MANUAL,
                1,
                10,
                1,
                "a".repeat(64),
                "b".repeat(64));
        BackupResult result = BackupResult.aggregate(
                backupId,
                WORLD_ID,
                List.of(DestinationResult.success(DestinationType.ZIP, "archive-" + id)),
                createdAt.plusSeconds(1));
        return new BackupRecord(manifest, result);
    }
}
