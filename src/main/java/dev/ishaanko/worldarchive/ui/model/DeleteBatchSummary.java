package dev.ishaanko.worldarchive.ui.model;

import dev.ishaanko.worldarchive.core.BackupOperation;
import dev.ishaanko.worldarchive.model.BackupResult;
import dev.ishaanko.worldarchive.model.BackupStatus;
import dev.ishaanko.worldarchive.model.DestinationStatus;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Presentation summary for a multi-backup delete: counts plus one line per problem. */
public record DeleteBatchSummary(
        BackupStatus status,
        String headline,
        List<String> details) {
    private static final int MAXIMUM_DETAIL_LINES = 6;

    public DeleteBatchSummary {
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(headline, "headline");
        details = List.copyOf(details);
    }

    public static DeleteBatchSummary from(List<BackupResult> results) {
        Objects.requireNonNull(results, "results");
        int removed = 0;
        List<String> problems = new ArrayList<>();
        for (BackupResult result : results) {
            BackupOutcomeSummary summary = BackupOutcomeSummary.from(BackupOperation.DELETE, result);
            if (summary.status() == BackupStatus.SUCCESS || summary.status() == BackupStatus.SKIPPED) {
                removed++;
                continue;
            }
            if (summary.status() == BackupStatus.PARTIAL_SUCCESS) {
                removed++;
            }
            for (DestinationOutcomeView destination : summary.destinations()) {
                if (destination.status() == DestinationStatus.FAILED) {
                    problems.add(shortId(result) + " · " + destination.destination() + ": "
                            + destination.detail().orElse("not deleted"));
                }
            }
        }
        int total = results.size();
        BackupStatus status;
        String headline;
        if (problems.isEmpty()) {
            status = BackupStatus.SUCCESS;
            headline = "Deleted " + total + " backups";
        } else if (removed == 0) {
            status = BackupStatus.FAILED;
            headline = "No backups were deleted";
        } else {
            status = BackupStatus.PARTIAL_SUCCESS;
            headline = "Deleted " + removed + " of " + total + " backups";
        }
        List<String> details = new ArrayList<>(problems.subList(
                0, Math.min(problems.size(), MAXIMUM_DETAIL_LINES)));
        if (problems.size() > MAXIMUM_DETAIL_LINES) {
            details.add("…and " + (problems.size() - MAXIMUM_DETAIL_LINES) + " more problems");
        }
        return new DeleteBatchSummary(status, headline, details);
    }

    private static String shortId(BackupResult result) {
        return result.backupId().toString().substring(0, 8);
    }
}
