package dev.ishaankot.worldarchive.recovery;

import dev.ishaankot.worldarchive.catalog.BackupCatalog;
import dev.ishaankot.worldarchive.config.WorldIdentityStore;
import dev.ishaankot.worldarchive.core.BackupOperation;
import dev.ishaankot.worldarchive.core.OperationId;
import dev.ishaankot.worldarchive.core.OperationPhase;
import dev.ishaankot.worldarchive.core.ProgressListener;
import dev.ishaankot.worldarchive.core.RestoreBackupRequest;
import dev.ishaankot.worldarchive.core.RestoreBackupResult;
import dev.ishaankot.worldarchive.core.WorldOperationGate;
import dev.ishaankot.worldarchive.model.BackupRecord;
import dev.ishaankot.worldarchive.model.DestinationResult;
import dev.ishaankot.worldarchive.model.DestinationType;
import dev.ishaankot.worldarchive.model.WorldIdentity;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Executes the restore-backup operation: private staging through atomic publication. */
final class RecoveryRestoreOperation {
    private final BackupCatalog catalog;

    private final RecoveryDestinations destinations;

    private final WorldIdentityStore identityStore;

    private final RestoredWorldMetadataFinalizer metadataFinalizer;

    private final WorldOperationGate operationGate;

    private final BackupRecoveryService.DirectoryMove directoryMove;

    RecoveryRestoreOperation(
            BackupCatalog catalog,
            RecoveryDestinations destinations,
            WorldIdentityStore identityStore,
            RestoredWorldMetadataFinalizer metadataFinalizer,
            WorldOperationGate operationGate,
            BackupRecoveryService.DirectoryMove directoryMove) {
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.destinations = Objects.requireNonNull(destinations, "destinations");
        this.identityStore = Objects.requireNonNull(identityStore, "identityStore");
        this.metadataFinalizer = Objects.requireNonNull(metadataFinalizer, "metadataFinalizer");
        this.operationGate = Objects.requireNonNull(operationGate, "operationGate");
        this.directoryMove = Objects.requireNonNull(directoryMove, "directoryMove");
    }

    RestoreBackupResult restoreBlocking(
            RestoreBackupRequest request,
            ProgressListener progressListener,
            OperationCancellation cancellation) throws Exception {
        cancellation.checkpoint();
        BackupRecord record = RecoverySupport.requireRecord(catalog, request.sourceBackupId());
        try (WorldOperationGate.Permit ignored = operationGate.enter(record.manifest().worldId())) {
            cancellation.checkpoint();
            BackupRecord current = RecoverySupport.requireRecord(catalog, request.sourceBackupId());
            RecoverySupport.requireSameManifest(record, current);
            OperationId operationId = OperationId.create();
            RecoverySupport.report(progressListener, RecoverySupport.progress(
                    operationId, current, BackupOperation.RESTORE, OperationPhase.PREPARING,
                    0, 0, "Preparing restored world copy"));
            RestoreWorkspace workspace = openRestoreWorkspace(
                    request, progressListener, operationId, current);
            List<DestinationCandidate> candidates = restorableCandidates(current);
            for (int index = 0; index < candidates.size(); index++) {
                DestinationCandidate candidate = candidates.get(index);
                if (!verifyRestoreSource(
                        current,
                        candidate,
                        progressListener,
                        operationId,
                        cancellation,
                        index,
                        candidates.size())) {
                    continue;
                }
                Optional<RestoreBackupResult> restored = restoreFromCandidate(
                        request,
                        current,
                        candidate,
                        workspace,
                        progressListener,
                        operationId,
                        cancellation);
                if (restored.isPresent()) {
                    return restored.orElseThrow();
                }
            }
            RecoverySupport.reportFailure(progressListener, operationId, current, BackupOperation.RESTORE,
                    "No valid restore source is available");
            throw new BackupRecoveryException("No valid destination can restore this backup");
        }
    }

    private RestoreWorkspace openRestoreWorkspace(
            RestoreBackupRequest request,
            ProgressListener progressListener,
            OperationId operationId,
            BackupRecord record) {
        try {
            return RestoreWorkspace.open(request.worldsDirectory(), directoryMove);
        } catch (IOException exception) {
            RecoverySupport.reportFailure(progressListener, operationId, record, BackupOperation.RESTORE,
                    "Worlds directory is unavailable or unsafe");
            throw new BackupRecoveryException(
                    "Worlds directory is unavailable or unsafe", exception);
        }
    }

    private boolean verifyRestoreSource(
            BackupRecord record,
            DestinationCandidate candidate,
            ProgressListener progressListener,
            OperationId operationId,
            OperationCancellation cancellation,
            int completed,
            int total) throws InterruptedException {
        cancellation.checkpoint();
        RecoverySupport.report(progressListener, RecoverySupport.progress(
                operationId, record, BackupOperation.RESTORE, OperationPhase.VERIFYING,
                completed, total, "Verifying restore source"));
        try {
            VerificationOutcome outcome = candidate.adapter().verifyForRestore(
                    record, candidate.result());
            cancellation.checkpoint();
            return outcome.valid();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw exception;
        } catch (Exception exception) {
            // Another independently stored destination may still be valid.
            return false;
        }
    }

    private Optional<RestoreBackupResult> restoreFromCandidate(
            RestoreBackupRequest request,
            BackupRecord record,
            DestinationCandidate candidate,
            RestoreWorkspace workspace,
            ProgressListener progressListener,
            OperationId operationId,
            OperationCancellation cancellation) throws Exception {
        RecoverySupport.report(progressListener, RecoverySupport.progress(
                operationId, record, BackupOperation.RESTORE, OperationPhase.WRITING,
                0, 0, "Materializing a private restored copy"));
        RestoreWorkspace.Staging staging = workspace.createStaging();
        Optional<RestoreWorkspace.Staging> materialized = materializeCandidate(
                record, candidate, workspace, staging, cancellation);
        if (materialized.isEmpty()) {
            return Optional.empty();
        }
        staging = materialized.orElseThrow();
        WorldIdentity identity = finalizeRestoredIdentity(
                request, record, workspace, staging, cancellation);
        RecoverySupport.report(progressListener, RecoverySupport.progress(
                operationId, record, BackupOperation.RESTORE, OperationPhase.PUBLISHING,
                0, 0, "Publishing restored world copy"));
        Path published = publishRestoredWorld(
                request, workspace, staging, cancellation);
        try {
            RestoreBackupResult result = new RestoreBackupResult(
                    record.manifest().backupId(), identity.worldId(), published);
            RecoverySupport.report(progressListener, RecoverySupport.progress(
                    operationId, record, BackupOperation.RESTORE, OperationPhase.COMPLETE,
                    1, 1, "Restored world copy is ready"));
            return Optional.of(result);
        } catch (RuntimeException exception) {
            workspace.deletePublished(published);
            throw exception;
        }
    }

    private static Optional<RestoreWorkspace.Staging> materializeCandidate(
            BackupRecord record,
            DestinationCandidate candidate,
            RestoreWorkspace workspace,
            RestoreWorkspace.Staging initialStaging,
            OperationCancellation cancellation) throws InterruptedException {
        RestoreWorkspace.Staging staging = initialStaging;
        try {
            RecoveryDestination.Materialization materialization = candidate.adapter().materialize(
                    record, candidate.result(), staging.path());
            boolean interruptedAfterMaterialization = Thread.interrupted();
            try {
                staging = staging.afterMaterialization(materialization);
            } finally {
                if (interruptedAfterMaterialization) {
                    Thread.currentThread().interrupt();
                }
            }
            if (materialization.postMaterializationProblem().isPresent()) {
                throw new BackupRecoveryException(
                        materialization.postMaterializationProblem().orElseThrow());
            }
            cancellation.checkpoint();
            staging.requireUnchanged();
            if (Files.exists(
                    staging.path().resolve(".worldarchive"), LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("Restore source contains internal metadata");
            }
            return Optional.of(staging);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            workspace.cleanup(staging, exception);
            throw exception;
        } catch (Exception exception) {
            if (!workspace.cleanup(staging, exception)) {
                throw new BackupRecoveryException(
                        "Private restore staging could not be cleaned safely", exception);
            }
            return Optional.empty();
        }
    }

    private WorldIdentity finalizeRestoredIdentity(
            RestoreBackupRequest request,
            BackupRecord record,
            RestoreWorkspace workspace,
            RestoreWorkspace.Staging staging,
            OperationCancellation cancellation) throws InterruptedException {
        try {
            cancellation.checkpoint();
            staging.requireUnchanged();
            metadataFinalizer.finalizeDisplayName(staging.path(), request.restoredWorldName());
            cancellation.checkpoint();
            staging.requireUnchanged();
            WorldIdentity identity = identityStore.createFreshRestoredCopyIdentity(
                    staging.path(), record.manifest().backupId());
            cancellation.checkpoint();
            staging.requireUnchanged();
            return identity;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            workspace.cleanup(staging, exception);
            throw exception;
        } catch (IOException | RuntimeException exception) {
            workspace.cleanup(staging, exception);
            throw new BackupRecoveryException(
                    "Restored world metadata could not be finalized", exception);
        }
    }

    private static Path publishRestoredWorld(
            RestoreBackupRequest request,
            RestoreWorkspace workspace,
            RestoreWorkspace.Staging staging,
            OperationCancellation cancellation) throws InterruptedException {
        try {
            return workspace.publish(staging, request.restoredWorldName(), cancellation);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            workspace.cleanup(staging, exception);
            throw exception;
        } catch (Exception exception) {
            workspace.cleanup(staging, exception);
            throw new BackupRecoveryException(
                    "Restored world copy could not be published", exception);
        }
    }

    private List<DestinationCandidate> restorableCandidates(BackupRecord record) {
        return RecoverySupport.presentDestinations(record).stream()
                .map(result -> new DestinationCandidate(result, destinations.get(result.destination())))
                .filter(candidate -> candidate.adapter() != null)
                .sorted(Comparator.comparingInt(candidate -> restorePriority(
                        candidate.result().destination())))
                .toList();
    }

    private static int restorePriority(DestinationType type) {
        return type == DestinationType.ZIP ? 0 : 1;
    }

    private record DestinationCandidate(
            DestinationResult result,
            RecoveryDestination adapter) {
    }
}
