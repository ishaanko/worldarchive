package dev.ishaanko.worldarchive.recovery;

import dev.ishaanko.worldarchive.catalog.BackupCatalog;
import dev.ishaanko.worldarchive.catalog.FileBackupDeletionRegistry;
import dev.ishaanko.worldarchive.core.DeleteBackupRequest;
import dev.ishaanko.worldarchive.core.WorldOperationGate;
import dev.ishaanko.worldarchive.model.BackupId;
import dev.ishaanko.worldarchive.model.BackupManifest;
import dev.ishaanko.worldarchive.model.BackupOperation;
import dev.ishaanko.worldarchive.model.BackupRecord;
import dev.ishaanko.worldarchive.model.BackupResult;
import dev.ishaanko.worldarchive.model.DestinationResult;
import dev.ishaanko.worldarchive.model.DestinationStatus;
import dev.ishaanko.worldarchive.model.DestinationType;
import dev.ishaanko.worldarchive.model.OperationId;
import dev.ishaanko.worldarchive.model.OperationPhase;
import dev.ishaanko.worldarchive.model.ProgressListener;
import dev.ishaanko.worldarchive.model.SafeText;
import dev.ishaanko.worldarchive.model.WorldId;
import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Deletes confirmed backups with every copy they have. The backups of one world are deleted
 * together inside that world's gate: one Git operation for their snapshots, their ZIP archives
 * one by one, and one catalog write. Each backup gets its own result. A backup whose listed copies
 * are no longer the ones the player confirmed is left alone; a copy that could not be deleted
 * stays listed, and a backup with no copy left leaves the catalog. Cancel stops the delete before
 * the next world; a world that started finishes, so the list matches the files unless the list
 * could not be saved, which the results of that world then say.
 */
final class RecoveryDeleteOperation {
    private static final Logger LOGGER = LoggerFactory.getLogger("WorldArchive");

    private static final String GONE = "This backup is no longer in the backup list; it may have been deleted already.";

    private static final String CHANGED = "This backup changed after you confirmed the delete. Select Delete again.";

    private final BackupCatalog catalog;

    private final Map<DestinationType, RecoveryDestination> destinations;

    private final FileBackupDeletionRegistry deletions;

    private final WorldOperationGate operationGate;

    private final Clock clock;

    RecoveryDeleteOperation(
            BackupCatalog catalog,
            Map<DestinationType, RecoveryDestination> destinations,
            FileBackupDeletionRegistry deletions,
            WorldOperationGate operationGate,
            Clock clock) {
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.destinations = new EnumMap<>(destinations);
        this.deletions = Objects.requireNonNull(deletions, "deletions");
        this.operationGate = Objects.requireNonNull(operationGate, "operationGate");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Deletes the confirmed backups, world by world. A world whose backup list cannot be read, or
     * whose backups cannot be marked deleted, fails its own backups with nothing deleted, and the
     * next world still runs. Results come back in request order.
     */
    List<BackupResult> delete(
            List<DeleteBackupRequest> requests,
            ProgressListener listener,
            CancellableTask<?> task) throws Exception {
        task.checkpoint();
        if (requests.isEmpty()) {
            throw new BackupRecoveryException("Select at least one backup to delete");
        }
        if (requests.stream().map(DeleteBackupRequest::backupId).distinct().count() != requests.size()) {
            throw new BackupRecoveryException("The same backup was selected twice");
        }
        Map<WorldId, List<DeleteBackupRequest>> byWorld = new LinkedHashMap<>();
        requests.forEach(request -> byWorld
                .computeIfAbsent(request.worldId(), ignored -> new ArrayList<>())
                .add(request));
        Progress progress = new Progress(listener, requests.size());
        Map<BackupId, BackupResult> results = new HashMap<>();
        for (Map.Entry<WorldId, List<DeleteBackupRequest>> world : byWorld.entrySet()) {
            try {
                results.putAll(deleteWorld(world.getKey(), world.getValue(), progress, task));
            } catch (IOException failure) {
                task.checkpoint();
                String reason = "Nothing of this backup was deleted, because WorldArchive could not read or save"
                        + " its backup list (" + SafeText.from(failure, "no details", 300) + "). Try again.";
                world.getValue().forEach(request -> results.put(request.backupId(), failed(request, reason)));
                progress.finished(world.getKey(), world.getValue().size());
            }
        }
        progress.report(byWorld.keySet().iterator().next(), OperationPhase.COMPLETE, "Finished deleting backups");
        return requests.stream().map(request -> results.get(request.backupId())).toList();
    }

    /** Deletes one world's confirmed backups; a backup that changed since it was confirmed is left alone. */
    private Map<BackupId, BackupResult> deleteWorld(
            WorldId worldId,
            List<DeleteBackupRequest> confirmed,
            Progress progress,
            CancellableTask<?> task) throws Exception {
        try (WorldOperationGate.Permit ignored = operationGate.enter(worldId)) {
            progress.report(worldId, OperationPhase.PREPARING, "Deleting " + confirmed.size() + " backups");
            Map<BackupId, BackupRecord> listed = new HashMap<>();
            catalog.list(worldId).forEach(record -> listed.put(record.manifest().backupId(), record));
            Map<BackupId, BackupResult> results = new LinkedHashMap<>();
            List<BackupRecord> matched = new ArrayList<>();
            for (DeleteBackupRequest request : confirmed) {
                BackupRecord record = listed.get(request.backupId());
                if (record == null) {
                    results.put(request.backupId(), failed(request, GONE));
                } else if (!DeleteConfirmation.of(request.copies()).matches(record)) {
                    results.put(request.backupId(), failed(request, CHANGED));
                } else {
                    matched.add(record);
                }
            }
            if (!matched.isEmpty()) {
                results.putAll(task.commitIfActive(() -> deleteMatched(worldId, matched, progress)));
            }
            progress.finished(worldId, confirmed.size());
            return results;
        }
    }

    /**
     * Marks the backups deleted before any file changes, deletes every copy, and then updates the
     * catalog and the marks once each: a backup that keeps a copy stays listed and unmarked.
     */
    private Map<BackupId, BackupResult> deleteMatched(WorldId worldId, List<BackupRecord> records, Progress progress)
            throws IOException {
        List<BackupId> backupIds = records.stream().map(record -> record.manifest().backupId()).toList();
        deletions.mark(backupIds);
        Map<DestinationType, Map<BackupId, DestinationResult>> outcomes = new EnumMap<>(DestinationType.class);
        for (RecoveryDestination destination : destinations.values()) {
            List<BackupRecord> holding = records.stream()
                    .filter(record -> RecoverySupport.copy(record, destination.type()).isPresent())
                    .toList();
            if (!holding.isEmpty()) {
                progress.report(worldId, OperationPhase.WRITING, "Deleting " + label(destination.type()) + " copies");
                outcomes.put(destination.type(), destination.delete(worldId, holding));
            }
        }
        Set<BackupId> listed = unlist(records, outcomes);
        try {
            deletions.unmark(backupIds.stream().filter(listed::contains).toList());
        } catch (IOException failure) {
            LOGGER.warn("Backups that kept a copy are still marked deleted, so a rebuild of the backup list"
                    + " will not list them again: {}", failure.toString());
        }
        Map<BackupId, BackupResult> results = new LinkedHashMap<>();
        for (BackupRecord record : records) {
            List<DestinationResult> attempts = RecoverySupport.copies(record).stream()
                    .map(copy -> outcomes.get(copy.destination()).get(record.manifest().backupId()))
                    .toList();
            results.put(record.manifest().backupId(), result(record.manifest(), attempts));
        }
        return results;
    }

    /**
     * Removes the deleted copies from the catalog in one write, and each backup with no copy left.
     * Returns the backups that stay listed. When the write fails, every backup stays listed, so
     * each deleted copy is reported as failed with the reason.
     */
    private Set<BackupId> unlist(
            List<BackupRecord> records,
            Map<DestinationType, Map<BackupId, DestinationResult>> outcomes) {
        Map<BackupId, UnaryOperator<Optional<BackupRecord>>> changes = new LinkedHashMap<>();
        for (BackupRecord record : records) {
            changes.put(record.manifest().backupId(), current -> current
                    .map(existing -> withoutDeletedCopies(existing, outcomes))
                    .filter(existing -> existing.result().destinations().stream().anyMatch(DestinationResult::isDurable)));
        }
        try {
            return catalog.updateAll(changes).entrySet().stream()
                    .filter(entry -> entry.getValue().isPresent())
                    .map(Map.Entry::getKey)
                    .collect(Collectors.toSet());
        } catch (IOException failure) {
            String reason = "Deleted, but the backup list could not be saved ("
                    + SafeText.from(failure, "it could not be written", 300)
                    + "), so the backup is still listed. Delete it again to update the list.";
            outcomes.replaceAll((type, deleted) -> deleted.entrySet().stream().collect(Collectors.toMap(
                    Map.Entry::getKey,
                    entry -> entry.getValue().status() == DestinationStatus.SUCCESS
                            ? DestinationResult.failed(type, reason)
                            : entry.getValue())));
            return changes.keySet();
        }
    }

    private static BackupRecord withoutDeletedCopies(
            BackupRecord record,
            Map<DestinationType, Map<BackupId, DestinationResult>> outcomes) {
        BackupId backupId = record.manifest().backupId();
        return RecoverySupport.withDestinations(record, record.result().destinations().stream()
                .filter(destination -> !Optional.ofNullable(outcomes.get(destination.destination()))
                        .map(deleted -> deleted.get(backupId))
                        .filter(outcome -> outcome.status() == DestinationStatus.SUCCESS
                                && outcome.artifactId().equals(destination.artifactId()))
                        .isPresent())
                .toList());
    }

    /** The result of a delete that did not start: each confirmed copy failed with the reason. */
    private BackupResult failed(DeleteBackupRequest request, String reason) {
        return new BackupResult(request.backupId(), request.worldId(), DeleteConfirmation.of(request.copies())
                .copyTypes().stream()
                .sorted()
                .map(type -> DestinationResult.failed(type, reason))
                .toList(), clock.instant());
    }

    private BackupResult result(BackupManifest manifest, List<DestinationResult> attempts) {
        Instant now = clock.instant();
        return new BackupResult(manifest.backupId(), manifest.worldId(), attempts,
                now.isBefore(manifest.createdAt()) ? manifest.createdAt() : now);
    }

    private static String label(DestinationType type) {
        return type == DestinationType.ZIP ? "ZIP" : "Git";
    }

    /** Progress of one delete, counted in backups. */
    private static final class Progress {
        private final ProgressListener listener;

        private final OperationId operationId = OperationId.create();

        private final int total;

        private int finished;

        private Progress(ProgressListener listener, int total) {
            this.listener = listener;
            this.total = total;
        }

        private void report(WorldId worldId, OperationPhase phase, String message) {
            RecoverySupport.report(listener, RecoverySupport.worldProgress(
                    operationId, worldId, BackupOperation.DELETE, phase, finished, total, message));
        }

        private void finished(WorldId worldId, int backups) {
            finished += backups;
            report(worldId, OperationPhase.WRITING, "Deleted " + finished + " of " + total + " backups");
        }
    }
}
