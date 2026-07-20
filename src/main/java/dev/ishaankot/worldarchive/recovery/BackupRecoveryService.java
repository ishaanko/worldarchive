package dev.ishaankot.worldarchive.recovery;

import dev.ishaankot.worldarchive.catalog.BackupCatalog;
import dev.ishaankot.worldarchive.config.WorldIdentityStore;
import dev.ishaankot.worldarchive.core.BackupMaintenanceService;
import dev.ishaankot.worldarchive.core.BackupOperation;
import dev.ishaankot.worldarchive.core.DeleteBackupRequest;
import dev.ishaankot.worldarchive.core.DeletePreparation;
import dev.ishaankot.worldarchive.core.OperationId;
import dev.ishaankot.worldarchive.core.OperationPhase;
import dev.ishaankot.worldarchive.core.OperationProgress;
import dev.ishaankot.worldarchive.core.ProgressListener;
import dev.ishaankot.worldarchive.core.RestoreBackupRequest;
import dev.ishaankot.worldarchive.core.RestoreBackupResult;
import dev.ishaankot.worldarchive.core.WorldOperationGate;
import dev.ishaankot.worldarchive.model.BackupId;
import dev.ishaankot.worldarchive.model.BackupRecord;
import dev.ishaankot.worldarchive.model.BackupResult;
import dev.ishaankot.worldarchive.model.DestinationHealth;
import dev.ishaankot.worldarchive.model.DestinationHealthStatus;
import dev.ishaankot.worldarchive.model.DestinationResult;
import dev.ishaankot.worldarchive.model.DestinationStatus;
import dev.ishaankot.worldarchive.model.DestinationType;
import dev.ishaankot.worldarchive.model.SyncStatus;
import dev.ishaankot.worldarchive.model.VerificationStatus;
import dev.ishaankot.worldarchive.model.WorldId;
import dev.ishaankot.worldarchive.model.WorldIdentity;
import dev.ishaankot.worldarchive.storage.git.GitSnapshotStore;
import dev.ishaankot.worldarchive.storage.zip.ZipBackupStore;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.UnaryOperator;

/** Thread-safe, Minecraft-independent implementation of all non-create backup operations. */
public final class BackupRecoveryService implements BackupMaintenanceService {
    public static final Duration DEFAULT_CONFIRMATION_LIFETIME = Duration.ofMinutes(1);

    private static final Duration MAXIMUM_CONFIRMATION_LIFETIME = Duration.ofMinutes(5);

    private final BackupCatalog catalog;

    private final Map<DestinationType, RecoveryDestination> destinations;

    private final WorldIdentityStore identityStore;

    private final RestoredWorldMetadataFinalizer metadataFinalizer;

    private final Executor executor;

    private final Clock clock;

    private final Duration confirmationLifetime;

    private final WorldOperationGate operationGate;

    private final DirectoryMove directoryMove;

    private final ConcurrentMap<OperationId, DeleteConfirmation> confirmations =
            new ConcurrentHashMap<>();

    public BackupRecoveryService(
            BackupCatalog catalog,
            Optional<? extends GitSnapshotStore> gitBackend,
            Optional<ZipBackupStore> zipStore,
            WorldIdentityStore identityStore,
            RestoredWorldMetadataFinalizer metadataFinalizer,
            Executor executor,
            WorldOperationGate operationGate) {
        this(
                catalog,
                destinationMap(gitBackend, zipStore, Clock.systemUTC()),
                identityStore,
                metadataFinalizer,
                executor,
                Clock.systemUTC(),
                DEFAULT_CONFIRMATION_LIFETIME,
                operationGate);
    }

    BackupRecoveryService(
            BackupCatalog catalog,
            Map<DestinationType, RecoveryDestination> destinations,
            WorldIdentityStore identityStore,
            RestoredWorldMetadataFinalizer metadataFinalizer,
            Executor executor,
            Clock clock,
            Duration confirmationLifetime,
            WorldOperationGate operationGate) {
        this(
                catalog,
                destinations,
                identityStore,
                metadataFinalizer,
                executor,
                clock,
                confirmationLifetime,
                operationGate,
                Files::move);
    }

    BackupRecoveryService(
            BackupCatalog catalog,
            Map<DestinationType, RecoveryDestination> destinations,
            WorldIdentityStore identityStore,
            RestoredWorldMetadataFinalizer metadataFinalizer,
            Executor executor,
            Clock clock,
            Duration confirmationLifetime,
            WorldOperationGate operationGate,
            DirectoryMove directoryMove) {
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.destinations = validatedDestinations(destinations);
        this.identityStore = Objects.requireNonNull(identityStore, "identityStore");
        this.metadataFinalizer = Objects.requireNonNull(metadataFinalizer, "metadataFinalizer");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.confirmationLifetime = requireShortLifetime(confirmationLifetime);
        this.operationGate = Objects.requireNonNull(operationGate, "operationGate");
        this.directoryMove = Objects.requireNonNull(directoryMove, "directoryMove");
    }

    @Override
    public CompletionStage<List<BackupRecord>> listBackups(Optional<WorldId> worldId) {
        Objects.requireNonNull(worldId, "worldId");
        return submit(() -> worldId.isPresent()
                ? catalog.list(worldId.orElseThrow())
                : catalog.listAll());
    }

    @Override
    public CompletionStage<Optional<BackupRecord>> findBackup(BackupId backupId) {
        Objects.requireNonNull(backupId, "backupId");
        return submit(() -> catalog.find(backupId));
    }

    @Override
    public CompletionStage<RestoreBackupResult> restoreBackup(
            RestoreBackupRequest request,
            ProgressListener progressListener) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(progressListener, "progressListener");
        return submit(cancellation -> restoreBlocking(request, progressListener, cancellation));
    }

    @Override
    public CompletionStage<DeletePreparation> prepareDelete(BackupId backupId) {
        Objects.requireNonNull(backupId, "backupId");
        return submit(() -> prepareDeleteBlocking(backupId));
    }

    @Override
    public CompletionStage<BackupResult> deleteBackup(
            DeleteBackupRequest request,
            ProgressListener progressListener) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(progressListener, "progressListener");
        return submit(cancellation -> deleteBlocking(request, progressListener, cancellation));
    }

    @Override
    public CompletionStage<BackupResult> verifyBackup(
            BackupId backupId,
            ProgressListener progressListener) {
        Objects.requireNonNull(backupId, "backupId");
        Objects.requireNonNull(progressListener, "progressListener");
        return submit(cancellation -> verifyBlocking(backupId, progressListener, cancellation));
    }

    @Override
    public CompletionStage<BackupResult> syncBackup(
            BackupId backupId,
            ProgressListener progressListener) {
        Objects.requireNonNull(backupId, "backupId");
        Objects.requireNonNull(progressListener, "progressListener");
        return submit(cancellation -> syncBlocking(backupId, progressListener, cancellation));
    }

    @Override
    public CompletionStage<List<DestinationHealth>> health(Optional<WorldId> worldId) {
        Objects.requireNonNull(worldId, "worldId");
        return submit(() -> healthBlocking(worldId));
    }

    private RestoreBackupResult restoreBlocking(
            RestoreBackupRequest request,
            ProgressListener progressListener,
            OperationCancellation cancellation) throws Exception {
        cancellation.checkpoint();
        BackupRecord record = requireRecord(request.sourceBackupId());
        try (WorldOperationGate.Permit ignored = operationGate.enter(record.manifest().worldId())) {
            cancellation.checkpoint();
            BackupRecord current = requireRecord(request.sourceBackupId());
            requireSameManifest(record, current);
            OperationId operationId = OperationId.create();
            report(progressListener, progress(
                    operationId, current, BackupOperation.RESTORE, OperationPhase.PREPARING,
                    0, 0, "Preparing restored world copy"));
            RestoreWorkspace workspace = openRestoreWorkspace(
                    request, progressListener, operationId, current);
            List<DestinationCandidate> candidates = verifiedRestoreSources(
                    current, progressListener, operationId, cancellation);
            for (DestinationCandidate candidate : candidates) {
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
            reportFailure(progressListener, operationId, current, BackupOperation.RESTORE,
                    "All restore sources failed during materialization");
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
            reportFailure(progressListener, operationId, record, BackupOperation.RESTORE,
                    "Worlds directory is unavailable or unsafe");
            throw new BackupRecoveryException(
                    "Worlds directory is unavailable or unsafe", exception);
        }
    }

    private List<DestinationCandidate> verifiedRestoreSources(
            BackupRecord record,
            ProgressListener progressListener,
            OperationId operationId,
            OperationCancellation cancellation) throws InterruptedException {
        List<DestinationCandidate> candidates = restorableCandidates(record);
        List<DestinationCandidate> verified = new ArrayList<>();
        for (DestinationCandidate candidate : candidates) {
            cancellation.checkpoint();
            report(progressListener, progress(
                    operationId, record, BackupOperation.RESTORE, OperationPhase.VERIFYING,
                    verified.size(), candidates.size(), "Verifying restore source"));
            try {
                VerificationOutcome outcome = candidate.adapter().verifyForRestore(
                        record, candidate.result());
                if (outcome.valid()) {
                    verified.add(candidate);
                }
                cancellation.checkpoint();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw exception;
            } catch (Exception exception) {
                // Another independently stored destination may still be valid.
            }
        }
        if (verified.isEmpty()) {
            reportFailure(progressListener, operationId, record, BackupOperation.RESTORE,
                    "No valid restore source is available");
            throw new BackupRecoveryException("No valid destination can restore this backup");
        }
        return verified;
    }

    private Optional<RestoreBackupResult> restoreFromCandidate(
            RestoreBackupRequest request,
            BackupRecord record,
            DestinationCandidate candidate,
            RestoreWorkspace workspace,
            ProgressListener progressListener,
            OperationId operationId,
            OperationCancellation cancellation) throws Exception {
        report(progressListener, progress(
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
        report(progressListener, progress(
                operationId, record, BackupOperation.RESTORE, OperationPhase.PUBLISHING,
                0, 0, "Publishing restored world copy"));
        Path published = publishRestoredWorld(
                request, workspace, staging, cancellation);
        try {
            RestoreBackupResult result = new RestoreBackupResult(
                    record.manifest().backupId(), identity.worldId(), published);
            report(progressListener, progress(
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

    private DeletePreparation prepareDeleteBlocking(BackupId backupId) throws IOException {
        BackupRecord record = requireRecord(backupId);
        Instant now = clock.instant();
        confirmations.forEach((token, confirmation) -> {
            if (!now.isBefore(confirmation.expiresAt())) {
                confirmations.remove(token, confirmation);
            }
        });
        OperationId token;
        DeleteConfirmation confirmation;
        do {
            token = OperationId.create();
            confirmation = new DeleteConfirmation(
                    backupId, record.manifest().worldId(), now.plus(confirmationLifetime));
        } while (confirmations.putIfAbsent(token, confirmation) != null);
        long artifacts = presentDestinations(record).size();
        String description = "Delete backup " + backupId + " for "
                + record.manifest().worldName() + " from " + artifacts + " destination(s)";
        return new DeletePreparation(backupId, token, description, confirmation.expiresAt());
    }

    private BackupResult deleteBlocking(
            DeleteBackupRequest request,
            ProgressListener progressListener,
            OperationCancellation cancellation) throws Exception {
        cancellation.checkpoint();
        DeleteConfirmation confirmation = confirmations.remove(request.confirmationToken());
        Instant now = clock.instant();
        if (confirmation == null
                || !confirmation.backupId().equals(request.backupId())
                || !now.isBefore(confirmation.expiresAt())) {
            throw new BackupRecoveryException("Delete confirmation is invalid, expired, or already used");
        }
        BackupRecord record = requireRecord(request.backupId());
        if (!record.manifest().worldId().equals(confirmation.worldId())) {
            throw new BackupRecoveryException("Delete confirmation does not match the backup world");
        }
        try (WorldOperationGate.Permit ignored = operationGate.enter(record.manifest().worldId())) {
            cancellation.checkpoint();
            BackupRecord current = requireRecord(request.backupId());
            requireSameManifest(record, current);
            OperationId operationId = OperationId.create();
            List<DestinationResult> present = presentDestinations(current);
            List<DestinationResult> attempts = new ArrayList<>();
            report(progressListener, progress(
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
                    attempts.add(new DestinationResult(
                            destination.destination(),
                            DestinationStatus.SUCCESS,
                            destination.artifactId(),
                            Optional.empty(),
                            destination.verificationStatus(),
                            destination.syncStatus()));
                } else {
                    attempts.add(DestinationResult.failed(
                            destination.destination(), "Destination artifact could not be deleted"));
                }
                report(progressListener, progress(
                        operationId, current, BackupOperation.DELETE, OperationPhase.WRITING,
                        attempts.size(), present.size(), "Deleting destination artifacts"));
                cancellation.checkpoint();
            }
            BackupResult result = BackupResult.aggregate(
                    current.manifest().backupId(),
                    current.manifest().worldId(),
                    attempts,
                    completionTime(current));
            report(progressListener, progress(
                    operationId, current, BackupOperation.DELETE, OperationPhase.COMPLETE,
                    present.size(), present.size(), "Destination deletion complete"));
            return result;
        }
    }

    private BackupResult verifyBlocking(
            BackupId backupId,
            ProgressListener progressListener,
            OperationCancellation cancellation) throws Exception {
        cancellation.checkpoint();
        BackupRecord record = requireRecord(backupId);
        try (WorldOperationGate.Permit ignored = operationGate.enter(record.manifest().worldId())) {
            cancellation.checkpoint();
            BackupRecord current = requireRecord(backupId);
            requireSameManifest(record, current);
            OperationId operationId = OperationId.create();
            List<DestinationResult> present = presentDestinations(current);
            Map<DestinationKey, VerificationStatus> updates = new java.util.HashMap<>();
            report(progressListener, progress(
                    operationId, current, BackupOperation.VERIFY, OperationPhase.PREPARING,
                    0, present.size(), "Preparing backup verification"));
            int completed = 0;
            for (DestinationResult destination : present) {
                cancellation.checkpoint();
                VerificationStatus status = VerificationStatus.UNAVAILABLE;
                RecoveryDestination adapter = destinations.get(destination.destination());
                if (adapter != null) {
                    try {
                        status = adapter.verify(current, destination).status();
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                        throw exception;
                    } catch (Exception exception) {
                        status = VerificationStatus.UNAVAILABLE;
                    }
                }
                updates.put(DestinationKey.from(destination), status);
                completed++;
                report(progressListener, progress(
                        operationId, current, BackupOperation.VERIFY, OperationPhase.VERIFYING,
                        completed, present.size(), "Verifying destination artifacts"));
            }
            BackupRecord updated = cancellation.commitIfActive(() ->
                    updateCatalog(backupId, existing -> existing.stream()
                            .map(destination -> Optional.ofNullable(
                                            updates.get(DestinationKey.from(destination)))
                                    .map(destination::withVerification)
                                    .orElse(destination))
                            .toList()));
            cancellation.checkpoint();
            report(progressListener, progress(
                    operationId, updated, BackupOperation.VERIFY, OperationPhase.COMPLETE,
                    present.size(), present.size(), "Backup verification complete"));
            return updated.result();
        }
    }

    private BackupResult syncBlocking(
            BackupId backupId,
            ProgressListener progressListener,
            OperationCancellation cancellation) throws Exception {
        cancellation.checkpoint();
        BackupRecord record = requireRecord(backupId);
        try (WorldOperationGate.Permit ignored = operationGate.enter(record.manifest().worldId())) {
            cancellation.checkpoint();
            BackupRecord current = requireRecord(backupId);
            requireSameManifest(record, current);
            Optional<DestinationResult> git = current.result().destinations().stream()
                    .filter(BackupRecoveryService::isPresent)
                    .filter(destination -> destination.destination() == DestinationType.GIT)
                    .findFirst();
            if (git.isEmpty()) {
                return current.result();
            }
            OperationId operationId = OperationId.create();
            report(progressListener, progress(
                    operationId, current, BackupOperation.SYNC, OperationPhase.PREPARING,
                    0, 1, "Preparing Git synchronization"));
            DestinationResult synchronizedResult;
            RecoveryDestination adapter = destinations.get(DestinationType.GIT);
            if (adapter == null) {
                synchronizedResult = pendingSync(git.orElseThrow(),
                        "Git destination is unavailable", SyncStatus.FAILED);
            } else {
                try {
                    cancellation.checkpoint();
                    synchronizedResult = mergeSync(
                            git.orElseThrow(), adapter.sync(current, git.orElseThrow()));
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw exception;
                } catch (Exception exception) {
                    synchronizedResult = pendingSync(git.orElseThrow(),
                            "Git synchronization failed", SyncStatus.FAILED);
                }
            }
            DestinationKey key = DestinationKey.from(git.orElseThrow());
            DestinationResult replacement = synchronizedResult;
            BackupRecord updated = cancellation.mandatoryCommit(() ->
                    updateCatalog(backupId, existing -> existing.stream()
                            .map(destination -> DestinationKey.from(destination).equals(key)
                                    ? replacement
                                    : destination)
                            .toList()));
            cancellation.checkpoint();
            report(progressListener, progress(
                    operationId, updated, BackupOperation.SYNC, OperationPhase.COMPLETE,
                    1, 1, "Git synchronization complete"));
            return updated.result();
        }
    }

    private List<DestinationHealth> healthBlocking(Optional<WorldId> worldId) {
        List<DestinationHealth> health = new ArrayList<>();
        for (DestinationType type : DestinationType.values()) {
            RecoveryDestination destination = destinations.get(type);
            if (destination == null) {
                health.add(new DestinationHealth(
                        type,
                        DestinationHealthStatus.UNCONFIGURED,
                        type + " destination is not configured",
                        clock.instant()));
                continue;
            }
            try {
                DestinationHealth item = destination.health(worldId);
                if (item.destination() != type) {
                    throw new BackupRecoveryException("Destination returned mismatched health");
                }
                health.add(item);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                health.add(new DestinationHealth(
                        type,
                        DestinationHealthStatus.UNAVAILABLE,
                        type + " health check was interrupted",
                        clock.instant()));
                break;
            } catch (Exception exception) {
                health.add(new DestinationHealth(
                        type,
                        DestinationHealthStatus.UNAVAILABLE,
                        type + " destination could not be checked",
                        clock.instant()));
            }
        }
        for (DestinationType type : DestinationType.values()) {
            if (health.stream().noneMatch(item -> item.destination() == type)) {
                health.add(new DestinationHealth(
                        type,
                        DestinationHealthStatus.UNAVAILABLE,
                        type + " health check was cancelled",
                        clock.instant()));
            }
        }
        health.sort(Comparator.comparing(DestinationHealth::destination));
        return List.copyOf(health);
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
            persistSuccessfulDeletion(current, DestinationKey.from(destination));
        }
        return removed;
    }

    private void removeRecordWithoutArtifacts(BackupRecord expected) throws IOException {
        BackupRecord current = requireRecord(expected.manifest().backupId());
        requireSameManifest(expected, current);
        if (!presentDestinations(current).isEmpty()) {
            throw new BackupRecoveryException(
                    "Backup gained a destination before the catalog was updated");
        }
        if (!catalog.remove(current.manifest().backupId())) {
            throw new BackupRecoveryException("Backup disappeared while updating the catalog");
        }
    }

    private void persistSuccessfulDeletion(
            BackupRecord expected,
            DestinationKey deleted) throws IOException {
        BackupRecord current = requireRecord(expected.manifest().backupId());
        requireSameManifest(expected, current);
        boolean stillPresent = current.result().destinations().stream()
                .anyMatch(destination -> deleted.equals(DestinationKey.from(destination))
                        && isPresent(destination));
        if (!stillPresent) {
            throw new BackupRecoveryException(
                    "Deleted destination disappeared before the catalog was updated");
        }
        List<DestinationResult> remaining = current.result().destinations().stream()
                .filter(destination -> !deleted.equals(DestinationKey.from(destination)))
                .toList();
        if (remaining.stream().noneMatch(BackupRecoveryService::isPresent)) {
            if (!catalog.remove(current.manifest().backupId())) {
                throw new BackupRecoveryException("Backup disappeared while updating the catalog");
            }
            return;
        }
        updateCatalog(current.manifest().backupId(), existing -> existing.stream()
                .filter(destination -> !deleted.equals(DestinationKey.from(destination)))
                .toList());
    }

    private BackupRecord updateCatalog(
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

    private BackupRecord requireRecord(BackupId backupId) throws IOException {
        return catalog.find(backupId).orElseThrow(
                () -> new BackupRecoveryException("Backup was not found in the catalog"));
    }

    private Instant completionTime(BackupRecord record) {
        Instant now = clock.instant();
        return now.isBefore(record.manifest().createdAt()) ? record.manifest().createdAt() : now;
    }

    private List<DestinationCandidate> restorableCandidates(BackupRecord record) {
        return presentDestinations(record).stream()
                .map(result -> new DestinationCandidate(result, destinations.get(result.destination())))
                .filter(candidate -> candidate.adapter() != null)
                .sorted(Comparator.comparingInt(candidate -> restorePriority(
                        candidate.result().destination())))
                .toList();
    }

    private static int restorePriority(DestinationType type) {
        return type == DestinationType.ZIP ? 0 : 1;
    }

    private static List<DestinationResult> presentDestinations(BackupRecord record) {
        return record.result().destinations().stream()
                .filter(BackupRecoveryService::isPresent)
                .sorted(Comparator.comparing(DestinationResult::destination))
                .toList();
    }

    private static boolean isPresent(DestinationResult destination) {
        return destination.artifactId().isPresent()
                && (destination.status() == DestinationStatus.SUCCESS
                        || destination.status() == DestinationStatus.PENDING_SYNC);
    }

    private static DestinationResult mergeSync(
            DestinationResult local,
            DestinationResult synchronizedResult) {
        if (synchronizedResult.destination() != DestinationType.GIT) {
            throw new BackupRecoveryException("Git returned a result for another destination");
        }
        synchronizedResult.artifactId().ifPresent(artifact -> {
            if (!local.artifactId().orElseThrow().equals(artifact)) {
                throw new BackupRecoveryException("Git returned a different snapshot identity");
            }
        });
        return switch (synchronizedResult.syncStatus()) {
            case SYNCED, NOT_CONFIGURED -> new DestinationResult(
                    DestinationType.GIT,
                    DestinationStatus.SUCCESS,
                    local.artifactId(),
                    Optional.empty(),
                    local.verificationStatus(),
                    synchronizedResult.syncStatus());
            case NOT_SYNCED, PENDING, FAILED -> pendingSync(
                    local,
                    synchronizedResult.message().orElse("Git synchronization must be retried"),
                    synchronizedResult.syncStatus());
        };
    }

    private static DestinationResult pendingSync(
            DestinationResult local,
            String message,
            SyncStatus status) {
        return new DestinationResult(
                DestinationType.GIT,
                DestinationStatus.PENDING_SYNC,
                local.artifactId(),
                Optional.of(message),
                local.verificationStatus(),
                status);
    }

    private static OperationProgress progress(
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

    private static void reportFailure(
            ProgressListener listener,
            OperationId operationId,
            BackupRecord record,
            BackupOperation operation,
            String message) {
        report(listener, progress(
                operationId, record, operation, OperationPhase.FAILED, 0, 0, message));
    }

    private static void report(ProgressListener listener, OperationProgress progress) {
        try {
            listener.onProgress(progress);
        } catch (RuntimeException exception) {
            // Storage outcomes cannot depend on observers.
        }
    }

    private <T> CompletionStage<T> submit(CheckedSupplier<T> operation) {
        return submit(cancellation -> operation.get());
    }

    private <T> CompletionStage<T> submit(CancellableTask.Operation<T> operation) {
        CancellableTask<T> task = new CancellableTask<>(operation);
        try {
            executor.execute(task);
        } catch (RejectedExecutionException exception) {
            task.completeExceptionally(exception);
        }
        return task;
    }

    private static Map<DestinationType, RecoveryDestination> destinationMap(
            Optional<? extends GitSnapshotStore> gitBackend,
            Optional<ZipBackupStore> zipStore,
            Clock clock) {
        Objects.requireNonNull(gitBackend, "gitBackend");
        Objects.requireNonNull(zipStore, "zipStore");
        EnumMap<DestinationType, RecoveryDestination> result =
                new EnumMap<>(DestinationType.class);
        gitBackend.ifPresent(backend -> result.put(
                DestinationType.GIT, new GitRecoveryDestination(backend, clock)));
        zipStore.ifPresent(store -> result.put(
                DestinationType.ZIP, new ZipRecoveryDestination(store, clock)));
        return result;
    }

    private static Map<DestinationType, RecoveryDestination> validatedDestinations(
            Map<DestinationType, RecoveryDestination> destinations) {
        Objects.requireNonNull(destinations, "destinations");
        EnumMap<DestinationType, RecoveryDestination> result =
                new EnumMap<>(DestinationType.class);
        destinations.forEach((type, destination) -> {
            Objects.requireNonNull(type, "destination type");
            Objects.requireNonNull(destination, "destination");
            if (type != destination.destinationType()) {
                throw new IllegalArgumentException("Destination map key does not match its adapter");
            }
            result.put(type, destination);
        });
        return Map.copyOf(result);
    }

    private static Duration requireShortLifetime(Duration lifetime) {
        Objects.requireNonNull(lifetime, "confirmationLifetime");
        if (lifetime.isZero()
                || lifetime.isNegative()
                || lifetime.compareTo(MAXIMUM_CONFIRMATION_LIFETIME) > 0) {
            throw new IllegalArgumentException(
                    "Delete confirmation lifetime must be positive and no longer than five minutes");
        }
        return lifetime;
    }

    private static void requireSameManifest(BackupRecord expected, BackupRecord current) {
        if (!expected.manifest().equals(current.manifest())) {
            throw new BackupRecoveryException("Backup catalog identity changed during the operation");
        }
    }

    @FunctionalInterface
    private interface CheckedSupplier<T> {
        T get() throws Exception;
    }

    @FunctionalInterface
    interface DirectoryMove extends RestoreWorkspace.DirectoryMove {
    }

    private record DestinationCandidate(
            DestinationResult result,
            RecoveryDestination adapter) {
    }

    private record DestinationKey(DestinationType type, String artifactId) {
        static DestinationKey from(DestinationResult result) {
            return new DestinationKey(
                    result.destination(), result.artifactId().orElse("<none>"));
        }
    }

    private record DeleteConfirmation(
            BackupId backupId,
            WorldId worldId,
            Instant expiresAt) {
    }

}
