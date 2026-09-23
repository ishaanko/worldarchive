package dev.ishaanko.worldarchive.recovery;

import dev.ishaanko.worldarchive.catalog.BackupCatalog;
import dev.ishaanko.worldarchive.config.WorldIdentityStore;
import dev.ishaanko.worldarchive.core.RestoreBackupRequest;
import dev.ishaanko.worldarchive.core.RestoreBackupResult;
import dev.ishaanko.worldarchive.core.WorldOperationGate;
import dev.ishaanko.worldarchive.model.BackupOperation;
import dev.ishaanko.worldarchive.model.BackupRecord;
import dev.ishaanko.worldarchive.model.DestinationResult;
import dev.ishaanko.worldarchive.model.DestinationType;
import dev.ishaanko.worldarchive.model.OperationId;
import dev.ishaanko.worldarchive.model.OperationPhase;
import dev.ishaanko.worldarchive.model.ProgressListener;
import dev.ishaanko.worldarchive.model.SafeText;
import dev.ishaanko.worldarchive.model.WorldIdentity;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Restores a backup into a new world, from its ZIP copy first and then from its Git copy. A copy
 * writes the world into a fresh private staging folder and checks every file as it writes; the
 * world then gets a new identity and its display name, and one rename publishes it. The source
 * world is never touched, and a restore that fails or is cancelled leaves nothing in the saves
 * folder. When no copy works, the error names each copy's reason.
 */
final class RecoveryRestoreOperation {
    private static final Comparator<DestinationResult> RESTORE_ORDER =
            Comparator.comparing(copy -> copy.destination() == DestinationType.ZIP ? 0 : 1);

    private final BackupCatalog catalog;

    private final Map<DestinationType, RecoveryDestination> destinations;

    private final WorldIdentityStore identityStore;

    private final RestoredWorldMetadataFinalizer metadataFinalizer;

    private final WorldOperationGate operationGate;

    private final Clock clock;

    RecoveryRestoreOperation(
            BackupCatalog catalog,
            Map<DestinationType, RecoveryDestination> destinations,
            WorldIdentityStore identityStore,
            RestoredWorldMetadataFinalizer metadataFinalizer,
            WorldOperationGate operationGate,
            Clock clock) {
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.destinations = Map.copyOf(destinations);
        this.identityStore = Objects.requireNonNull(identityStore, "identityStore");
        this.metadataFinalizer = Objects.requireNonNull(metadataFinalizer, "metadataFinalizer");
        this.operationGate = Objects.requireNonNull(operationGate, "operationGate");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    RestoreBackupResult restore(
            RestoreBackupRequest request,
            ProgressListener listener,
            CancellableTask<?> task) throws Exception {
        task.checkpoint();
        BackupRecord record = RecoverySupport.requireRecord(catalog, request.sourceBackupId());
        try (WorldOperationGate.Permit ignored = operationGate.enter(record.manifest().worldId())) {
            task.checkpoint();
            BackupRecord current = RecoverySupport.requireRecord(catalog, request.sourceBackupId());
            RecoverySupport.requireSameManifest(record, current);
            Progress progress = new Progress(listener, current);
            progress.report(OperationPhase.PREPARING, "Preparing restored world copy");
            RestoreWorkspace workspace = openWorkspace(request);
            List<String> failures = new ArrayList<>();
            for (DestinationResult copy : RecoverySupport.copies(current).stream().sorted(RESTORE_ORDER).toList()) {
                progress.report(OperationPhase.WRITING, "Restoring from the " + label(copy) + " copy");
                Path staging = workspace.createStaging();
                try {
                    destinations.get(copy.destination()).restore(current, copy, staging);
                    requireNoIdentityFolder(staging);
                } catch (InterruptedException interrupted) {
                    discard(workspace, staging, interrupted);
                    throw interrupted;
                } catch (Exception failure) {
                    discard(workspace, staging, failure);
                    task.checkpoint();
                    failures.add(label(copy) + ": " + SafeText.from(failure, "the copy could not be restored", 300));
                    continue;
                }
                return finish(request, current, workspace, staging, progress, task);
            }
            progress.report(OperationPhase.FAILED, "No copy of this backup could be restored");
            throw new BackupRecoveryException(failures.isEmpty()
                    ? "This backup has no copy left to restore from."
                    : "This backup could not be restored from any of its copies. " + String.join(" ", failures));
        }
    }

    private RestoreWorkspace openWorkspace(RestoreBackupRequest request) {
        try {
            return RestoreWorkspace.open(request.worldsDirectory(), clock);
        } catch (IOException exception) {
            throw new BackupRecoveryException("The worlds folder " + request.worldsDirectory() + " cannot be used: "
                    + SafeText.from(exception, "it cannot be opened", 300), exception);
        }
    }

    /** Names the restored world, gives it a new identity, and publishes it. */
    private RestoreBackupResult finish(
            RestoreBackupRequest request,
            BackupRecord record,
            RestoreWorkspace workspace,
            Path staging,
            Progress progress,
            CancellableTask<?> task) throws Exception {
        try {
            task.checkpoint();
            metadataFinalizer.finalizeDisplayName(staging, request.restoredWorldName());
            WorldIdentity identity = identityStore.createFreshRestoredCopyIdentity(staging, record.manifest().backupId());
            task.checkpoint();
            progress.report(OperationPhase.PUBLISHING, "Publishing restored world copy");
            Path published = workspace.publish(staging, request.restoredWorldName(), task);
            progress.report(OperationPhase.COMPLETE, "Restored world copy is ready");
            return new RestoreBackupResult(identity.worldId(), published);
        } catch (InterruptedException interrupted) {
            discard(workspace, staging, interrupted);
            throw interrupted;
        } catch (Exception failure) {
            discard(workspace, staging, failure);
            throw new BackupRecoveryException("The restored world could not be finished: "
                    + SafeText.from(failure, "it could not be written", 300), failure);
        }
    }

    /** A backup never holds WorldArchive's identity folder; a copy that does is not restored. */
    private static void requireNoIdentityFolder(Path staging) throws IOException {
        if (Files.exists(staging.resolve(".worldarchive"), LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("The copy holds WorldArchive's own identity folder (.worldarchive)");
        }
    }

    /** Removes a staging folder after a failure; one that cannot be removed is reported. */
    private static void discard(RestoreWorkspace workspace, Path staging, Exception failure) {
        try {
            workspace.delete(staging);
        } catch (IOException | RuntimeException cleanup) {
            failure.addSuppressed(cleanup);
            throw new BackupRecoveryException("Part of the restored world could not be removed from " + staging
                    + ". Delete that folder, then try again.", failure);
        }
    }

    private static String label(DestinationResult copy) {
        return copy.destination() == DestinationType.ZIP ? "ZIP" : "Git";
    }

    /** Progress of one restore. */
    private record Progress(ProgressListener listener, BackupRecord record, OperationId operationId) {
        private Progress(ProgressListener listener, BackupRecord record) {
            this(listener, record, OperationId.create());
        }

        /** A restore has no countable steps; it reports one unit, done when complete. */
        private void report(OperationPhase phase, String message) {
            long units = phase == OperationPhase.COMPLETE ? 1 : 0;
            RecoverySupport.report(listener, RecoverySupport.progress(
                    operationId, record, BackupOperation.RESTORE, phase, units, units, message));
        }
    }
}
