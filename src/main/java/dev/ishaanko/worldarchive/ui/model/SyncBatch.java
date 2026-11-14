package dev.ishaanko.worldarchive.ui.model;

import dev.ishaanko.worldarchive.model.BackupId;
import dev.ishaanko.worldarchive.model.BackupResult;
import dev.ishaanko.worldarchive.model.BackupStatus;
import dev.ishaanko.worldarchive.model.DestinationResult;
import dev.ishaanko.worldarchive.model.DestinationStatus;
import dev.ishaanko.worldarchive.model.DestinationType;
import dev.ishaanko.worldarchive.model.OperationProgress;
import dev.ishaanko.worldarchive.model.SafeText;
import dev.ishaanko.worldarchive.model.SyncStatus;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.IntFunction;
import java.util.stream.Collectors;

/**
 * The backups one Sync uploads, oldest first, so the remote's main ends at the newest of them.
 * The browser starts the upload with {@link #backupIds}, shows which backup it is on with
 * {@link #inBatch}, and shows the outcome with {@link #summarize}.
 */
public record SyncBatch(List<BackupRow> rows) {
    private static final int MAXIMUM_MESSAGE_LENGTH = 512;

    private static final int MAXIMUM_REASON_LENGTH = 60;

    public SyncBatch {
        rows = List.copyOf(rows);
        if (rows.isEmpty()) {
            throw new IllegalArgumentException("A sync needs at least one backup");
        }
    }

    /** The selected backups, oldest first. */
    public static SyncBatch of(List<BackupRow> selected) {
        return new SyncBatch(selected.stream()
                .sorted(Comparator.comparing(BackupRow::createdAt).thenComparing(BackupRow::backupId))
                .toList());
    }

    /** The backups to upload, in upload order. */
    public List<BackupId> backupIds() {
        return rows.stream().map(BackupRow::backupId).toList();
    }

    /**
     * A progress event of one backup's upload, measured over the whole batch, with the message
     * that {@code message} makes from the backup's position (1 for the first); empty for an event
     * of no backup in the batch.
     */
    public Optional<OperationProgress> inBatch(OperationProgress update, IntFunction<String> message) {
        int index = update.backupId().map(backupIds()::indexOf).orElse(-1);
        if (index < 0) {
            return Optional.empty();
        }
        long unit = Math.max(update.totalUnits(), 1);
        long done = update.totalUnits() == 0 ? 0 : update.completedUnits();
        return Optional.of(new OperationProgress(
                update.operationId(),
                update.worldId(),
                update.backupId(),
                update.operation(),
                update.phase(),
                index * unit + done,
                rows.size() * unit,
                SafeText.clean(message.apply(index + 1), MAXIMUM_MESSAGE_LENGTH)));
    }

    /** What the sync did: a backup counts as uploaded only when its Git copy is on the remote. */
    public Summary summarize(List<BackupResult> results) {
        Map<BackupId, BackupRow> byId = rows.stream()
                .collect(Collectors.toMap(BackupRow::backupId, Function.identity()));
        int uploaded = 0;
        List<Failure> failures = new ArrayList<>();
        for (BackupResult result : results) {
            Optional<DestinationResult> git = result.destinations().stream()
                    .filter(destination -> destination.destination() == DestinationType.GIT)
                    .findFirst();
            if (git.filter(SyncBatch::onRemote).isPresent()) {
                uploaded++;
                continue;
            }
            BackupRow row = byId.get(result.backupId());
            failures.add(new Failure(
                    row == null ? result.backupId().toString() : BackupText.name(row),
                    git.flatMap(DestinationResult::message)
                            .map(reason -> SafeText.clean(reason, MAXIMUM_REASON_LENGTH))
                            .filter(reason -> !reason.isEmpty())));
        }
        return new Summary(uploaded, results.size(), failures);
    }

    private static boolean onRemote(DestinationResult git) {
        return git.status() == DestinationStatus.SUCCESS && git.syncStatus() == SyncStatus.SYNCED;
    }

    /** How many backups uploaded out of how many, and each one that did not. */
    public record Summary(int uploaded, int total, List<Failure> failures) {
        /**
         * The failures the result screen names; it counts the rest. Three names with a short
         * reason each fit the screen's seven rows; every row in the list keeps its full reason.
         */
        public static final int MAXIMUM_SHOWN = 3;

        public Summary {
            failures = List.copyOf(failures);
            if (uploaded < 0 || uploaded + failures.size() != total) {
                throw new IllegalArgumentException("Every backup is either uploaded or failed");
            }
        }

        /** SUCCESS when every backup uploaded, FAILED when none did, PARTIAL_SUCCESS otherwise. */
        public BackupStatus status() {
            if (failures.isEmpty()) {
                return BackupStatus.SUCCESS;
            }
            return uploaded == 0 ? BackupStatus.FAILED : BackupStatus.PARTIAL_SUCCESS;
        }

        public List<Failure> shown() {
            return failures.subList(0, Math.min(failures.size(), MAXIMUM_SHOWN));
        }

        public int more() {
            return failures.size() - shown().size();
        }
    }

    /** A backup that did not upload, named as the browser shows it, and why when the upload said. */
    public record Failure(String name, Optional<String> reason) {
        public Failure {
            Objects.requireNonNull(name, "name");
            reason = Objects.requireNonNull(reason, "reason");
        }
    }
}
