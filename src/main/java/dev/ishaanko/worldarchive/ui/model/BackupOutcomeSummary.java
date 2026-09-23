package dev.ishaanko.worldarchive.ui.model;

import dev.ishaanko.worldarchive.model.BackupOperation;
import dev.ishaanko.worldarchive.model.BackupResult;
import dev.ishaanko.worldarchive.model.BackupStatus;
import dev.ishaanko.worldarchive.model.DestinationResult;
import dev.ishaanko.worldarchive.model.DestinationStatus;
import dev.ishaanko.worldarchive.model.DestinationType;
import dev.ishaanko.worldarchive.model.SafeText;
import dev.ishaanko.worldarchive.model.SyncStatus;
import dev.ishaanko.worldarchive.model.VerificationStatus;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/**
 * What a create, sync or verify of one backup did, for the operation screen: the overall status,
 * a headline, and one line per copy that the operation touched. Restores and deletes have their
 * own summaries: the restore screen names the new world, and {@link DeleteBatchSummary} counts
 * deleted backups.
 */
public record BackupOutcomeSummary(
        BackupStatus status,
        String headline,
        List<String> lines) {
    /** Keeps one long destination message from pushing the other lines off the screen. */
    private static final int MAXIMUM_DETAIL_LENGTH = 160;

    public BackupOutcomeSummary {
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(headline, "headline");
        lines = List.copyOf(lines);
    }

    /**
     * Summarizes the result of a create, sync or verify.
     *
     * @throws IllegalArgumentException for a restore or a delete
     */
    public static BackupOutcomeSummary from(BackupOperation operation, BackupResult result) {
        return switch (operation) {
            case CREATE -> creation(result);
            case SYNC -> synchronization(result);
            case VERIFY -> verification(result);
            case RESTORE, DELETE -> throw new IllegalArgumentException(operation + " has its own summary");
        };
    }

    private static BackupOutcomeSummary creation(BackupResult result) {
        String headline = switch (result.status()) {
            case SUCCESS -> "Backup completed";
            case PARTIAL_SUCCESS -> "Backup completed with destination issues";
            case FAILED -> "Backup failed";
            case SKIPPED -> "Backup skipped";
        };
        return new BackupOutcomeSummary(result.status(), headline,
                lines(result.destinations(), destination -> creationPhrase(destination.status())));
    }

    private static BackupOutcomeSummary synchronization(BackupResult result) {
        List<DestinationResult> git = result.destinations().stream()
                .filter(destination -> destination.destination() == DestinationType.GIT)
                .filter(DestinationResult::isDurable)
                .toList();
        BackupStatus status = synchronizationStatus(git);
        String headline = switch (status) {
            case SUCCESS -> "Backup synchronized";
            case PARTIAL_SUCCESS -> "Backup synchronization pending";
            case FAILED -> "Backup synchronization failed";
            case SKIPPED -> "Backup synchronization skipped";
        };
        return new BackupOutcomeSummary(status, headline,
                lines(git, destination -> synchronizationPhrase(destination.syncStatus())));
    }

    private static BackupOutcomeSummary verification(BackupResult result) {
        List<DestinationResult> copies = result.destinations().stream()
                .filter(DestinationResult::isDurable)
                .toList();
        BackupStatus status = verificationStatus(copies);
        String headline = switch (status) {
            case SUCCESS -> "Backup verified";
            case PARTIAL_SUCCESS -> "Backup verification incomplete";
            case FAILED -> "Backup verification failed";
            case SKIPPED -> "Backup verification skipped";
        };
        return new BackupOutcomeSummary(status, headline,
                lines(copies, destination -> verificationPhrase(destination.verificationStatus())));
    }

    /** One line per destination, such as {@code GIT: sync failed · The remote refused}. */
    private static List<String> lines(
            List<DestinationResult> destinations, Function<DestinationResult, String> phrase) {
        return destinations.stream()
                .map(destination -> destination.destination() + ": " + phrase.apply(destination) + detail(destination))
                .toList();
    }

    private static String detail(DestinationResult destination) {
        return destination.message()
                .map(message -> BackupText.SEPARATOR + SafeText.clean(message, MAXIMUM_DETAIL_LENGTH))
                .orElse("");
    }

    private static String creationPhrase(DestinationStatus status) {
        return switch (status) {
            case SUCCESS -> "success";
            case PENDING_SYNC -> "pending sync";
            case FAILED -> "failed";
            case SKIPPED -> "skipped";
        };
    }

    private static String synchronizationPhrase(SyncStatus status) {
        return switch (status) {
            case SYNCED -> "synced";
            case NOT_CONFIGURED -> "not configured";
            case NOT_SYNCED -> "not synced";
            case PENDING -> "sync pending";
            case FAILED -> "sync failed";
        };
    }

    private static String verificationPhrase(VerificationStatus status) {
        return switch (status) {
            case VERIFIED -> "verified";
            case FAILED -> "verification failed";
            case UNAVAILABLE -> "verification unavailable";
            case NOT_VERIFIED -> "not verified";
        };
    }

    private static BackupStatus synchronizationStatus(List<DestinationResult> git) {
        if (git.isEmpty()) {
            return BackupStatus.SKIPPED;
        }
        return switch (git.getFirst().syncStatus()) {
            case SYNCED -> BackupStatus.SUCCESS;
            case NOT_SYNCED, PENDING -> BackupStatus.PARTIAL_SUCCESS;
            case FAILED -> BackupStatus.FAILED;
            case NOT_CONFIGURED -> BackupStatus.SKIPPED;
        };
    }

    private static BackupStatus verificationStatus(List<DestinationResult> copies) {
        if (copies.isEmpty()) {
            return BackupStatus.SKIPPED;
        }
        if (copies.stream().anyMatch(copy -> copy.verificationStatus() == VerificationStatus.FAILED)) {
            return BackupStatus.FAILED;
        }
        return copies.stream().allMatch(copy -> copy.verificationStatus() == VerificationStatus.VERIFIED)
                ? BackupStatus.SUCCESS
                : BackupStatus.PARTIAL_SUCCESS;
    }
}
