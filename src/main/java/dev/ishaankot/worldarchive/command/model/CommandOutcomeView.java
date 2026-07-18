package dev.ishaankot.worldarchive.command.model;

import dev.ishaankot.worldarchive.model.BackupId;
import dev.ishaankot.worldarchive.model.BackupResult;
import dev.ishaankot.worldarchive.model.BackupStatus;
import java.util.List;
import java.util.Objects;

/** Concise aggregate command outcome retaining independent destination details. */
public record CommandOutcomeView(
        BackupId backupId,
        BackupStatus status,
        String headline,
        List<CommandDestinationOutcome> destinations) {
    public CommandOutcomeView {
        Objects.requireNonNull(backupId, "backupId");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(headline, "headline");
        destinations = List.copyOf(destinations);
    }

    public static CommandOutcomeView from(BackupResult result) {
        Objects.requireNonNull(result, "result");
        List<CommandDestinationOutcome> destinations = result.destinations().stream()
                .map(destination -> new CommandDestinationOutcome(
                        destination.destination(),
                        destination.status(),
                        destination.message()))
                .toList();
        return new CommandOutcomeView(
                result.backupId(),
                result.status(),
                headline(result.status()),
                destinations);
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
