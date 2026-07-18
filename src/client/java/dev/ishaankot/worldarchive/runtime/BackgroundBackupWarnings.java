package dev.ishaankot.worldarchive.runtime;

import dev.ishaankot.worldarchive.model.BackupResult;
import dev.ishaankot.worldarchive.model.BackupStatus;
import java.util.Objects;
import java.util.Optional;

/** Credential-safe notices for unattended backup outcomes. */
final class BackgroundBackupWarnings {
    private BackgroundBackupWarnings() {
    }

    static Optional<String> scheduled(BackupResult result, Throwable failure) {
        return warning("Scheduled", result, failure);
    }

    static Optional<String> worldExit(BackupResult result, Throwable failure) {
        return warning("World-exit", result, failure);
    }

    private static Optional<String> warning(
            String trigger,
            BackupResult result,
            Throwable failure) {
        if (failure != null || result == null) {
            return Optional.of(trigger + " backup did not complete");
        }
        BackupStatus status = Objects.requireNonNull(result.status(), "status");
        return switch (status) {
            case SUCCESS -> Optional.empty();
            case PARTIAL_SUCCESS -> Optional.of(trigger + " backup completed with warnings");
            case FAILED -> Optional.of(trigger + " backup failed");
            case SKIPPED -> Optional.empty();
        };
    }
}
