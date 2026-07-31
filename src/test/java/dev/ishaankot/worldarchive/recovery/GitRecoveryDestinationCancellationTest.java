package dev.ishaankot.worldarchive.recovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.ishaankot.worldarchive.core.ProgressListener;
import dev.ishaankot.worldarchive.model.BackupRecord;
import dev.ishaankot.worldarchive.model.DestinationResult;
import dev.ishaankot.worldarchive.model.DestinationType;
import dev.ishaankot.worldarchive.model.SyncStatus;
import dev.ishaankot.worldarchive.storage.git.GitSnapshotStore;
import java.lang.reflect.Proxy;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

final class GitRecoveryDestinationCancellationTest
        extends BackupRecoveryServiceTestSupport {
    @Test
    void cancellingSyncDrainsRemoteGitBeforeCatalogPublication() throws Exception {
        Fixture fixture = fixture(DestinationType.GIT);
        DrainAwareFuture<DestinationResult> remote = new DrainAwareFuture<>();
        CountDownLatch syncRequested = new CountDownLatch(1);
        CountDownLatch catalogUpdated = new CountDownLatch(1);
        GitSnapshotStore backend = syncOnlyBackend(syncRequested, remote);
        InMemoryCatalog catalog = new InMemoryCatalog(fixture.record());
        catalog.afterUpdate = catalogUpdated::countDown;
        MutableClock clock = new MutableClock(CREATED_AT.plusSeconds(2));

        try (ExecutorService executor = Executors.newThreadPerTaskExecutor(
                Thread.ofVirtual().factory())) {
            BackupRecoveryService service = service(
                    catalog,
                    Map.of(
                            DestinationType.GIT,
                            new GitRecoveryDestination(backend, clock)),
                    clock,
                    RestoredWorldMetadataFinalizer.NO_OP,
                    executor);
            var future = service.syncBackup(
                            fixture.backupId(),
                            ProgressListener.NO_OP)
                    .toCompletableFuture();
            assertTrue(syncRequested.await(5, TimeUnit.SECONDS));

            assertTrue(future.cancel(true));
            remote.awaitDrainStarted();
            assertFalse(catalogUpdated.await(250, TimeUnit.MILLISECONDS));

            remote.complete(fixture.destination(DestinationType.GIT)
                    .withSync(SyncStatus.SYNCED));
            assertTrue(catalogUpdated.await(5, TimeUnit.SECONDS));
            assertThrows(CancellationException.class, future::join);
        }

        BackupRecord persisted = catalog.findUnchecked(
                        fixture.backupId())
                .orElseThrow();
        assertEquals(
                SyncStatus.SYNCED,
                destination(persisted.result(), DestinationType.GIT)
                        .syncStatus());
    }

    private static GitSnapshotStore syncOnlyBackend(
            CountDownLatch syncRequested,
            CompletionStage<DestinationResult> remote) {
        return (GitSnapshotStore) Proxy.newProxyInstance(
                GitSnapshotStore.class.getClassLoader(),
                new Class<?>[] {GitSnapshotStore.class},
                (ignored, method, arguments) -> switch (method.getName()) {
                    case "destinationType" -> DestinationType.GIT;
                    case "syncSnapshot" -> {
                        syncRequested.countDown();
                        yield remote;
                    }
                    case "close" -> null;
                    default -> throw new AssertionError(
                            "Unexpected Git operation: " + method.getName());
                });
    }
}
