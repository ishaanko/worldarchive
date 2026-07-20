package dev.ishaankot.worldarchive.ui.model;

import dev.ishaankot.worldarchive.core.BackupOperation;
import dev.ishaankot.worldarchive.model.BackupId;
import dev.ishaankot.worldarchive.model.BackupResult;
import dev.ishaankot.worldarchive.model.BackupStatus;
import dev.ishaankot.worldarchive.model.DestinationResult;
import dev.ishaankot.worldarchive.model.DestinationStatus;
import dev.ishaankot.worldarchive.model.DestinationType;
import dev.ishaankot.worldarchive.model.SyncStatus;
import dev.ishaankot.worldarchive.model.VerificationStatus;
import java.util.List;
import java.util.Objects;

/** Aggregate presentation summary that preserves partial destination failures. */
public record BackupOutcomeSummary(
        BackupOperation operation,
        BackupId backupId,
        BackupStatus status,
        String headline,
        List<DestinationOutcomeView> destinations) {
    public BackupOutcomeSummary {
        Objects.requireNonNull(operation, "operation");
        Objects.requireNonNull(backupId, "backupId");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(headline, "headline");
        destinations = List.copyOf(destinations);
    }

    public static BackupOutcomeSummary from(BackupResult result) {
        return from(BackupOperation.CREATE, result);
    }

    public static BackupOutcomeSummary from(
            BackupOperation operation,
            BackupResult result) {
        Objects.requireNonNull(operation, "operation");
        Objects.requireNonNull(result, "result");
        List<DestinationResult> applicable = applicableDestinations(
                operation,
                result.destinations());
        List<DestinationOutcomeView> destinations = applicable.stream()
                .map(BackupOutcomeSummary::destination)
                .toList();
        BackupStatus status = operationStatus(operation, result.status(), applicable);
        return new BackupOutcomeSummary(
                operation,
                result.backupId(),
                status,
                headline(operation, status),
                destinations);
    }

    public boolean partialFailure() {
        return status == BackupStatus.PARTIAL_SUCCESS;
    }

    /** Returns an operation-aware status phrase for one destination line. */
    public String destinationStatus(DestinationOutcomeView destination) {
        Objects.requireNonNull(destination, "destination");
        return switch (operation) {
            case DELETE -> deletionStatus(destination.status());
            case SYNC -> synchronizationStatus(destination.syncStatus());
            case VERIFY -> verificationStatus(destination.verificationStatus());
            default -> creationStatus(destination.status());
        };
    }

    private static String deletionStatus(DestinationStatus status) {
        return switch (status) {
            case SUCCESS -> "deleted";
            case PENDING_SYNC -> "deletion pending";
            case FAILED -> "not deleted";
            case SKIPPED -> "not present";
        };
    }

    private static String synchronizationStatus(SyncStatus status) {
        return switch (status) {
            case SYNCED -> "synced";
            case NOT_CONFIGURED -> "not configured";
            case NOT_SYNCED -> "not synced";
            case PENDING -> "sync pending";
            case FAILED -> "sync failed";
        };
    }

    private static String verificationStatus(VerificationStatus status) {
        return switch (status) {
            case VERIFIED -> "verified";
            case FAILED -> "verification failed";
            case UNAVAILABLE -> "verification unavailable";
            case NOT_VERIFIED -> "not verified";
        };
    }

    private static String creationStatus(DestinationStatus status) {
        return switch (status) {
            case SUCCESS -> "success";
            case PENDING_SYNC -> "pending sync";
            case FAILED -> "failed";
            case SKIPPED -> "skipped";
        };
    }

    private static DestinationOutcomeView destination(DestinationResult result) {
        return new DestinationOutcomeView(
                result.destination(),
                result.status(),
                result.verificationStatus(),
                result.syncStatus(),
                result.message());
    }

    private static List<DestinationResult> applicableDestinations(
            BackupOperation operation,
            List<DestinationResult> destinations) {
        if (operation == BackupOperation.SYNC) {
            return destinations.stream()
                    .filter(destination -> destination.destination() == DestinationType.GIT)
                    .filter(BackupOutcomeSummary::durable)
                    .toList();
        }
        if (operation == BackupOperation.VERIFY) {
            return destinations.stream()
                    .filter(BackupOutcomeSummary::durable)
                    .toList();
        }
        return List.copyOf(destinations);
    }

    private static boolean durable(DestinationResult destination) {
        return destination.artifactId().isPresent()
                && (destination.status() == DestinationStatus.SUCCESS
                        || destination.status() == DestinationStatus.PENDING_SYNC);
    }

    private static BackupStatus operationStatus(
            BackupOperation operation,
            BackupStatus backupStatus,
            List<DestinationResult> destinations) {
        return switch (operation) {
            case SYNC -> synchronizationStatus(destinations);
            case VERIFY -> verificationStatus(destinations);
            default -> backupStatus;
        };
    }

    private static BackupStatus synchronizationStatus(List<DestinationResult> destinations) {
        if (destinations.isEmpty()) {
            return BackupStatus.SKIPPED;
        }
        SyncStatus sync = destinations.getFirst().syncStatus();
        return switch (sync) {
            case SYNCED -> BackupStatus.SUCCESS;
            case NOT_SYNCED, PENDING -> BackupStatus.PARTIAL_SUCCESS;
            case FAILED -> BackupStatus.FAILED;
            case NOT_CONFIGURED -> BackupStatus.SKIPPED;
        };
    }

    private static BackupStatus verificationStatus(List<DestinationResult> destinations) {
        if (destinations.isEmpty()) {
            return BackupStatus.SKIPPED;
        }
        if (destinations.stream()
                .anyMatch(destination ->
                        destination.verificationStatus() == VerificationStatus.FAILED)) {
            return BackupStatus.FAILED;
        }
        if (destinations.stream()
                .allMatch(destination ->
                        destination.verificationStatus() == VerificationStatus.VERIFIED)) {
            return BackupStatus.SUCCESS;
        }
        return BackupStatus.PARTIAL_SUCCESS;
    }

    private static String headline(BackupOperation operation, BackupStatus status) {
        return switch (operation) {
            case DELETE -> deletionHeadline(status);
            case SYNC -> synchronizationHeadline(status);
            case VERIFY -> verificationHeadline(status);
            default -> creationHeadline(status);
        };
    }

    private static String deletionHeadline(BackupStatus status) {
        return switch (status) {
            case SUCCESS -> "Backup deleted";
            case PARTIAL_SUCCESS -> "Backup deleted from some destinations";
            case FAILED -> "Backup could not be deleted";
            case SKIPPED -> "Backup removed";
        };
    }

    private static String synchronizationHeadline(BackupStatus status) {
        return switch (status) {
            case SUCCESS -> "Backup synchronized";
            case PARTIAL_SUCCESS -> "Backup synchronization pending";
            case FAILED -> "Backup synchronization failed";
            case SKIPPED -> "Backup synchronization skipped";
        };
    }

    private static String verificationHeadline(BackupStatus status) {
        return switch (status) {
            case SUCCESS -> "Backup verified";
            case PARTIAL_SUCCESS -> "Backup verification incomplete";
            case FAILED -> "Backup verification failed";
            case SKIPPED -> "Backup verification skipped";
        };
    }

    private static String creationHeadline(BackupStatus status) {
        return switch (status) {
            case SUCCESS -> "Backup completed";
            case PARTIAL_SUCCESS -> "Backup completed with destination issues";
            case FAILED -> "Backup failed";
            case SKIPPED -> "Backup skipped";
        };
    }
}
