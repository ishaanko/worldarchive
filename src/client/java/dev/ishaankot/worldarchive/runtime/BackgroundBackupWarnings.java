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

    static ExitNotice worldExitNotice(BackupResult result, Throwable failure) {
        if (failure != null || result == null) {
            return new ExitNotice(
                    "World save or backup did not complete",
                    NoticeSeverity.ERROR);
        }
        return switch (Objects.requireNonNull(result.status(), "status")) {
            case SUCCESS -> new ExitNotice(
                    "Backup finished. You can safely quit Minecraft.",
                    NoticeSeverity.SUCCESS);
            case PARTIAL_SUCCESS -> new ExitNotice(
                    "Backup created with warnings",
                    NoticeSeverity.WARNING);
            case FAILED -> new ExitNotice(
                    "Backup failed; world was saved",
                    NoticeSeverity.ERROR);
            case SKIPPED -> new ExitNotice(
                    "Backup skipped; world was saved",
                    NoticeSeverity.WARNING);
        };
    }

    static ExitNotice worldExitStartedNotice() {
        return new ExitNotice(
                "Creating backup... Keep Minecraft open until it finishes.",
                NoticeSeverity.SUCCESS);
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

    enum NoticeSeverity {
        SUCCESS,
        WARNING,
        ERROR
    }

    record ExitNotice(String message, NoticeSeverity severity) {
        ExitNotice {
            Objects.requireNonNull(message, "message");
            Objects.requireNonNull(severity, "severity");
        }
    }
}
