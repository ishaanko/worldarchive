package dev.ishaanko.worldarchive.ui.model;

import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/** Decides which backup-browser actions can run for the current selection, and why one cannot. */
public final class BackupActionPolicy {
    private BackupActionPolicy() {
    }

    /**
     * The reason each action cannot run, or {@link ActionDisabledReason#NONE} when it can. Delete
     * accepts any number of backups; Restore, Sync and Verify need exactly one.
     */
    public static Map<BackupAction, ActionDisabledReason> evaluate(
            BackupBrowserCapabilities capabilities,
            List<BackupRow> selection) {
        EnumMap<BackupAction, ActionDisabledReason> reasons = new EnumMap<>(BackupAction.class);
        for (BackupAction action : BackupAction.values()) {
            reasons.put(action, capabilities.operationInProgress()
                    ? ActionDisabledReason.OPERATION_IN_PROGRESS
                    : reason(action, capabilities, selection));
        }
        return Collections.unmodifiableMap(reasons);
    }

    private static ActionDisabledReason reason(
            BackupAction action,
            BackupBrowserCapabilities capabilities,
            List<BackupRow> selection) {
        return switch (action) {
            case CREATE -> create(capabilities);
            case RESTORE, VERIFY -> restoreOrVerify(selection);
            case DELETE -> delete(selection);
            case SYNC -> sync(capabilities, selection);
            case OPEN_FOLDER -> capabilities.managedFolderAvailable()
                    ? ActionDisabledReason.NONE
                    : ActionDisabledReason.FOLDER_UNAVAILABLE;
            case STORAGE, SETTINGS -> ActionDisabledReason.NONE;
        };
    }

    private static ActionDisabledReason create(BackupBrowserCapabilities capabilities) {
        if (!capabilities.sourceAvailable()) {
            return ActionDisabledReason.SOURCE_UNAVAILABLE;
        }
        return capabilities.createBlock().isPresent()
                ? ActionDisabledReason.CREATE_BLOCKED
                : ActionDisabledReason.NONE;
    }

    private static ActionDisabledReason delete(List<BackupRow> selection) {
        if (selection.isEmpty()) {
            return ActionDisabledReason.NO_SELECTION;
        }
        return selection.stream().allMatch(BackupRow::hasDurableCopy)
                ? ActionDisabledReason.NONE
                : ActionDisabledReason.NO_DURABLE_COPY;
    }

    private static ActionDisabledReason restoreOrVerify(List<BackupRow> selection) {
        ActionDisabledReason count = exactlyOne(selection);
        if (count != ActionDisabledReason.NONE) {
            return count;
        }
        return selection.getFirst().hasDurableCopy()
                ? ActionDisabledReason.NONE
                : ActionDisabledReason.NO_DURABLE_COPY;
    }

    private static ActionDisabledReason sync(
            BackupBrowserCapabilities capabilities,
            List<BackupRow> selection) {
        ActionDisabledReason count = exactlyOne(selection);
        if (count != ActionDisabledReason.NONE) {
            return count;
        }
        if (!selection.getFirst().git().durable()) {
            return ActionDisabledReason.NO_DURABLE_COPY;
        }
        return capabilities.gitRemoteConfigured()
                ? ActionDisabledReason.NONE
                : ActionDisabledReason.REMOTE_NOT_CONFIGURED;
    }

    private static ActionDisabledReason exactlyOne(List<BackupRow> selection) {
        if (selection.isEmpty()) {
            return ActionDisabledReason.NO_SELECTION;
        }
        return selection.size() > 1 ? ActionDisabledReason.MULTIPLE_SELECTED : ActionDisabledReason.NONE;
    }
}
