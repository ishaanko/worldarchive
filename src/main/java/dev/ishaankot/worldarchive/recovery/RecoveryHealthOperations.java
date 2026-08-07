package dev.ishaankot.worldarchive.recovery;

import dev.ishaankot.worldarchive.catalog.BackupCatalog;
import dev.ishaankot.worldarchive.core.BackupOperation;
import dev.ishaankot.worldarchive.core.OperationId;
import dev.ishaankot.worldarchive.core.OperationPhase;
import dev.ishaankot.worldarchive.core.ProgressListener;
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
import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Executes verify, sync, and health-check maintenance operations. */
final class RecoveryHealthOperations {
    private final BackupCatalog catalog;

    private final RecoveryDestinations destinations;

    private final WorldOperationGate operationGate;

    private final Clock clock;

    RecoveryHealthOperations(
            BackupCatalog catalog,
            RecoveryDestinations destinations,
            WorldOperationGate operationGate,
            Clock clock) {
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.destinations = Objects.requireNonNull(destinations, "destinations");
        this.operationGate = Objects.requireNonNull(operationGate, "operationGate");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    BackupResult verifyBlocking(
            BackupId backupId,
            ProgressListener progressListener,
            OperationCancellation cancellation) throws Exception {
        cancellation.checkpoint();
        BackupRecord record = RecoverySupport.requireRecord(catalog, backupId);
        try (WorldOperationGate.Permit ignored = operationGate.enter(record.manifest().worldId())) {
            cancellation.checkpoint();
            BackupRecord current = RecoverySupport.requireRecord(catalog, backupId);
            RecoverySupport.requireSameManifest(record, current);
            OperationId operationId = OperationId.create();
            List<DestinationResult> present = RecoverySupport.presentDestinations(current);
            Map<RecoverySupport.DestinationKey, VerificationStatus> updates = new HashMap<>();
            RecoverySupport.report(progressListener, RecoverySupport.progress(
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
                updates.put(RecoverySupport.DestinationKey.from(destination), status);
                completed++;
                RecoverySupport.report(progressListener, RecoverySupport.progress(
                        operationId, current, BackupOperation.VERIFY, OperationPhase.VERIFYING,
                        completed, present.size(), "Verifying destination artifacts"));
            }
            BackupRecord updated = cancellation.commitIfActive(() ->
                    RecoverySupport.updateCatalog(catalog, backupId, existing -> existing.stream()
                            .map(destination -> Optional.ofNullable(
                                            updates.get(RecoverySupport.DestinationKey.from(destination)))
                                    .map(destination::withVerification)
                                    .orElse(destination))
                            .toList()));
            cancellation.checkpoint();
            RecoverySupport.report(progressListener, RecoverySupport.progress(
                    operationId, updated, BackupOperation.VERIFY, OperationPhase.COMPLETE,
                    present.size(), present.size(), "Backup verification complete"));
            return updated.result();
        }
    }

    BackupResult syncBlocking(
            BackupId backupId,
            ProgressListener progressListener,
            OperationCancellation cancellation) throws Exception {
        cancellation.checkpoint();
        BackupRecord record = RecoverySupport.requireRecord(catalog, backupId);
        try (WorldOperationGate.Permit ignored = operationGate.enter(record.manifest().worldId())) {
            cancellation.checkpoint();
            BackupRecord current = RecoverySupport.requireRecord(catalog, backupId);
            RecoverySupport.requireSameManifest(record, current);
            Optional<DestinationResult> git = current.result().destinations().stream()
                    .filter(RecoverySupport::isPresent)
                    .filter(destination -> destination.destination() == DestinationType.GIT)
                    .findFirst();
            if (git.isEmpty()) {
                return current.result();
            }
            OperationId operationId = OperationId.create();
            RecoverySupport.report(progressListener, RecoverySupport.progress(
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
            RecoverySupport.DestinationKey key = RecoverySupport.DestinationKey.from(git.orElseThrow());
            DestinationResult replacement = synchronizedResult;
            BackupRecord updated = cancellation.mandatoryCommit(() ->
                    RecoverySupport.updateCatalog(catalog, backupId, existing -> existing.stream()
                            .map(destination -> RecoverySupport.DestinationKey.from(destination).equals(key)
                                    ? replacement
                                    : destination)
                            .toList()));
            cancellation.checkpoint();
            RecoverySupport.report(progressListener, RecoverySupport.progress(
                    operationId, updated, BackupOperation.SYNC, OperationPhase.COMPLETE,
                    1, 1, "Git synchronization complete"));
            return updated.result();
        }
    }

    List<DestinationHealth> healthBlocking(Optional<WorldId> worldId) {
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
            case SYNCED, NOT_CONFIGURED -> withState(
                    local,
                    DestinationStatus.SUCCESS,
                    Optional.empty(),
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
        return withState(
                local,
                DestinationStatus.PENDING_SYNC,
                Optional.of(message),
                status);
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
