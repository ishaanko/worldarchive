package dev.ishaanko.worldarchive.recovery;

import dev.ishaanko.worldarchive.catalog.BackupCatalog;
import dev.ishaanko.worldarchive.catalog.BackupDeletionRegistry;
import dev.ishaanko.worldarchive.core.BackupOperation;
import dev.ishaanko.worldarchive.core.ConfirmationLedger;
import dev.ishaanko.worldarchive.core.DeleteBackupRequest;
import dev.ishaanko.worldarchive.core.DeletePreparation;
import dev.ishaanko.worldarchive.core.OperationId;
import dev.ishaanko.worldarchive.core.OperationPhase;
import dev.ishaanko.worldarchive.core.OperationProgress;
import dev.ishaanko.worldarchive.core.ProgressListener;
import dev.ishaanko.worldarchive.core.WorldOperationGate;
import dev.ishaanko.worldarchive.model.BackupId;
import dev.ishaanko.worldarchive.model.BackupManifest;
import dev.ishaanko.worldarchive.model.BackupRecord;
import dev.ishaanko.worldarchive.model.BackupResult;
import dev.ishaanko.worldarchive.model.DestinationResult;
import dev.ishaanko.worldarchive.model.DestinationStatus;
import dev.ishaanko.worldarchive.model.SensitiveDataRedactor;
import dev.ishaanko.worldarchive.model.WorldId;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Executes delete confirmation issuance and the destination-deletion operation. */
final class RecoveryDeleteOperation {
    private static final int MAXIMUM_FAILURE_REASON_LENGTH = 200;

    private final BackupCatalog catalog;

    private final RecoveryDestinations destinations;

    private final BackupDeletionRegistry deletions;

    private final WorldOperationGate operationGate;

    private final Executor executor;

    private final Clock clock;

    private final Duration confirmationLifetime;

    private final ConfirmationLedger<OperationId, DeleteConfirmation> confirmations =
            new ConfirmationLedger<>(DeleteConfirmation::expiresAt);

    RecoveryDeleteOperation(
            BackupCatalog catalog,
            RecoveryDestinations destinations,
            BackupDeletionRegistry deletions,
            WorldOperationGate operationGate,
            Executor executor,
            Clock clock,
            Duration confirmationLifetime) {
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.destinations = Objects.requireNonNull(destinations, "destinations");
        this.deletions = Objects.requireNonNull(deletions, "deletions");
        this.operationGate = Objects.requireNonNull(operationGate, "operationGate");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.confirmationLifetime =
                Objects.requireNonNull(confirmationLifetime, "confirmationLifetime");
    }

    DeletePreparation prepareDeleteBlocking(BackupId backupId) throws IOException {
        BackupRecord record = RecoverySupport.requireRecord(catalog, backupId);
        Instant now = clock.instant();
        confirmations.expireStaleEntries(now);
        ConfirmationLedger.Issued<OperationId, DeleteConfirmation> issued = confirmations.putUnique(
                OperationId::create,
                token -> DeleteConfirmation.create(record, now.plus(confirmationLifetime)));
        long artifacts = RecoverySupport.presentDestinations(record).size();
        String description = "Delete backup " + backupId + " for "
                + record.manifest().worldName() + " from " + artifacts + " destination(s)";
        return new DeletePreparation(
                backupId, issued.key(), description, issued.value().expiresAt());
    }

    BackupResult deleteBlocking(
            DeleteBackupRequest request,
            ProgressListener progressListener,
            OperationCancellation cancellation) throws Exception {
        cancellation.checkpoint();
        DeleteConfirmation confirmation = claimConfirmation(request);
        BackupRecord record = RecoverySupport.requireRecord(catalog, request.backupId());
        try (WorldOperationGate.Permit ignored = operationGate.enter(record.manifest().worldId())) {
            return deleteInsideGate(confirmation, progressListener, cancellation);
        }
    }

    /**
     * Deletes several confirmed backups in one operation. Every confirmation is claimed
     * before any storage changes, so one bad token deletes nothing. Backups in the same
     * world share one gate permit and run concurrently; each destination store still
     * serializes its own writes. Results come back in request order, and a backup whose
     * deletion could not start is reported as failed rather than dropped.
     */
    List<BackupResult> deleteManyBlocking(
            List<DeleteBackupRequest> requests,
            ProgressListener progressListener,
            OperationCancellation cancellation) throws Exception {
        cancellation.checkpoint();
        if (requests.isEmpty()) {
            throw new BackupRecoveryException("Select at least one backup to delete");
        }
        Map<BackupId, DeleteConfirmation> claimed = claimAll(requests);
        Map<WorldId, List<DeleteConfirmation>> byWorld = new LinkedHashMap<>();
        for (DeleteConfirmation confirmation : claimed.values()) {
            byWorld.computeIfAbsent(confirmation.manifest().worldId(), ignored -> new ArrayList<>())
                    .add(confirmation);
        }
        OperationId operationId = OperationId.create();
        int total = claimed.size();
        AtomicInteger completed = new AtomicInteger();
        Map<BackupId, BackupResult> results = new ConcurrentHashMap<>();
        for (Map.Entry<WorldId, List<DeleteConfirmation>> world : byWorld.entrySet()) {
            RecoverySupport.report(progressListener, batchProgress(
                    operationId, world.getKey(), OperationPhase.PREPARING,
                    completed.get(), total, "Preparing to delete " + total + " backups"));
            try (WorldOperationGate.Permit ignored = operationGate.enter(world.getKey())) {
                cancellation.checkpoint();
                List<Runnable> tasks = new ArrayList<>();
                for (DeleteConfirmation confirmation : world.getValue()) {
                    tasks.add(() -> {
                        results.put(confirmation.backupId(), deleteOrReport(
                                confirmation, cancellation));
                        int done = completed.incrementAndGet();
                        RecoverySupport.report(progressListener, batchProgress(
                                operationId, world.getKey(), OperationPhase.WRITING,
                                done, total, "Deleting backups (" + done + " of " + total + ")"));
                    });
                }
                runAll(tasks);
            }
        }
        RecoverySupport.report(progressListener, batchProgress(
                operationId, byWorld.keySet().iterator().next(), OperationPhase.COMPLETE,
                total, total, "Finished deleting backups"));
        return requests.stream()
                .map(request -> results.get(request.backupId()))
                .toList();
    }

    private BackupResult deleteOrReport(
            DeleteConfirmation confirmation,
            OperationCancellation cancellation) {
        try {
            return deleteInsideGate(confirmation, ProgressListener.NO_OP, cancellation);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return failedResult(confirmation, exception);
        } catch (Exception exception) {
            return failedResult(confirmation, exception);
        }
    }

    /**
     * Runs the tasks with the executor and this thread pulling from one shared queue. The
     * caller never blocks on work that only the executor could start, so a bounded or
     * single-threaded executor cannot deadlock. Every task finishes even after an
     * interrupt, because each one is committing storage changes.
     */
    private void runAll(List<Runnable> tasks) throws InterruptedException {
        Queue<Runnable> pending = new ConcurrentLinkedQueue<>(tasks);
        CountDownLatch finished = new CountDownLatch(tasks.size());
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Runnable drain = () -> {
            Runnable task;
            while ((task = pending.poll()) != null) {
                try {
                    task.run();
                } catch (Throwable exception) {
                    failure.compareAndSet(null, exception);
                } finally {
                    finished.countDown();
                }
            }
        };
        for (int helper = 1; helper < tasks.size(); helper++) {
            try {
                executor.execute(drain);
            } catch (RejectedExecutionException exception) {
                break;
            }
        }
        drain.run();
        boolean interrupted = false;
        while (true) {
            try {
                finished.await();
                break;
            } catch (InterruptedException exception) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
        Throwable failed = failure.get();
        if (failed != null) {
            throw new CompletionException(failed);
        }
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedException("Backup deletion was cancelled");
        }
    }

    /**
     * Claims every token or none. A rejected batch hands the already claimed tokens back
     * to the ledger, so the caller can fix the bad request and retry with the same tokens.
     */
    private Map<BackupId, DeleteConfirmation> claimAll(List<DeleteBackupRequest> requests) {
        Map<BackupId, DeleteConfirmation> claimed = new LinkedHashMap<>();
        Map<OperationId, DeleteConfirmation> restorable = new LinkedHashMap<>();
        try {
            for (DeleteBackupRequest request : requests) {
                if (claimed.containsKey(request.backupId())) {
                    throw new BackupRecoveryException("The same backup was selected twice");
                }
                // Record the token before validating it, so a mismatch also rolls back.
                DeleteConfirmation issued = confirmations.claim(request.confirmationToken())
                        .orElse(null);
                if (issued != null) {
                    restorable.put(request.confirmationToken(), issued);
                }
                claimed.put(request.backupId(), requireValid(request, issued));
            }
            return claimed;
        } catch (RuntimeException exception) {
            restorable.forEach(confirmations::put);
            throw exception;
        }
    }

    private DeleteConfirmation claimConfirmation(DeleteBackupRequest request) {
        return requireValid(
                request, confirmations.claim(request.confirmationToken()).orElse(null));
    }

    private DeleteConfirmation requireValid(
            DeleteBackupRequest request,
            DeleteConfirmation confirmation) {
        Instant now = clock.instant();
        if (confirmation == null
                || !confirmation.backupId().equals(request.backupId())
                || !now.isBefore(confirmation.expiresAt())) {
            throw new BackupRecoveryException("Delete confirmation is invalid, expired, or already used");
        }
        return confirmation;
    }

    private BackupResult deleteInsideGate(
            DeleteConfirmation confirmation,
            ProgressListener progressListener,
            OperationCancellation cancellation) throws Exception {
        BackupId backupId = confirmation.backupId();
        boolean deletionIntentRecorded = false;
        try {
            cancellation.checkpoint();
            BackupRecord current = RecoverySupport.requireRecord(catalog, backupId);
            confirmation.requireMatches(current);
            deletions.record(current.manifest().backupId());
            deletionIntentRecorded = true;
            OperationId operationId = OperationId.create();
            List<DestinationResult> present = RecoverySupport.presentDestinations(current);
            List<DestinationResult> attempts = new ArrayList<>();
            RecoverySupport.report(progressListener, RecoverySupport.progress(
                    operationId, current, BackupOperation.DELETE, OperationPhase.PREPARING,
                    0, present.size(), "Preparing destination deletion"));
            if (present.isEmpty()) {
                cancellation.commitIfActive(() -> {
                    removeRecordWithoutArtifacts(current);
                    return null;
                });
                cancellation.checkpoint();
            }
            for (DestinationResult destination : present) {
                cancellation.checkpoint();
                RecoveryDestination adapter = destinations.get(destination.destination());
                boolean removed = false;
                Optional<String> failureReason = Optional.empty();
                if (adapter != null) {
                    try {
                        DeletionOutcome outcome = cancellation.commitIfActive(() ->
                                deleteAndPersist(current, destination, adapter));
                        removed = outcome.removed();
                        failureReason = outcome.failureReason();
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                        throw exception;
                    }
                }
                if (removed) {
                    attempts.add(destination.withState(
                            DestinationStatus.SUCCESS,
                            Optional.empty(),
                            destination.syncStatus()));
                } else {
                    attempts.add(DestinationResult.failed(
                            destination.destination(), deletionFailureMessage(failureReason)));
                }
                RecoverySupport.report(progressListener, RecoverySupport.progress(
                        operationId, current, BackupOperation.DELETE, OperationPhase.WRITING,
                        attempts.size(), present.size(), "Deleting destination artifacts"));
                cancellation.checkpoint();
            }
            BackupResult result = BackupResult.aggregate(
                    current.manifest().backupId(),
                    current.manifest().worldId(),
                    attempts,
                    completionTime(current.manifest()));
            RecoverySupport.report(progressListener, RecoverySupport.progress(
                    operationId, current, BackupOperation.DELETE, OperationPhase.COMPLETE,
                    present.size(), present.size(), "Destination deletion complete"));
            return result;
        } finally {
            if (deletionIntentRecorded && catalog.find(backupId).isPresent()) {
                deletions.restore(backupId);
            }
        }
    }

    private DeletionOutcome deleteAndPersist(
            BackupRecord current,
            DestinationResult destination,
            RecoveryDestination adapter) throws Exception {
        boolean removed;
        try {
            removed = adapter.delete(current, destination);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw exception;
        } catch (Exception exception) {
            return DeletionOutcome.failed(safeFailureReason(exception));
        }
        if (removed) {
            persistSuccessfulDeletion(current, RecoverySupport.DestinationKey.from(destination));
        }
        return removed ? DeletionOutcome.succeeded() : DeletionOutcome.failed(Optional.empty());
    }

    /**
     * Turns a deletion that could not start into a result the caller can show next to the
     * others. Every destination the user confirmed is marked failed with the reason. A
     * confirmation without destinations has nothing to mark, so that failure propagates.
     */
    private BackupResult failedResult(DeleteConfirmation confirmation, Exception failure) {
        if (confirmation.destinationTypes().isEmpty()) {
            throw failure instanceof RuntimeException unchecked
                    ? unchecked
                    : new CompletionException(failure);
        }
        String reason = deletionFailureMessage(safeFailureReason(failure));
        List<DestinationResult> attempts = confirmation.destinationTypes().stream()
                .sorted()
                .map(type -> DestinationResult.failed(type, reason))
                .toList();
        return BackupResult.aggregate(
                confirmation.backupId(),
                confirmation.manifest().worldId(),
                attempts,
                completionTime(confirmation.manifest()));
    }

    private static OperationProgress batchProgress(
            OperationId operationId,
            WorldId worldId,
            OperationPhase phase,
            long completed,
            long total,
            String message) {
        return new OperationProgress(
                operationId,
                worldId,
                Optional.empty(),
                BackupOperation.DELETE,
                phase,
                completed,
                total,
                message);
    }

    private static Optional<String> safeFailureReason(Exception exception) {
        String message = exception.getMessage();
        if (message == null || message.isBlank()) {
            return Optional.of(exception.getClass().getSimpleName());
        }
        String redacted = SensitiveDataRedactor.redact(message);
        return Optional.of(redacted.length() > MAXIMUM_FAILURE_REASON_LENGTH
                ? redacted.substring(0, MAXIMUM_FAILURE_REASON_LENGTH - 1) + "…"
                : redacted);
    }

    private static String deletionFailureMessage(Optional<String> reason) {
        return reason
                .map(text -> "Destination artifact could not be deleted: " + text)
                .orElse("Destination artifact could not be deleted");
    }

    private record DeletionOutcome(boolean removed, Optional<String> failureReason) {
        static DeletionOutcome succeeded() {
            return new DeletionOutcome(true, Optional.empty());
        }

        static DeletionOutcome failed(Optional<String> failureReason) {
            return new DeletionOutcome(false, failureReason);
        }
    }

    private void removeRecordWithoutArtifacts(BackupRecord expected) throws IOException {
        BackupRecord current = RecoverySupport.requireRecord(catalog, expected.manifest().backupId());
        RecoverySupport.requireSameManifest(expected, current);
        if (!RecoverySupport.presentDestinations(current).isEmpty()) {
            throw new BackupRecoveryException(
                    "Backup gained a destination before the catalog was updated");
        }
        if (!catalog.remove(current.manifest().backupId())) {
            throw new BackupRecoveryException("Backup disappeared while updating the catalog");
        }
    }

    private void persistSuccessfulDeletion(
            BackupRecord expected,
            RecoverySupport.DestinationKey deleted) throws IOException {
        BackupRecord current = RecoverySupport.requireRecord(catalog, expected.manifest().backupId());
        RecoverySupport.requireSameManifest(expected, current);
        boolean stillPresent = current.result().destinations().stream()
                .anyMatch(destination -> deleted.equals(RecoverySupport.DestinationKey.from(destination))
                        && RecoverySupport.isPresent(destination));
        if (!stillPresent) {
            throw new BackupRecoveryException(
                    "Deleted destination disappeared before the catalog was updated");
        }
        List<DestinationResult> remaining = current.result().destinations().stream()
                .filter(destination -> !deleted.equals(RecoverySupport.DestinationKey.from(destination)))
                .toList();
        if (remaining.stream().noneMatch(RecoverySupport::isPresent)) {
            if (!catalog.remove(current.manifest().backupId())) {
                throw new BackupRecoveryException("Backup disappeared while updating the catalog");
            }
            return;
        }
        RecoverySupport.updateCatalog(catalog, current.manifest().backupId(), existing -> existing.stream()
                .filter(destination -> !deleted.equals(RecoverySupport.DestinationKey.from(destination)))
                .toList());
    }

    private Instant completionTime(BackupManifest manifest) {
        Instant now = clock.instant();
        return now.isBefore(manifest.createdAt()) ? manifest.createdAt() : now;
    }
}
