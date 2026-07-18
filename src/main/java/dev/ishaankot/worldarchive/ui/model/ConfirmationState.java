package dev.ishaankot.worldarchive.ui.model;

import dev.ishaankot.worldarchive.model.BackupId;
import java.util.Objects;
import java.util.Optional;

/** Immutable pending confirmation state. */
public record ConfirmationState(
        ConfirmationKind kind,
        BackupId backupId,
        String title,
        String prompt,
        Optional<RestoreChoice> restoreChoice,
        boolean destructive) {
    public ConfirmationState {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(backupId, "backupId");
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(prompt, "prompt");
        restoreChoice = Objects.requireNonNull(restoreChoice, "restoreChoice");
        if ((kind == ConfirmationKind.RESTORE) != restoreChoice.isPresent()) {
            throw new IllegalArgumentException("Only restore confirmations carry a restore choice");
        }
    }

    public static ConfirmationState delete(BackupRow row) {
        Objects.requireNonNull(row, "row");
        return new ConfirmationState(
                ConfirmationKind.DELETE,
                row.backupId(),
                "Delete backup?",
                "Delete backup " + row.backupId() + " from every available destination?",
                Optional.empty(),
                true);
    }

    public static ConfirmationState restore(BackupRow row, RestoreChoice choice) {
        Objects.requireNonNull(row, "row");
        Objects.requireNonNull(choice, "choice");
        return new ConfirmationState(
                ConfirmationKind.RESTORE,
                row.backupId(),
                "Restore backup?",
                "Restore backup " + row.backupId() + " as a new world copy?",
                Optional.of(choice),
                false);
    }
}
