package dev.ishaankot.worldarchive.recovery;

import dev.ishaankot.worldarchive.catalog.BackupCatalog;
import dev.ishaankot.worldarchive.catalog.BackupDeletionRegistry;
import dev.ishaankot.worldarchive.core.BackupOperation;
import dev.ishaankot.worldarchive.core.ConfirmationLedger;
import dev.ishaankot.worldarchive.core.DeleteBackupRequest;
import dev.ishaankot.worldarchive.core.DeletePreparation;
import dev.ishaankot.worldarchive.core.OperationId;
import dev.ishaankot.worldarchive.core.OperationPhase;
import dev.ishaankot.worldarchive.core.ProgressListener;
import dev.ishaankot.worldarchive.core.WorldOperationGate;
import dev.ishaankot.worldarchive.model.BackupId;
import dev.ishaankot.worldarchive.model.BackupRecord;
import dev.ishaankot.worldarchive.model.BackupResult;
import dev.ishaankot.worldarchive.model.DestinationResult;
import dev.ishaankot.worldarchive.model.DestinationStatus;
import dev.ishaankot.worldarchive.model.SyncStatus;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Executes delete confirmation issuance and the destination-deletion operation. */
final class RecoveryDeleteOperation {
    private final BackupCatalog catalog;

    private final RecoveryDestinations destinations;

    private final BackupDeletionRegistry deletions;

    private final WorldOperationGate operationGate;

    private final Clock clock;

    private final Duration confirmationLifetime;

    private final ConfirmationLedger<OperationId, DeleteConfirmation> confirmations =
            new ConfirmationLedger<>(DeleteConfirmation::expiresAt);

    RecoveryDeleteOperation(
            BackupCatalog catalog,
            RecoveryDestinations destinations,
            BackupDeletionRegistry deletions,
            WorldOperationGate operationGate,
            Clock clock,
            Duration confirmationLifetime) {
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.destinations = Objects.requireNonNull(destinations, "destinations");
        this.deletions = Objects.requireNonNull(deletions, "deletions");
        this.operationGate = Objects.requireNonNull(operationGate, "operationGate");
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
        DeleteConfirmation confirmation = confirmations.claim(request.confirmationToken())
                .orElse(null);
        Instant now = clock.instant();
        if (confirmation == null
                || !confirmation.backupId().equals(request.backupId())
                || !now.isBefore(confirmation.expiresAt())) {
            throw new BackupRecoveryException("Delete confirmation is invalid, expired, or already used");
        }
        BackupRecord record = RecoverySupport.requireRecord(catalog, request.backupId());
        boolean deletionIntentRecorded = false;
        try (WorldOperationGate.Permit ignored = operationGate.enter(record.manifest().worldId())) {
            cancellation.checkpoint();
            BackupRecord current = RecoverySupport.requireRecord(catalog, request.backupId());
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
                if (adapter != null) {
                    try {
                        removed = cancellation.commitIfActive(() ->
                                deleteAndPersist(current, destination, adapter));
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                        throw exception;
                    }
                }
                if (removed) {
                    attempts.add(withState(
                            destination,
                            DestinationStatus.SUCCESS,
                            Optional.empty(),
                            destination.syncStatus()));
                } else {
                    attempts.add(DestinationResult.failed(
                            destination.destination(), "Destination artifact could not be deleted"));
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
                    completionTime(current));
            RecoverySupport.report(progressListener, RecoverySupport.progress(
                    operationId, current, BackupOperation.DELETE, OperationPhase.COMPLETE,
                    present.size(), present.size(), "Destination deletion complete"));
            return result;
        } finally {
            if (deletionIntentRecorded && catalog.find(request.backupId()).isPresent()) {
                deletions.restore(request.backupId());
            }
        }
    }

    private boolean deleteAndPersist(
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
            return false;
        }
        if (removed) {
            persistSuccessfulDeletion(current, RecoverySupport.DestinationKey.from(destination));
        }
        return removed;
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

    private Instant completionTime(BackupRecord record) {
        Instant now = clock.instant();
        return now.isBefore(record.manifest().createdAt()) ? record.manifest().createdAt() : now;
    }

    private static DestinationResult withState(
            DestinationResult original,
            DestinationStatus status,
            Optional<String> message,
            SyncStatus syncStatus) {
        return new DestinationResult(
                original.destination(),
                status,
                original.artifactId(),
                message,
                original.verificationStatus(),
                syncStatus,
                original.ownership(),
                original.importSourceId());
    }
}
