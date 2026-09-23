package dev.ishaanko.worldarchive.recovery;

import dev.ishaanko.worldarchive.catalog.BackupCatalog;
import dev.ishaanko.worldarchive.core.WorldOperationGate;
import dev.ishaanko.worldarchive.model.BackupId;
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
import dev.ishaanko.worldarchive.model.SyncStatus;
import dev.ishaanko.worldarchive.model.VerificationStatus;
import java.io.IOException;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.UnaryOperator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Verifies a backup's copies and uploads its pending Git copy. Verify and sync record their outcome in the catalog; a cancelled verify
 * records nothing, and a sync records what the upload did before it stopped.
 */
final class RecoveryHealthOperations {
    private static final Logger LOGGER = LoggerFactory.getLogger("WorldArchive");

    private final BackupCatalog catalog;

    private final Map<DestinationType, RecoveryDestination> destinations;

    private final GitRecoveryDestination git;

    private final WorldOperationGate operationGate;

    RecoveryHealthOperations(
            BackupCatalog catalog,
            Map<DestinationType, RecoveryDestination> destinations,
            GitRecoveryDestination git,
            WorldOperationGate operationGate) {
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.destinations = new EnumMap<>(destinations);
        this.git = Objects.requireNonNull(git, "git");
        this.operationGate = Objects.requireNonNull(operationGate, "operationGate");
    }

    /**
     * Checks every copy in full and records VERIFIED, FAILED, or UNAVAILABLE for each. The result
     * shows each warning a check found, such as a missing checksum file, in place of the copy's
     * message; the warnings are not recorded.
     */
    BackupResult verify(BackupId backupId, ProgressListener listener, CancellableTask<?> task) throws Exception {
        task.checkpoint();
        BackupRecord record = RecoverySupport.requireRecord(catalog, backupId);
        try (WorldOperationGate.Permit ignored = operationGate.enter(record.manifest().worldId())) {
            task.checkpoint();
            BackupRecord current = RecoverySupport.requireRecord(catalog, backupId);
            RecoverySupport.requireSameManifest(record, current);
            OperationId operationId = OperationId.create();
            List<DestinationResult> copies = RecoverySupport.copies(current);
            report(listener, operationId, current, BackupOperation.VERIFY, OperationPhase.PREPARING, 0,
                    copies.size(), "Preparing backup verification");
            Map<DestinationType, VerificationOutcome> outcomes = new EnumMap<>(DestinationType.class);
            for (DestinationResult copy : copies) {
                task.checkpoint();
                outcomes.put(copy.destination(), check(current, copy));
                report(listener, operationId, current, BackupOperation.VERIFY, OperationPhase.VERIFYING,
                        outcomes.size(), copies.size(), "Verifying destination artifacts");
            }
            task.checkpoint();
            BackupRecord updated = task.commitIfActive(() -> update(backupId, existing -> existing.isDurable()
                    && outcomes.containsKey(existing.destination())
                    ? existing.withVerification(outcomes.get(existing.destination()).status())
                    : existing));
            report(listener, operationId, updated, BackupOperation.VERIFY, OperationPhase.COMPLETE, copies.size(),
                    copies.size(), "Backup verification complete");
            return RecoverySupport.withDestinations(updated, updated.result().destinations().stream()
                    .map(copy -> withWarning(copy, outcomes.get(copy.destination())))
                    .toList()).result();
        }
    }

    /** Uploads this computer's Git copy to the world's remote; a copy already there is left alone. */
    BackupResult sync(BackupId backupId, ProgressListener listener, CancellableTask<?> task) throws Exception {
        task.checkpoint();
        BackupRecord record = RecoverySupport.requireRecord(catalog, backupId);
        try (WorldOperationGate.Permit ignored = operationGate.enter(record.manifest().worldId())) {
            task.checkpoint();
            BackupRecord current = RecoverySupport.requireRecord(catalog, backupId);
            RecoverySupport.requireSameManifest(record, current);
            Optional<DestinationResult> gitCopy = RecoverySupport.copy(current, DestinationType.GIT);
            if (gitCopy.isEmpty()) {
                return current.result();
            }
            DestinationResult local = gitCopy.get();
            OperationId operationId = OperationId.create();
            if (local.status() == DestinationStatus.SUCCESS && local.syncStatus() == SyncStatus.SYNCED) {
                report(listener, operationId, current, BackupOperation.SYNC, OperationPhase.COMPLETE, 1, 1,
                        "Backup is already synchronized");
                return current.result();
            }
            report(listener, operationId, current, BackupOperation.SYNC, OperationPhase.PREPARING, 0, 1,
                    "Preparing Git synchronization");
            DestinationResult synced = upload(current, local);
            BackupRecord updated = task.mandatoryCommit(() -> update(backupId, existing ->
                    existing.destination() == DestinationType.GIT && existing.isDurable() ? synced : existing));
            task.checkpoint();
            report(listener, operationId, updated, BackupOperation.SYNC, OperationPhase.COMPLETE, 1, 1,
                    "Git synchronization complete");
            return updated.result();
        }
    }

    /** Checks one copy; one that cannot be read is UNAVAILABLE, and the log says why. */
    private VerificationOutcome check(BackupRecord record, DestinationResult copy) throws InterruptedException {
        try {
            VerificationOutcome outcome = destinations.get(copy.destination()).verify(record, copy);
            if (outcome.status() != VerificationStatus.VERIFIED) {
                LOGGER.warn("Verify: the {} copy of backup {} is damaged: {}",
                        copy.destination(), record.manifest().backupId(), outcome.message().orElse(""));
            }
            return outcome;
        } catch (InterruptedException interrupted) {
            throw interrupted;
        } catch (Exception failure) {
            LOGGER.warn("Verify: the {} copy of backup {} could not be checked: {}", copy.destination(),
                    record.manifest().backupId(), SafeText.from(failure, "it cannot be read", 512));
            return VerificationOutcome.unavailable();
        }
    }

    /** The copy with the warning its check found as its message, for the player to read; other copies as they are. */
    private static DestinationResult withWarning(DestinationResult copy, VerificationOutcome outcome) {
        return outcome != null && outcome.status() == VerificationStatus.VERIFIED && outcome.message().isPresent()
                ? copy.withState(copy.status(), outcome.message(), copy.syncStatus())
                : copy;
    }

    /** The Git copy after an upload attempt; any failure leaves it pending, ready to try again. */
    private DestinationResult upload(BackupRecord record, DestinationResult local) throws InterruptedException {
        DestinationResult uploaded;
        try {
            uploaded = git.sync(record, local);
        } catch (InterruptedException interrupted) {
            throw interrupted;
        } catch (Exception failure) {
            return pending(local, "Git synchronization failed: " + SafeText.from(failure, "Git failed", 512),
                    SyncStatus.FAILED);
        }
        return switch (uploaded.syncStatus()) {
            case SYNCED, NOT_CONFIGURED ->
                    local.withState(DestinationStatus.SUCCESS, Optional.empty(), uploaded.syncStatus());
            case NOT_SYNCED, PENDING, FAILED -> pending(
                    local, uploaded.message().orElse("Git synchronization must be retried"), uploaded.syncStatus());
        };
    }

    private BackupRecord update(BackupId backupId, UnaryOperator<DestinationResult> change) throws IOException {
        return catalog.update(backupId, existing -> RecoverySupport.withDestinations(
                        existing, existing.result().destinations().stream().map(change).toList()))
                .orElseThrow(() -> new BackupRecoveryException("This backup left the backup list while it was checked"));
    }

    private static DestinationResult pending(DestinationResult local, String message, SyncStatus status) {
        return local.withState(DestinationStatus.PENDING_SYNC, Optional.of(message), status);
    }

    private static void report(
            ProgressListener listener,
            OperationId operationId,
            BackupRecord record,
            BackupOperation operation,
            OperationPhase phase,
            long completed,
            long total,
            String message) {
        RecoverySupport.report(listener, RecoverySupport.progress(
                operationId, record, operation, phase, completed, total, message));
    }
}
