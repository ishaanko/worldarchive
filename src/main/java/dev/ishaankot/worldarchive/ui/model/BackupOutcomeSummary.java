package dev.ishaankot.worldarchive.ui.model;

import dev.ishaankot.worldarchive.model.BackupId;
import dev.ishaankot.worldarchive.model.BackupResult;
import dev.ishaankot.worldarchive.model.BackupStatus;
import dev.ishaankot.worldarchive.model.DestinationResult;
import java.util.List;
import java.util.Objects;

/** Aggregate presentation summary that preserves partial destination failures. */
public record BackupOutcomeSummary(
        BackupId backupId,
        BackupStatus status,
        String headline,
        List<DestinationOutcomeView> destinations) {
    public BackupOutcomeSummary {
        Objects.requireNonNull(backupId, "backupId");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(headline, "headline");
        destinations = List.copyOf(destinations);
    }

    public static BackupOutcomeSummary from(BackupResult result) {
        Objects.requireNonNull(result, "result");
        List<DestinationOutcomeView> destinations = result.destinations().stream()
                .map(BackupOutcomeSummary::destination)
                .toList();
        return new BackupOutcomeSummary(
                result.backupId(),
                result.status(),
                headline(result.status()),
                destinations);
    }

    public boolean partialFailure() {
        return status == BackupStatus.PARTIAL_SUCCESS;
    }

    private static DestinationOutcomeView destination(DestinationResult result) {
        return new DestinationOutcomeView(result.destination(), result.status(), result.message());
    }

    private static String headline(BackupStatus status) {
        return switch (status) {
            case SUCCESS -> "Backup completed";
            case PARTIAL_SUCCESS -> "Backup completed with destination issues";
            case FAILED -> "Backup failed";
            case SKIPPED -> "Backup skipped";
        };
    }
}
