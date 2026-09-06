package dev.ishaanko.worldarchive.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.ishaanko.worldarchive.catalog.BackupCatalog;
import dev.ishaanko.worldarchive.model.BackupId;
import dev.ishaanko.worldarchive.model.BackupResult;
import dev.ishaanko.worldarchive.model.BackupStatus;
import dev.ishaanko.worldarchive.model.BackupTrigger;
import dev.ishaanko.worldarchive.model.DestinationResult;
import dev.ishaanko.worldarchive.model.DestinationType;
import dev.ishaanko.worldarchive.model.WorldId;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Cancellation and rollback behavior of {@link SerializedBackupCoordinator#cancelBackup}. */
final class SerializedBackupCoordinatorCancelTest {
    private static final Instant NOW = Instant.parse("2026-07-17T12:00:00Z");

    @TempDir
    Path temporaryDirectory;

    private ExecutorService coordinatorExecutor;

    @BeforeEach
    void setUp() {
        coordinatorExecutor = Executors.newFixedThreadPool(4);
    }

    @AfterEach
    void tearDown() throws Exception {
        coordinatorExecutor.shutdownNow();
        assertTrue(coordinatorExecutor.awaitTermination(5, TimeUnit.SECONDS));
    }

    @Test
    void cancelBackupDuringDestinationWritesRollsBackAndPublishesNothing() throws Exception {
        InMemoryCatalog catalog = new InMemoryCatalog();
        InMemoryInventoryStore inventories = new InMemoryInventoryStore();
        CompletableFuture<DestinationResult> blocked = new CompletableFuture<>();
        FakeBackend backend = new FakeBackend(DestinationType.ZIP, ignored -> blocked);
        SerializedBackupCoordinator coordinator = coordinator(
                catalog,
                inventories,
                new FakeCaptureFactory(temporaryDirectory.resolve("captures-cancel-commit")),
                List.of(backend),
                BackupCaptureGate.DIRECT,
                new LockingWorldOperationGate());
        WorldId worldId = WorldId.create();
        AtomicReference<OperationId> operationId = new AtomicReference<>();
        AtomicReference<BackupId> backupId = new AtomicReference<>();
        CompletionStage<BackupResult> operation = coordinator.createBackup(
                request(worldId, "world-cancel-commit", BackupTrigger.MANUAL, Optional.empty()),
                progress -> {
                    operationId.set(progress.operationId());
                    progress.backupId().ifPresent(backupId::set);
                });
        await(() -> backend.calls.get() == 1);

        assertTrue(coordinator.cancelBackup(operationId.get()));

        assertThrows(CancellationException.class,
                () -> operation.toCompletableFuture().get(5, TimeUnit.SECONDS));
        await(() -> !coordinator.isBusy(worldId));
        assertEquals(List.of(backupId.get()), backend.discards);
        assertEquals(List.of(), catalog.records);
        assertEquals(Map.of(), inventories.values);
    }

    @Test
    void cancelBackupIsRejectedOnceFinalizationBegins() throws Exception {
        BlockingCatalog catalog = new BlockingCatalog();
        FakeBackend backend = FakeBackend.success(DestinationType.ZIP);
        SerializedBackupCoordinator coordinator = coordinator(
                catalog,
                new InMemoryInventoryStore(),
                new FakeCaptureFactory(temporaryDirectory.resolve("captures-cancel-late")),
                List.of(backend),
                BackupCaptureGate.DIRECT,
                new LockingWorldOperationGate());
        AtomicReference<OperationId> operationId = new AtomicReference<>();
        CompletionStage<BackupResult> operation = coordinator.createBackup(
                request(WorldId.create(), "world-cancel-late", BackupTrigger.MANUAL, Optional.empty()),
                progress -> operationId.set(progress.operationId()));
        assertTrue(catalog.entered.await(5, TimeUnit.SECONDS));

        assertFalse(coordinator.cancelBackup(operationId.get()));
        catalog.release.countDown();

        assertEquals(
                BackupStatus.SUCCESS,
                operation.toCompletableFuture().get(5, TimeUnit.SECONDS).status());
        assertEquals(1, catalog.records.size());
        assertEquals(List.of(), backend.discards);
    }

    @Test
    void cancelBackupKeepsAndRecordsAnArtifactThatCannotBeRemoved() throws Exception {
        InMemoryCatalog catalog = new InMemoryCatalog();
        InMemoryInventoryStore inventories = new InMemoryInventoryStore();
        FakeBackend zip = FakeBackend.success(DestinationType.ZIP);
        zip.discardFails = true;
        CompletableFuture<DestinationResult> blockedGit = new CompletableFuture<>();
        FakeBackend git = new FakeBackend(DestinationType.GIT, ignored -> blockedGit);
        SerializedBackupCoordinator coordinator = coordinator(
                catalog,
                inventories,
                new FakeCaptureFactory(temporaryDirectory.resolve("captures-cancel-keep")),
                List.of(zip, git),
                BackupCaptureGate.DIRECT,
                new LockingWorldOperationGate());
        WorldId worldId = WorldId.create();
        AtomicReference<OperationId> operationId = new AtomicReference<>();
        CompletionStage<BackupResult> operation = coordinator.createBackup(
                request(worldId, "world-cancel-keep", BackupTrigger.MANUAL, Optional.empty()),
                progress -> operationId.set(progress.operationId()));
        await(() -> git.calls.get() == 1);

        assertTrue(coordinator.cancelBackup(operationId.get()));

        BackupResult result = operation.toCompletableFuture().get(5, TimeUnit.SECONDS);
        assertEquals(BackupStatus.SUCCESS, result.status());
        assertEquals(1, result.destinations().size());
        assertEquals(DestinationType.ZIP, result.destinations().getFirst().destination());
        assertEquals(1, catalog.records.size());
        assertEquals(1, zip.discards.size());
        assertEquals(1, git.discards.size());
        assertEquals(Map.of(), inventories.values);
    }

    @Test
    void cancelBackupReportsADestinationWhoseRemovalIsUnconfirmed() throws Exception {
        InMemoryCatalog catalog = new InMemoryCatalog();
        InMemoryInventoryStore inventories = new InMemoryInventoryStore();
        CompletableFuture<DestinationResult> blocked = new CompletableFuture<>();
        FakeBackend backend = new FakeBackend(DestinationType.GIT, ignored -> blocked);
        backend.discardFails = true;
        SerializedBackupCoordinator coordinator = coordinator(
                catalog,
                inventories,
                new FakeCaptureFactory(temporaryDirectory.resolve("captures-cancel-unconfirmed")),
                List.of(backend),
                BackupCaptureGate.DIRECT,
                new LockingWorldOperationGate());
        WorldId worldId = WorldId.create();
        AtomicReference<OperationId> operationId = new AtomicReference<>();
        CompletionStage<BackupResult> operation = coordinator.createBackup(
                request(worldId, "world-cancel-unconfirmed", BackupTrigger.MANUAL, Optional.empty()),
                progress -> operationId.set(progress.operationId()));
        await(() -> backend.calls.get() == 1);

        assertTrue(coordinator.cancelBackup(operationId.get()));

        ExecutionException failure = assertThrows(ExecutionException.class,
                () -> operation.toCompletableFuture().get(5, TimeUnit.SECONDS));
        assertTrue(failure.getCause() instanceof IOException);
        assertTrue(failure.getCause().getMessage().contains("GIT"));
        assertEquals(List.of(), catalog.records);
        assertEquals(Map.of(), inventories.values);
    }

    @Test
    void cancelBackupForUnknownOperationReturnsFalse() throws Exception {
        SerializedBackupCoordinator coordinator = coordinator(
                new InMemoryCatalog(),
                new InMemoryInventoryStore(),
                new FakeCaptureFactory(temporaryDirectory.resolve("captures-cancel-unknown")),
                List.of(FakeBackend.success(DestinationType.ZIP)),
                BackupCaptureGate.DIRECT,
                new LockingWorldOperationGate());

        assertFalse(coordinator.cancelBackup(OperationId.create()));
    }

    private SerializedBackupCoordinator coordinator(
            BackupCatalog catalog,
            WorldInventoryStore inventories,
            BackupCaptureFactory captures,
            List<BackupBackend> backends,
            BackupCaptureGate gate,
            WorldOperationGate operationGate) {
        return new SerializedBackupCoordinator(
                catalog,
                captures,
                inventories,
                BackupDestinationSelector.fixed(backends),
                new UnusedMaintenanceService(),
                gate,
                operationGate,
                coordinatorExecutor,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private CreateBackupRequest request(
            WorldId worldId,
            String directoryName,
            BackupTrigger trigger,
            Optional<String> label) throws IOException {
        Path world = temporaryDirectory.resolve(directoryName);
        Files.createDirectories(world);
        return new CreateBackupRequest(worldId, world, directoryName, label, trigger);
    }

    private static void await(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() >= deadline) {
                throw new AssertionError("Condition was not satisfied before timeout");
            }
            Thread.sleep(10);
        }
    }
}
