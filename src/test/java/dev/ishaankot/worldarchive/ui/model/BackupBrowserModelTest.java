package dev.ishaankot.worldarchive.ui.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.ishaankot.worldarchive.model.BackupId;
import dev.ishaankot.worldarchive.model.BackupManifest;
import dev.ishaankot.worldarchive.model.BackupRecord;
import dev.ishaankot.worldarchive.model.BackupResult;
import dev.ishaankot.worldarchive.model.BackupTrigger;
import dev.ishaankot.worldarchive.model.DestinationResult;
import dev.ishaankot.worldarchive.model.DestinationType;
import dev.ishaankot.worldarchive.model.WorldId;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class BackupBrowserModelTest {
    private static final WorldId WORLD_ID = new WorldId(
            UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"));

    @Test
    void filtersSortsPaginatesAndKeepsOnlyVisibleSelection() {
        BackupRecord oldest = record("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa1", "Zulu", 1, 10, 1);
        BackupRecord middle = record("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa2", "alpha", 2, 30, 3);
        BackupRecord newest = record("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa3", "Alpha", 3, 20, 2);

        BackupBrowserPage first = BackupBrowserPage.create(
                List.of(oldest, middle, newest),
                new BackupBrowserQuery("alpha", BackupSort.NEWEST, 0, 1),
                Optional.of(newest.manifest().backupId()));

        assertEquals(2, first.totalRows());
        assertEquals(2, first.pageCount());
        assertEquals(List.of(newest.manifest().backupId()), ids(first));
        assertEquals(Optional.of(newest.manifest().backupId()), first.selectedBackupId());

        BackupBrowserPage second = BackupBrowserPage.create(
                List.of(oldest, middle, newest),
                new BackupBrowserQuery("alpha", BackupSort.NEWEST, 1, 1),
                Optional.of(newest.manifest().backupId()));
        assertEquals(List.of(middle.manifest().backupId()), ids(second));
        assertTrue(second.selectedBackupId().isEmpty());

        BackupBrowserPage sizeOrder = BackupBrowserPage.create(
                List.of(oldest, middle, newest),
                new BackupBrowserQuery("", BackupSort.SIZE_DESCENDING, 0, 3),
                Optional.empty());
        assertEquals(
                List.of(
                        middle.manifest().backupId(),
                        newest.manifest().backupId(),
                        oldest.manifest().backupId()),
                ids(sizeOrder));

        BackupBrowserPage oldestFirst = BackupBrowserPage.create(
                List.of(oldest, middle, newest),
                new BackupBrowserQuery("", BackupSort.OLDEST, 0, 3),
                Optional.empty());
        assertEquals(
                List.of(
                        oldest.manifest().backupId(),
                        middle.manifest().backupId(),
                        newest.manifest().backupId()),
                ids(oldestFirst));

        BackupBrowserPage changedOrder = BackupBrowserPage.create(
                List.of(oldest, middle, newest),
                new BackupBrowserQuery("", BackupSort.CHANGED_FILES_DESCENDING, 0, 3),
                Optional.empty());
        assertEquals(
                List.of(
                        middle.manifest().backupId(),
                        newest.manifest().backupId(),
                        oldest.manifest().backupId()),
                ids(changedOrder));
    }

    @Test
    void clampsPagesAndUsesStableLabelTies() {
        BackupRecord first = record("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa1", "same", 1, 10, 1);
        BackupRecord second = record("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa2", "SAME", 2, 10, 1);
        BackupBrowserPage page = BackupBrowserPage.create(
                List.of(first, second),
                new BackupBrowserQuery("", BackupSort.LABEL, 99, 1),
                Optional.empty());

        assertEquals(1, page.pageIndex());
        assertEquals(List.of(first.manifest().backupId()), ids(page));
    }

    @Test
    void actionPolicyExplainsEveryDisabledAction() {
        BackupRow selected = BackupRow.from(record(
                "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa1", "label", 1, 10, 1));
        BackupBrowserCapabilities capabilities = new BackupBrowserCapabilities(
                false, true, false, true);
        Map<BackupAction, BackupActionAvailability> states = BackupActionPolicy.evaluate(
                capabilities, Optional.of(selected));

        assertTrue(states.get(BackupAction.CREATE).enabled());
        assertTrue(states.get(BackupAction.RESTORE).enabled());
        assertTrue(states.get(BackupAction.DELETE).enabled());
        assertTrue(states.get(BackupAction.VERIFY).enabled());
        assertTrue(states.get(BackupAction.OPEN_FOLDER).enabled());
        assertTrue(states.get(BackupAction.SETTINGS).enabled());
        assertFalse(states.get(BackupAction.SYNC).enabled());
        assertEquals(
                ActionDisabledReason.REMOTE_NOT_CONFIGURED,
                states.get(BackupAction.SYNC).reason());

        Map<BackupAction, BackupActionAvailability> noSelection = BackupActionPolicy.evaluate(
                capabilities, Optional.empty());
        assertEquals(
                ActionDisabledReason.NO_SELECTION,
                noSelection.get(BackupAction.RESTORE).reason());

        Map<BackupAction, BackupActionAvailability> busy = BackupActionPolicy.evaluate(
                new BackupBrowserCapabilities(true, true, true, true),
                Optional.of(selected));
        assertTrue(busy.values().stream().noneMatch(BackupActionAvailability::enabled));
        assertTrue(busy.values().stream()
                .allMatch(state -> state.reason() == ActionDisabledReason.OPERATION_IN_PROGRESS));

        BackupRow failed = BackupRow.from(record(
                "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa4",
                "failed",
                4,
                10,
                1,
                DestinationResult.failed(DestinationType.GIT, "failed")));
        Map<BackupAction, BackupActionAvailability> noDurableCopy = BackupActionPolicy.evaluate(
                new BackupBrowserCapabilities(false, false, true, false),
                Optional.of(failed));
        assertEquals(
                ActionDisabledReason.NO_DESTINATION_CONFIGURED,
                noDurableCopy.get(BackupAction.CREATE).reason());
        assertEquals(
                ActionDisabledReason.FOLDER_UNAVAILABLE,
                noDurableCopy.get(BackupAction.OPEN_FOLDER).reason());
        assertEquals(
                ActionDisabledReason.NO_DURABLE_COPY,
                noDurableCopy.get(BackupAction.RESTORE).reason());
        assertEquals(
                ActionDisabledReason.NO_DURABLE_COPY,
                noDurableCopy.get(BackupAction.SYNC).reason());
    }

    private static List<BackupId> ids(BackupBrowserPage page) {
        return page.rows().stream().map(BackupRow::backupId).toList();
    }

    private static BackupRecord record(
            String backupId,
            String label,
            long epochSecond,
            long bytes,
            long changedFiles) {
        return record(
                backupId,
                label,
                epochSecond,
                bytes,
                changedFiles,
                DestinationResult.success(DestinationType.GIT, "artifact-" + backupId));
    }

    private static BackupRecord record(
            String backupId,
            String label,
            long epochSecond,
            long bytes,
            long changedFiles,
            DestinationResult destination) {
        BackupId id = BackupId.parse(backupId);
        Instant createdAt = Instant.ofEpochSecond(epochSecond);
        BackupManifest manifest = BackupManifest.create(
                id,
                WORLD_ID,
                "Fixture World",
                Optional.of(label),
                createdAt,
                BackupTrigger.MANUAL,
                4,
                bytes,
                changedFiles,
                "a".repeat(64),
                "b".repeat(64));
        BackupResult result = BackupResult.aggregate(
                id,
                WORLD_ID,
                List.of(destination),
                createdAt.plusSeconds(1));
        return new BackupRecord(manifest, result);
    }
}
