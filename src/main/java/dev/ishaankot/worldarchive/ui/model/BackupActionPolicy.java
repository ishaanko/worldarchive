package dev.ishaankot.worldarchive.ui.model;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Pure action policy shared by screens and screen tests. */
public final class BackupActionPolicy {
    private BackupActionPolicy() {
    }

    public static Map<BackupAction, BackupActionAvailability> evaluate(
            BackupBrowserCapabilities capabilities,
            Optional<BackupRow> selection) {
        Objects.requireNonNull(capabilities, "capabilities");
        Objects.requireNonNull(selection, "selection");
        EnumMap<BackupAction, BackupActionAvailability> result = new EnumMap<>(BackupAction.class);
        if (capabilities.operationInProgress()) {
            for (BackupAction action : BackupAction.values()) {
                result.put(action, disabled(ActionDisabledReason.OPERATION_IN_PROGRESS));
            }
            return Collections.unmodifiableMap(result);
        }

        result.put(
                BackupAction.CREATE,
                capabilities.createDestinationConfigured()
                        ? enabled()
                        : disabled(ActionDisabledReason.NO_DESTINATION_CONFIGURED));
        result.put(
                BackupAction.OPEN_FOLDER,
                capabilities.managedFolderAvailable()
                        ? enabled()
                        : disabled(ActionDisabledReason.FOLDER_UNAVAILABLE));
        result.put(BackupAction.SETTINGS, enabled());

        if (selection.isEmpty()) {
            disableSelectionActions(result, ActionDisabledReason.NO_SELECTION);
            return Collections.unmodifiableMap(result);
        }
        BackupRow row = selection.orElseThrow();
        BackupActionAvailability durable = row.hasDurableCopy()
                ? enabled()
                : disabled(ActionDisabledReason.NO_DURABLE_COPY);
        result.put(BackupAction.RESTORE, durable);
        result.put(BackupAction.DELETE, durable);
        result.put(BackupAction.VERIFY, durable);

        BackupActionAvailability sync;
        if (!row.git().durable()) {
            sync = disabled(ActionDisabledReason.NO_DURABLE_COPY);
        } else if (!capabilities.gitRemoteConfigured()) {
            sync = disabled(ActionDisabledReason.REMOTE_NOT_CONFIGURED);
        } else {
            sync = enabled();
        }
        result.put(BackupAction.SYNC, sync);
        return Collections.unmodifiableMap(result);
    }

    private static void disableSelectionActions(
            EnumMap<BackupAction, BackupActionAvailability> result,
            ActionDisabledReason reason) {
        result.put(BackupAction.RESTORE, disabled(reason));
        result.put(BackupAction.DELETE, disabled(reason));
        result.put(BackupAction.SYNC, disabled(reason));
        result.put(BackupAction.VERIFY, disabled(reason));
    }

    private static BackupActionAvailability enabled() {
        return BackupActionAvailability.available();
    }

    private static BackupActionAvailability disabled(ActionDisabledReason reason) {
        return BackupActionAvailability.disabled(reason);
    }
}
