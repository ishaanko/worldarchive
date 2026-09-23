package dev.ishaanko.worldarchive.runtime;

import dev.ishaanko.worldarchive.core.BackupService;
import dev.ishaanko.worldarchive.core.DeleteBackupRequest;
import dev.ishaanko.worldarchive.core.RestoreBackupRequest;
import dev.ishaanko.worldarchive.core.RestoreBackupResult;
import dev.ishaanko.worldarchive.model.BackupId;
import dev.ishaanko.worldarchive.model.BackupRecord;
import dev.ishaanko.worldarchive.model.BackupResult;
import dev.ishaanko.worldarchive.model.ProgressListener;
import dev.ishaanko.worldarchive.model.SafeText;
import dev.ishaanko.worldarchive.model.WorldId;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The {@link BackupService} of the backup screens: the current state's recovery service. A restore,
 * delete, verify or sync holds a work permit until it has stopped, so no settings save moves the
 * backup folders under it.
 */
final class RuntimeBackupService implements BackupService {
    private static final Logger LOGGER = LoggerFactory.getLogger("WorldArchive");

    private final StateCalls calls;

    private final WorldIdentityResolver resolver;

    RuntimeBackupService(StateCalls calls, WorldIdentityResolver resolver) {
        this.calls = Objects.requireNonNull(calls, "calls");
        this.resolver = Objects.requireNonNull(resolver, "resolver");
    }

    @Override
    public CompletionStage<List<BackupRecord>> listBackups(Optional<WorldId> worldId) {
        return calls.withState(state -> state.recovery().listBackups(worldId));
    }

    /**
     * Restores into a new world and registers that world before the permit is released.
     * Cancelling the returned stage cancels the restore, which then removes its partial world.
     */
    @Override
    public CompletionStage<RestoreBackupResult> restoreBackup(
            RestoreBackupRequest request,
            ProgressListener progressListener) {
        return calls.withPermit(state -> registering(
                state.recovery().restoreBackup(request, progressListener).toCompletableFuture()));
    }

    @Override
    public CompletionStage<List<BackupResult>> deleteBackups(
            List<DeleteBackupRequest> requests,
            ProgressListener progressListener) {
        return calls.withPermit(state -> state.recovery().deleteBackups(requests, progressListener));
    }

    @Override
    public CompletionStage<BackupResult> verifyBackup(BackupId backupId, ProgressListener progressListener) {
        return calls.withPermit(state -> state.recovery().verifyBackup(backupId, progressListener));
    }

    @Override
    public CompletionStage<BackupResult> syncBackup(BackupId backupId, ProgressListener progressListener) {
        return calls.withPermit(state -> state.recovery().syncBackup(backupId, progressListener));
    }

    /**
     * The restore's result once its world is registered. Cancelling it cancels the restore; like
     * the restore's own stage, it completes only once the restore has stopped.
     */
    private CompletableFuture<RestoreBackupResult> registering(CompletableFuture<RestoreBackupResult> restore) {
        CompletableFuture<RestoreBackupResult> registered = new CompletableFuture<>() {
            @Override
            public boolean cancel(boolean mayInterruptIfRunning) {
                return restore.cancel(mayInterruptIfRunning);
            }
        };
        restore.whenComplete((result, failure) -> {
            if (failure != null) {
                registered.completeExceptionally(failure);
                return;
            }
            // The world exists either way; opening it or the world list registers it later.
            try {
                if (!resolver.register(result.restoredWorldId(), result.restoredWorldDirectory())) {
                    LOGGER.warn("The restored world {} could not be registered yet", result.restoredWorldDirectory());
                }
            } catch (RuntimeException registration) {
                LOGGER.warn("The restored world could not be registered yet: {}",
                        SafeText.from(registration, "no reason was given", 300));
            } finally {
                registered.complete(result);
            }
        });
        return registered;
    }
}
