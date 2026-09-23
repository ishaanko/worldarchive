package dev.ishaanko.worldarchive.ui.model;

import dev.ishaanko.worldarchive.model.BackupId;
import dev.ishaanko.worldarchive.model.BackupResult;
import dev.ishaanko.worldarchive.model.BackupStatus;
import dev.ishaanko.worldarchive.model.DestinationResult;
import dev.ishaanko.worldarchive.model.DestinationStatus;
import dev.ishaanko.worldarchive.model.SafeText;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * What a delete of one or more backups did: how many are gone, one line for each copy that is
 * still there, and one for each note of a deleted copy, such as a copy on a remote that is no
 * longer in the settings. Lines name the backup the way the browser does. A backup counts as
 * deleted only when no copy of it is left, because until then it stays listed.
 */
public record DeleteBatchSummary(
        BackupStatus status,
        String headline,
        List<String> details) {
    private static final int MAXIMUM_DETAIL_LINES = 6;

    private static final int MAXIMUM_REASON_LENGTH = 160;

    public DeleteBatchSummary {
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(headline, "headline");
        details = List.copyOf(details);
    }

    /** Summarizes {@code results}; {@code rows} are the browser rows that were confirmed. */
    public static DeleteBatchSummary from(List<BackupResult> results, List<BackupRow> rows) {
        Map<BackupId, BackupRow> confirmed = rows.stream()
                .collect(Collectors.toMap(BackupRow::backupId, Function.identity(), (first, second) -> first));
        int deleted = 0;
        List<String> lines = new ArrayList<>();
        for (BackupResult result : results) {
            String name = confirmed.containsKey(result.backupId())
                    ? BackupText.name(confirmed.get(result.backupId()))
                    : result.backupId().toString();
            boolean gone = true;
            for (DestinationResult destination : result.destinations()) {
                boolean kept = leftBehind(destination);
                gone &= !kept;
                if (kept || destination.message().isPresent()) {
                    lines.add(name + BackupText.SEPARATOR + line(destination));
                }
            }
            if (gone) {
                deleted++;
            }
        }
        List<String> details = new ArrayList<>(lines.subList(0, Math.min(lines.size(), MAXIMUM_DETAIL_LINES)));
        if (lines.size() > MAXIMUM_DETAIL_LINES) {
            details.add("…and " + (lines.size() - MAXIMUM_DETAIL_LINES) + " more");
        }
        int total = results.size();
        if (deleted == total) {
            return new DeleteBatchSummary(
                    BackupStatus.SUCCESS,
                    total == 1 ? "Deleted 1 backup" : "Deleted " + total + " backups",
                    details);
        }
        if (deleted == 0) {
            return new DeleteBatchSummary(BackupStatus.FAILED, "No backups were deleted", details);
        }
        return new DeleteBatchSummary(
                BackupStatus.PARTIAL_SUCCESS,
                "Deleted " + deleted + " of " + total + " backups",
                details);
    }

    /** True when the delete kept this copy: it failed, or its remote deletion still waits. */
    private static boolean leftBehind(DestinationResult destination) {
        return destination.status() == DestinationStatus.FAILED
                || destination.status() == DestinationStatus.PENDING_SYNC;
    }

    private static String line(DestinationResult destination) {
        String reason = destination.message()
                .map(message -> SafeText.clean(message, MAXIMUM_REASON_LENGTH))
                .orElse(destination.status() == DestinationStatus.PENDING_SYNC ? "deletion pending" : "not deleted");
        return destination.destination() + ": " + reason;
    }
}
