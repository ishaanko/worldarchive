package dev.ishaanko.worldarchive.ui.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.ishaanko.worldarchive.model.BackupId;
import dev.ishaanko.worldarchive.model.BackupManifest;
import dev.ishaanko.worldarchive.model.BackupRecord;
import dev.ishaanko.worldarchive.model.BackupResult;
import dev.ishaanko.worldarchive.model.BackupTrigger;
import dev.ishaanko.worldarchive.model.DestinationResult;
import dev.ishaanko.worldarchive.model.DestinationType;
import dev.ishaanko.worldarchive.model.WorldId;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class BackupBrowserModelTest {
    private static final WorldId WORLD_ID = WorldId.parse("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");

    @Test
    void filtersSortsAndPages() {
        BackupRow oldest = row("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa1", "Zulu", 1, 10, 1);
        BackupRow middle = row("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa2", "alpha", 2, 30, 3);
        BackupRow newest = row("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa3", "Alpha", 3, 20, 2);
        List<BackupRow> rows = List.of(oldest, middle, newest);

        List<BackupRow> alpha = BackupFilter.apply(rows, " ALPHA ", BackupSort.NEWEST);
        assertEquals(List.of(newest, middle), alpha);
        Paging second = Paging.of(alpha.size(), 1, 1);
        assertEquals(List.of(middle), second.slice(alpha));
        assertTrue(second.hasPrevious());
        assertFalse(second.hasNext());

        assertEquals(List.of(middle, newest, oldest), BackupFilter.apply(rows, "", BackupSort.SIZE_DESCENDING));
        assertEquals(List.of(oldest, middle, newest), BackupFilter.apply(rows, "", BackupSort.OLDEST));
        assertEquals(List.of(middle, newest, oldest), BackupFilter.apply(rows, "", BackupSort.CHANGED_FILES_DESCENDING));
    }

    @Test
    void theFilterMatchesLabelsTriggersAsShownAndIdPrefixesButNotTheWorldName() {
        BackupRow labeled = row("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa1", "Base before the raid", 1, 10, 1);
        BackupRow unlabeled = record(
                "cccccccc-aaaa-aaaa-aaaa-aaaaaaaaaaa2", Optional.empty(), BackupTrigger.WORLD_EXIT, 2, 10, 1, zip());

        assertEquals(List.of(labeled), BackupFilter.apply(List.of(labeled, unlabeled), "base", BackupSort.NEWEST));
        assertEquals(List.of(unlabeled), BackupFilter.apply(List.of(labeled, unlabeled), "world exit", BackupSort.NEWEST));
        assertEquals(List.of(unlabeled), BackupFilter.apply(List.of(labeled, unlabeled), "cccc", BackupSort.NEWEST));
    }

    @Test
    void pagesAreClampedAndLabelTiesStayStable() {
        BackupRow first = row("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa1", "same", 1, 10, 1);
        BackupRow second = row("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa2", "SAME", 2, 10, 1);
        List<BackupRow> sorted = BackupFilter.apply(List.of(first, second), "", BackupSort.LABEL);

        Paging paging = Paging.of(sorted.size(), 1, 99);

        assertEquals(1, paging.pageIndex());
        assertEquals(List.of(first), paging.slice(sorted));
    }

    @Test
    void actionPolicyExplainsEveryDisabledAction() {
        BackupRow selected = row("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa1", "label", 1, 10, 1);
        BackupBrowserCapabilities capabilities = capabilities(false, true, true, false, true);

        Map<BackupAction, ActionDisabledReason> states = BackupActionPolicy.evaluate(capabilities, List.of(selected));
        for (BackupAction action : List.of(
                BackupAction.CREATE,
                BackupAction.RESTORE,
                BackupAction.DELETE,
                BackupAction.VERIFY,
                BackupAction.OPEN_FOLDER,
                BackupAction.SETTINGS)) {
            assertEquals(ActionDisabledReason.NONE, states.get(action), action.name());
        }
        assertEquals(ActionDisabledReason.REMOTE_NOT_CONFIGURED, states.get(BackupAction.SYNC));
        assertEquals(
                ActionDisabledReason.NO_SELECTION,
                BackupActionPolicy.evaluate(capabilities, List.of()).get(BackupAction.RESTORE));
        assertTrue(BackupActionPolicy.evaluate(capabilities.withOperationInProgress(), List.of(selected))
                .values().stream()
                .allMatch(reason -> reason == ActionDisabledReason.OPERATION_IN_PROGRESS));

        BackupRow failedGitOnly = record(
                "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa4",
                Optional.of("failed"),
                BackupTrigger.MANUAL,
                4,
                10,
                1,
                DestinationResult.failed(DestinationType.GIT, "failed"));
        Map<BackupAction, ActionDisabledReason> noCopy = BackupActionPolicy.evaluate(
                capabilities(false, true, false, true, false), List.of(failedGitOnly));
        assertEquals(ActionDisabledReason.CREATE_BLOCKED, noCopy.get(BackupAction.CREATE));
        assertEquals(ActionDisabledReason.FOLDER_UNAVAILABLE, noCopy.get(BackupAction.OPEN_FOLDER));
        assertEquals(ActionDisabledReason.NO_DURABLE_COPY, noCopy.get(BackupAction.RESTORE));
        assertEquals(ActionDisabledReason.NO_DURABLE_COPY, noCopy.get(BackupAction.SYNC));
        assertEquals(
                ActionDisabledReason.SOURCE_UNAVAILABLE,
                BackupActionPolicy.evaluate(capabilities(false, false, false, true, false), List.of(failedGitOnly))
                        .get(BackupAction.CREATE));
    }

    @Test
    void multipleSelectionOnlyAllowsDeleteAndOnlyWhenEveryBackupHasACopy() {
        BackupRow first = row("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa1", "one", 1, 10, 1);
        BackupRow second = row("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa2", "two", 2, 10, 1);
        BackupBrowserCapabilities capabilities = capabilities(false, true, true, true, true);

        Map<BackupAction, ActionDisabledReason> states = BackupActionPolicy.evaluate(capabilities, List.of(first, second));

        assertEquals(ActionDisabledReason.NONE, states.get(BackupAction.DELETE));
        assertEquals(ActionDisabledReason.MULTIPLE_SELECTED, states.get(BackupAction.RESTORE));
        assertEquals(ActionDisabledReason.MULTIPLE_SELECTED, states.get(BackupAction.SYNC));
        assertEquals(ActionDisabledReason.MULTIPLE_SELECTED, states.get(BackupAction.VERIFY));

        BackupRow gone = record(
                "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa3",
                Optional.of("gone"),
                BackupTrigger.MANUAL,
                3,
                10,
                1,
                DestinationResult.failed(DestinationType.GIT, "failed"));
        assertEquals(
                ActionDisabledReason.NO_DURABLE_COPY,
                BackupActionPolicy.evaluate(capabilities, List.of(first, gone)).get(BackupAction.DELETE));
    }

    private static BackupBrowserCapabilities capabilities(
            boolean operationInProgress,
            boolean sourceAvailable,
            boolean createAllowed,
            boolean gitRemoteConfigured,
            boolean managedFolderAvailable) {
        return new BackupBrowserCapabilities(
                operationInProgress,
                sourceAvailable,
                createAllowed ? Optional.empty() : Optional.of(BackupBrowserCapabilities.CreateBlock.WORLD_OFF),
                gitRemoteConfigured,
                managedFolderAvailable,
                Optional.empty());
    }

    private static DestinationResult zip() {
        return DestinationResult.success(DestinationType.ZIP, "archive.zip");
    }

    private static BackupRow row(String backupId, String label, long epochSecond, long bytes, long changedFiles) {
        return record(
                backupId,
                Optional.of(label),
                BackupTrigger.MANUAL,
                epochSecond,
                bytes,
                changedFiles,
                DestinationResult.success(DestinationType.GIT, "artifact-" + backupId));
    }

    private static BackupRow record(
            String backupId,
            Optional<String> label,
            BackupTrigger trigger,
            long epochSecond,
            long bytes,
            long changedFiles,
            DestinationResult destination) {
        BackupId id = BackupId.parse(backupId);
        Instant createdAt = Instant.ofEpochSecond(epochSecond);
        BackupManifest manifest = BackupManifest.create(
                id,
                WORLD_ID,
                "My Base",
                label,
                createdAt,
                trigger,
                4,
                bytes,
                changedFiles,
                "a".repeat(64),
                "b".repeat(64),
                Optional.empty());
        return BackupRow.from(new BackupRecord(
                manifest,
                new BackupResult(id, WORLD_ID, List.of(destination), createdAt.plusSeconds(1))));
    }
}
