package dev.ishaanko.worldarchive.recovery;

import dev.ishaanko.worldarchive.catalog.BackupCatalog;
import dev.ishaanko.worldarchive.core.BackupOperation;
import dev.ishaanko.worldarchive.core.OperationId;
import dev.ishaanko.worldarchive.core.OperationPhase;
import dev.ishaanko.worldarchive.core.OperationProgress;
import dev.ishaanko.worldarchive.core.ProgressListener;
import dev.ishaanko.worldarchive.model.BackupId;
import dev.ishaanko.worldarchive.model.BackupRecord;
import dev.ishaanko.worldarchive.model.BackupResult;
import dev.ishaanko.worldarchive.model.DestinationResult;
import dev.ishaanko.worldarchive.model.DestinationStatus;
import dev.ishaanko.worldarchive.model.DestinationType;
import java.io.IOException;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.function.UnaryOperator;

/** Small helpers shared across the recovery operation classes. */
final class RecoverySupport {
    private RecoverySupport() {
    }

    static BackupRecord requireRecord(BackupCatalog catalog, BackupId backupId) throws IOException {
        return catalog.find(backupId).orElseThrow(
                () -> new BackupRecoveryException("Backup was not found in the catalog"));
    }

    static void requireSameManifest(BackupRecord expected, BackupRecord current) {
        if (!expected.manifest().equals(current.manifest())) {
            throw new BackupRecoveryException("Backup catalog identity changed during the operation");
        }
    }

    static List<DestinationResult> presentDestinations(BackupRecord record) {
        return record.result().destinations().stream()
                .filter(RecoverySupport::isPresent)
                .sorted(Comparator.comparing(DestinationResult::destination))
                .toList();
    }

    static boolean isPresent(DestinationResult destination) {
        return destination.artifactId().isPresent()
                && (destination.status() == DestinationStatus.SUCCESS
                        || destination.status() == DestinationStatus.PENDING_SYNC);
    }

    static BackupRecord updateCatalog(
            BackupCatalog catalog,
            BackupId backupId,
            UnaryOperator<List<DestinationResult>> destinationUpdate) throws IOException {
        return catalog.update(backupId, existing -> {
            List<DestinationResult> replacements = List.copyOf(
                    destinationUpdate.apply(existing.result().destinations()));
            BackupResult result = BackupResult.aggregate(
                    existing.manifest().backupId(),
                    existing.manifest().worldId(),
                    replacements,
                    existing.result().completedAt());
            return new BackupRecord(existing.manifest(), result);
        }).orElseThrow(() -> new BackupRecoveryException(
                "Backup disappeared while updating the catalog"));
    }

    static OperationProgress progress(
            OperationId operationId,
            BackupRecord record,
            BackupOperation operation,
            OperationPhase phase,
            long completed,
            long total,
            String message) {
        return new OperationProgress(
                operationId,
                record.manifest().worldId(),
                Optional.of(record.manifest().backupId()),
                operation,
                phase,
                completed,
                total,
                message);
    }

    static void reportFailure(
            ProgressListener listener,
            OperationId operationId,
            BackupRecord record,
            BackupOperation operation,
            String message) {
        report(listener, progress(
                operationId, record, operation, OperationPhase.FAILED, 0, 0, message));
    }

    static void report(ProgressListener listener, OperationProgress progress) {
        try {
            listener.onProgress(progress);
        } catch (RuntimeException exception) {
            // Storage outcomes cannot depend on observers.
        }
    }

    record DestinationKey(DestinationType type, String artifactId) {
        static DestinationKey from(DestinationResult result) {
            return new DestinationKey(
                    result.destination(), result.artifactId().orElse("<none>"));
        }
    }
}
