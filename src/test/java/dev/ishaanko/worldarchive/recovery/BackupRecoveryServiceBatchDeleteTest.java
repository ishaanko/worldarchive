package dev.ishaanko.worldarchive.recovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.ishaanko.worldarchive.catalog.BackupDeletionRegistry;
import dev.ishaanko.worldarchive.config.WorldIdentityStore;
import dev.ishaanko.worldarchive.core.DeleteBackupRequest;
import dev.ishaanko.worldarchive.core.DeletePreparation;
import dev.ishaanko.worldarchive.core.LockingWorldOperationGate;
import dev.ishaanko.worldarchive.core.ProgressListener;
import dev.ishaanko.worldarchive.core.WorldOperationGate;
import dev.ishaanko.worldarchive.model.BackupResult;
import dev.ishaanko.worldarchive.model.BackupStatus;
import dev.ishaanko.worldarchive.model.DestinationType;
import dev.ishaanko.worldarchive.model.WorldId;
import java.nio.file.Files;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

/** Batch deletion across several worlds, where one world's lane can refuse to open. */
class BackupRecoveryServiceBatchDeleteTest extends BackupRecoveryServiceTestSupport {
    @Test
    void deleteBackupsHandsBackTokensOfAWorldThatNeverStarted() {
        WorldId firstWorld = WorldId.create();
        WorldId secondWorld = WorldId.create();
        Fixture first = fixture(firstWorld, DestinationType.ZIP);
        Fixture second = fixture(secondWorld, DestinationType.ZIP);
        FakeDestination zip = new FakeDestination(DestinationType.ZIP, firstWorld);
        InMemoryCatalog catalog = new InMemoryCatalog(first.record(), second.record());
        LockingWorldOperationGate lanes = new LockingWorldOperationGate();
        AtomicBoolean refuseOnce = new AtomicBoolean(true);
        WorldOperationGate gate = worldId -> {
            if (worldId.equals(secondWorld) && refuseOnce.getAndSet(false)) {
                throw new IllegalStateException("second world lane is unavailable");
            }
            return lanes.enter(worldId);
        };
        BackupRecoveryService service = new BackupRecoveryService(
                catalog,
                Map.of(DestinationType.ZIP, zip),
                BackupDeletionRegistry.NONE,
                new WorldIdentityStore(),
                RestoredWorldMetadataFinalizer.NO_OP,
                Runnable::run,
                Clock.systemUTC(),
                BackupRecoveryService.DEFAULT_CONFIRMATION_LIFETIME,
                gate,
                Files::move);
        DeleteBackupRequest firstRequest = prepared(service, first);
        DeleteBackupRequest secondRequest = prepared(service, second);

        assertThrows(CompletionException.class, () -> service.deleteBackups(
                        List.of(firstRequest, secondRequest), ProgressListener.NO_OP)
                .toCompletableFuture().join());
        assertEquals(1, catalog.records.size());

        // The second world never started, so its confirmation is still valid.
        List<BackupResult> retried = service.deleteBackups(
                        List.of(secondRequest), ProgressListener.NO_OP)
                .toCompletableFuture().join();
        assertEquals(BackupStatus.SUCCESS, retried.getFirst().status());
        assertTrue(catalog.records.isEmpty());
    }

    private static DeleteBackupRequest prepared(BackupRecoveryService service, Fixture fixture) {
        DeletePreparation preparation = service.prepareDelete(fixture.backupId())
                .toCompletableFuture().join();
        return new DeleteBackupRequest(fixture.backupId(), preparation.confirmationToken());
    }
}
