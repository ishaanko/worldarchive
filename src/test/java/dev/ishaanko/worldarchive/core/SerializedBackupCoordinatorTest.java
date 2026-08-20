package dev.ishaanko.worldarchive.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.ishaanko.worldarchive.catalog.BackupCatalog;
import dev.ishaanko.worldarchive.model.BackupId;
import dev.ishaanko.worldarchive.model.BackupManifest;
import dev.ishaanko.worldarchive.model.BackupRecord;
import dev.ishaanko.worldarchive.model.BackupResult;
import dev.ishaanko.worldarchive.model.BackupStatus;
import dev.ishaanko.worldarchive.model.BackupTrigger;
import dev.ishaanko.worldarchive.model.DestinationHealth;
import dev.ishaanko.worldarchive.model.DestinationResult;
import dev.ishaanko.worldarchive.model.DestinationStatus;
import dev.ishaanko.worldarchive.model.DestinationType;
import dev.ishaanko.worldarchive.model.WorldId;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class SerializedBackupCoordinatorTest {
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
    void gateUsesCallerControlledThreadAndExitsBeforeDestinationBegins() throws Exception {
        InMemoryCatalog catalog = new InMemoryCatalog();
        InMemoryInventoryStore inventories = new InMemoryInventoryStore();
        FakeCaptureFactory captures = new FakeCaptureFactory(temporaryDirectory.resolve("captures"));
        AtomicReference<String> captureThread = new AtomicReference<>();
        captures.observer = ignored -> captureThread.set(Thread.currentThread().getName());
        AtomicBoolean gateExited = new AtomicBoolean();
        ExecutorService gateExecutor = Executors.newSingleThreadExecutor(runnable ->
                new Thread(runnable, "server-capture-thread"));
        BackupCaptureGate gate = task -> {
            try {
                CapturedBackup result = gateExecutor.submit(task::capture).get();
                gateExited.set(true);
                return result;
            } catch (ExecutionException exception) {
                if (exception.getCause() instanceof IOException ioException) {
                    throw ioException;
                }
                if (exception.getCause() instanceof InterruptedException interruptedException) {
                    throw interruptedException;
                }
                throw new IOException("Capture gate failed", exception.getCause());
            }
        };
        AtomicBoolean backendAfterGate = new AtomicBoolean();
        FakeBackend backend = new FakeBackend(DestinationType.ZIP, capture -> {
            backendAfterGate.set(gateExited.get());
            return CompletableFuture.completedFuture(DestinationResult.success(
                    DestinationType.ZIP,
                    "archive"));
        });
        SerializedBackupCoordinator coordinator = coordinator(
                catalog,
                inventories,
                captures,
                List.of(backend),
                gate,
                new LockingWorldOperationGate());
        try {
            BackupResult result = coordinator.createBackup(
                            request(WorldId.create(), "world-a", BackupTrigger.MANUAL, Optional.empty()),
                            ProgressListener.NO_OP)
                    .toCompletableFuture()
                    .get(5, TimeUnit.SECONDS);

            assertEquals(BackupStatus.SUCCESS, result.status());
            assertEquals("server-capture-thread", captureThread.get());
            assertTrue(backendAfterGate.get());
        } finally {
            gateExecutor.shutdownNow();
            assertTrue(gateExecutor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void preparedCaptureIsSynchronousAndQueuesOnlyDestinationWork() throws Exception {
        InMemoryCatalog catalog = new InMemoryCatalog();
        InMemoryInventoryStore inventories = new InMemoryInventoryStore();
        FakeCaptureFactory captures = new FakeCaptureFactory(temporaryDirectory.resolve("captures"));
        AtomicReference<Thread> captureThread = new AtomicReference<>();
        captures.observer = ignored -> captureThread.set(Thread.currentThread());
        FakeBackend backend = FakeBackend.success(DestinationType.ZIP);
        BackupCaptureGate forbiddenGate = task -> {
            throw new AssertionError("Prepared captures must not re-enter the async capture gate");
        };
        LockingWorldOperationGate operationGate = new LockingWorldOperationGate();
        SerializedBackupCoordinator coordinator = coordinator(
                catalog,
                inventories,
                captures,
                List.of(backend),
                forbiddenGate,
                operationGate);
        CreateBackupRequest request = request(
                WorldId.create(), "world-a", BackupTrigger.WORLD_EXIT, Optional.empty());

        PreparedBackup prepared = coordinator.prepareCapture(request, CaptureProgressListener.NO_OP);
        assertSame(Thread.currentThread(), captureThread.get());
        assertEquals(0, backend.calls.get());

        BackupResult result = coordinator.createPreparedBackup(prepared, ProgressListener.NO_OP)
                .toCompletableFuture()
                .get(5, TimeUnit.SECONDS);
        assertEquals(BackupStatus.SUCCESS, result.status());
        assertEquals(1, backend.calls.get());
        try (WorldOperationGate.Permit ignored = operationGate.enter(request.worldId())) {
            assertTrue(true);
        }
    }

    @Test
    void preparedCaptureReleaseObserversRunOnceForCloseAndClaim() throws Exception {
        SerializedBackupCoordinator coordinator = coordinator(
                new InMemoryCatalog(),
                new InMemoryInventoryStore(),
                new FakeCaptureFactory(temporaryDirectory.resolve("captures")),
                List.of(FakeBackend.success(DestinationType.ZIP)),
                BackupCaptureGate.DIRECT,
                new LockingWorldOperationGate());
        CreateBackupRequest request = request(
                WorldId.create(), "world-a", BackupTrigger.MANUAL, Optional.empty());
        AtomicInteger closedReleases = new AtomicInteger();
        PreparedBackup closed = coordinator.prepareCapture(
                request,
                CaptureProgressListener.NO_OP);
        closed.addReleaseObserver(closedReleases::incrementAndGet);

        closed.close();
        closed.close();
        assertEquals(1, closedReleases.get());
        AtomicInteger lateReleases = new AtomicInteger();
        closed.addReleaseObserver(lateReleases::incrementAndGet);
        assertEquals(1, lateReleases.get());

        AtomicInteger claimedReleases = new AtomicInteger();
        PreparedBackup claimed = coordinator.prepareCapture(
                request,
                CaptureProgressListener.NO_OP);
        claimed.addReleaseObserver(claimedReleases::incrementAndGet);
        coordinator.createPreparedBackup(claimed, ProgressListener.NO_OP)
                .toCompletableFuture()
                .get(5, TimeUnit.SECONDS);
        assertEquals(1, claimedReleases.get());
    }

    @Test
    void preparedCaptureReleaseObserverCannotBlockOwnershipTransfer() throws Exception {
        Path captures = temporaryDirectory.resolve("captures-release-observer");
        FakeBackend backend = FakeBackend.success(DestinationType.ZIP);
        SerializedBackupCoordinator coordinator = coordinator(
                new InMemoryCatalog(),
                new InMemoryInventoryStore(),
                new FakeCaptureFactory(captures),
                List.of(backend),
                BackupCaptureGate.DIRECT,
                new LockingWorldOperationGate());
        PreparedBackup prepared = coordinator.prepareCapture(
                request(
                        WorldId.create(),
                        "world-release-observer",
                        BackupTrigger.MANUAL,
                        Optional.empty()),
                CaptureProgressListener.NO_OP);
        prepared.addReleaseObserver(() -> {
            throw new IllegalStateException("observer failure");
        });

        BackupResult result = coordinator.createPreparedBackup(prepared, ProgressListener.NO_OP)
                .toCompletableFuture()
                .get(5, TimeUnit.SECONDS);

        assertEquals(BackupStatus.SUCCESS, result.status());
        assertEquals(1, backend.calls.get());
        try (var entries = Files.list(captures)) {
            assertEquals(List.of(), entries.toList());
        }
    }

    @Test
    void preparedCaptureClosesResourcesWhenAReleaseObserverThrowsAnError() throws Exception {
        Path captures = temporaryDirectory.resolve("captures-release-error");
        SerializedBackupCoordinator coordinator = coordinator(
                new InMemoryCatalog(),
                new InMemoryInventoryStore(),
                new FakeCaptureFactory(captures),
                List.of(FakeBackend.success(DestinationType.ZIP)),
                BackupCaptureGate.DIRECT,
                new LockingWorldOperationGate());
        PreparedBackup prepared = coordinator.prepareCapture(
                request(
                        WorldId.create(),
                        "world-release-error",
                        BackupTrigger.MANUAL,
                        Optional.empty()),
                CaptureProgressListener.NO_OP);
        prepared.addReleaseObserver(() -> {
            throw new AssertionError("observer error");
        });

        assertThrows(
                AssertionError.class,
                () -> coordinator.createPreparedBackup(prepared, ProgressListener.NO_OP));

        try (var entries = Files.list(captures)) {
            assertEquals(List.of(), entries.toList());
        }
    }

    @Test
    void coalescesCompatibleCreatesSerializesOneWorldAndRunsOtherWorld() throws Exception {
        InMemoryCatalog catalog = new InMemoryCatalog();
        InMemoryInventoryStore inventories = new InMemoryInventoryStore();
        FakeCaptureFactory captures = new FakeCaptureFactory(temporaryDirectory.resolve("captures"));
        Map<String, CompletableFuture<DestinationResult>> releases = new ConcurrentHashMap<>();
        FakeBackend backend = new FakeBackend(DestinationType.ZIP, capture -> {
            String key = key(capture.manifest());
            CompletableFuture<DestinationResult> release = new CompletableFuture<>();
            releases.put(key, release);
            return release;
        });
        SerializedBackupCoordinator coordinator = coordinator(
                catalog,
                inventories,
                captures,
                List.of(backend),
                BackupCaptureGate.DIRECT,
                new LockingWorldOperationGate());
        WorldId worldA = WorldId.create();
        WorldId worldB = WorldId.create();
        CreateBackupRequest firstRequest = request(
                worldA, "world-a", BackupTrigger.MANUAL, Optional.empty());
        CompletionStage<BackupResult> first = coordinator.createBackup(
                firstRequest, ProgressListener.NO_OP);
        CompletionStage<BackupResult> coalesced = coordinator.createBackup(
                firstRequest, ProgressListener.NO_OP);
        CompletionStage<BackupResult> queued = coordinator.createBackup(
                request(worldA, "world-a", BackupTrigger.MANUAL, Optional.of("later")),
                ProgressListener.NO_OP);
        CompletionStage<BackupResult> otherWorld = coordinator.createBackup(
                request(worldB, "world-b", BackupTrigger.MANUAL, Optional.empty()),
                ProgressListener.NO_OP);

        assertSame(first, coalesced);
        await(() -> releases.containsKey(worldA + ":none")
                && releases.containsKey(worldB + ":none"));
        assertFalse(releases.containsKey(worldA + ":later"));

        releases.get(worldB + ":none").complete(DestinationResult.success(DestinationType.ZIP, "b"));
        releases.get(worldA + ":none").complete(DestinationResult.success(DestinationType.ZIP, "a"));
        assertEquals(BackupStatus.SUCCESS, otherWorld.toCompletableFuture().get(5, TimeUnit.SECONDS).status());
        assertEquals(BackupStatus.SUCCESS, first.toCompletableFuture().get(5, TimeUnit.SECONDS).status());
        await(() -> releases.containsKey(worldA + ":later"));
        releases.get(worldA + ":later").complete(DestinationResult.success(DestinationType.ZIP, "later"));
        assertEquals(BackupStatus.SUCCESS, queued.toCompletableFuture().get(5, TimeUnit.SECONDS).status());
        assertEquals(3, captures.calls.get());
    }

    @Test
    void mergesPartialResultsAndPersistsCatalogAndInventoryOnce() throws Exception {
        InMemoryCatalog catalog = new InMemoryCatalog();
        InMemoryInventoryStore inventories = new InMemoryInventoryStore();
        FakeCaptureFactory captures = new FakeCaptureFactory(temporaryDirectory.resolve("captures"));
        List<Path> observedRoots = new java.util.concurrent.CopyOnWriteArrayList<>();
        FakeBackend git = new FakeBackend(DestinationType.GIT, capture -> {
            observedRoots.add(capture.worldDirectory());
            return CompletableFuture.completedFuture(DestinationResult.success(DestinationType.GIT, "ref"));
        });
        FakeBackend zip = new FakeBackend(DestinationType.ZIP, capture -> {
            observedRoots.add(capture.worldDirectory());
            return CompletableFuture.failedFuture(new IOException("untrustworthy failure"));
        });
        SerializedBackupCoordinator coordinator = coordinator(
                catalog,
                inventories,
                captures,
                List.of(git, zip),
                BackupCaptureGate.DIRECT,
                new LockingWorldOperationGate());
        WorldId worldId = WorldId.create();

        BackupResult result = coordinator.createBackup(
                        request(worldId, "world-a", BackupTrigger.MANUAL, Optional.empty()),
                        ProgressListener.NO_OP)
                .toCompletableFuture()
                .get(5, TimeUnit.SECONDS);

        assertEquals(BackupStatus.PARTIAL_SUCCESS, result.status());
        assertEquals(1, catalog.records.size());
        assertTrue(inventories.values.containsKey(worldId));
        assertEquals(2, observedRoots.size());
        assertEquals(1, observedRoots.stream().distinct().count());
        assertFalse(Files.exists(observedRoots.getFirst()));
    }

    @Test
    void scheduledCreateSkipsKnownUnchangedWorldWithoutPublishing() throws Exception {
        InMemoryCatalog catalog = new InMemoryCatalog();
        InMemoryInventoryStore inventories = new InMemoryInventoryStore();
        FakeCaptureFactory captures = new FakeCaptureFactory(temporaryDirectory.resolve("captures"));
        WorldId worldId = WorldId.create();
        inventories.values.put(worldId, captures.inventory);
        FakeBackend backend = FakeBackend.success(DestinationType.ZIP);
        SerializedBackupCoordinator coordinator = coordinator(
                catalog,
                inventories,
                captures,
                List.of(backend),
                BackupCaptureGate.DIRECT,
                new LockingWorldOperationGate());

        BackupResult result = coordinator.createBackup(
                        request(worldId, "world-a", BackupTrigger.SCHEDULED, Optional.empty()),
                        ProgressListener.NO_OP)
                .toCompletableFuture()
                .get(5, TimeUnit.SECONDS);

        assertEquals(BackupStatus.SKIPPED, result.status());
        assertEquals(List.of(DestinationStatus.SKIPPED), result.destinations().stream()
                .map(DestinationResult::status)
                .toList());
        assertEquals(0, backend.calls.get());
        assertEquals(List.of(), catalog.records);
    }

    @Test
    void corruptInventoryDoesNotBlockManualOrPreparedExitBackups() throws Exception {
        InMemoryCatalog catalog = new InMemoryCatalog();
        InMemoryInventoryStore inventories = new InMemoryInventoryStore();
        inventories.loadFailure = new IOException("simulated corrupt inventory");
        FakeBackend backend = FakeBackend.success(DestinationType.ZIP);
        SerializedBackupCoordinator coordinator = coordinator(
                catalog,
                inventories,
                new FakeCaptureFactory(temporaryDirectory.resolve("corrupt-inventory-captures")),
                List.of(backend),
                BackupCaptureGate.DIRECT,
                new LockingWorldOperationGate());
        WorldId worldId = WorldId.create();

        BackupResult manual = coordinator.createBackup(
                        request(worldId, "world-corrupt-inventory", BackupTrigger.MANUAL, Optional.empty()),
                        ProgressListener.NO_OP)
                .toCompletableFuture()
                .get(5, TimeUnit.SECONDS);
        PreparedBackup preparedExit = coordinator.prepareCapture(
                request(worldId, "world-corrupt-inventory", BackupTrigger.WORLD_EXIT, Optional.empty()),
                CaptureProgressListener.NO_OP);
        BackupResult exit = coordinator.createPreparedBackup(preparedExit, ProgressListener.NO_OP)
                .toCompletableFuture()
                .get(5, TimeUnit.SECONDS);

        assertEquals(BackupStatus.SUCCESS, manual.status());
        assertEquals(BackupStatus.SUCCESS, exit.status());
        assertEquals(2, backend.calls.get());
        assertEquals(2, catalog.records.size());
        assertTrue(inventories.values.containsKey(worldId));
    }

    @Test
    void cancellationInterruptsCaptureAndPublishesNothing() throws Exception {
        Path world = Files.createDirectory(temporaryDirectory.resolve("world-cancel"));
        Files.writeString(world.resolve("level.dat"), "contents", StandardCharsets.UTF_8);
        Path capturesRoot = temporaryDirectory.resolve("captures-real");
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        FileSystemBackupCaptureFactory captures = new FileSystemBackupCaptureFactory(
                capturesRoot,
                new SourceCaptureObserver() {
                    @Override
                    public void beforeFileCopy(Path relativePath) throws InterruptedException {
                        entered.countDown();
                        release.await();
                    }
                });
        InMemoryCatalog catalog = new InMemoryCatalog();
        InMemoryInventoryStore inventories = new InMemoryInventoryStore();
        FakeBackend backend = FakeBackend.success(DestinationType.ZIP);
        SerializedBackupCoordinator coordinator = coordinator(
                catalog,
                inventories,
                captures,
                List.of(backend),
                BackupCaptureGate.DIRECT,
                new LockingWorldOperationGate());
        WorldId worldId = WorldId.create();
        CompletionStage<BackupResult> operation = coordinator.createBackup(
                new CreateBackupRequest(
                        worldId, world, "Cancel World", BackupTrigger.MANUAL),
                ProgressListener.NO_OP);
        assertTrue(entered.await(5, TimeUnit.SECONDS));

        assertTrue(operation.toCompletableFuture().cancel(true));
        release.countDown();
        await(() -> !coordinator.isBusy(worldId));
        await(() -> {
            if (!Files.isDirectory(capturesRoot)) {
                return false;
            }
            try (var entries = Files.list(capturesRoot)) {
                return entries.findAny().isEmpty();
            } catch (IOException exception) {
                return false;
            }
        });
        assertEquals(0, backend.calls.get());
        assertEquals(List.of(), catalog.records);
        assertEquals(Map.of(), inventories.values);
    }

    @Test
    void sourceMutationFailsBeforeAnyDestinationOrCatalogPublication() throws Exception {
        Path world = Files.createDirectory(temporaryDirectory.resolve("world-mutation"));
        Files.writeString(world.resolve("level.dat"), "before", StandardCharsets.UTF_8);
        java.util.concurrent.atomic.AtomicInteger mutations =
                new java.util.concurrent.atomic.AtomicInteger();
        FileSystemBackupCaptureFactory captures = new FileSystemBackupCaptureFactory(
                temporaryDirectory.resolve("captures-mutation"),
                new SourceCaptureObserver() {
                    @Override
                    public void afterFileCopy(Path relativePath) throws IOException {
                        Files.writeString(
                                world.resolve(relativePath),
                                "after-" + mutations.incrementAndGet(),
                                StandardCharsets.UTF_8);
                    }
                });
        InMemoryCatalog catalog = new InMemoryCatalog();
        InMemoryInventoryStore inventories = new InMemoryInventoryStore();
        FakeBackend backend = FakeBackend.success(DestinationType.ZIP);
        SerializedBackupCoordinator coordinator = coordinator(
                catalog,
                inventories,
                captures,
                List.of(backend),
                BackupCaptureGate.DIRECT,
                new LockingWorldOperationGate());

        ExecutionException failure = assertThrows(ExecutionException.class, () -> coordinator.createBackup(
                        new CreateBackupRequest(
                                WorldId.create(), world, "Mutation World", BackupTrigger.MANUAL),
                        ProgressListener.NO_OP)
                .toCompletableFuture()
                .get(5, TimeUnit.SECONDS));

        assertTrue(failure.getCause() instanceof IOException);
        assertEquals(0, backend.calls.get());
        assertEquals(List.of(), catalog.records);
        assertEquals(Map.of(), inventories.values);
    }

    @Test
    void cancellationIsRejectedAfterDestinationPublicationBegins() throws Exception {
        BlockingCatalog catalog = new BlockingCatalog();
        FakeBackend backend = FakeBackend.success(DestinationType.ZIP);
        SerializedBackupCoordinator coordinator = coordinator(
                catalog,
                new InMemoryInventoryStore(),
                new FakeCaptureFactory(temporaryDirectory.resolve("captures-commit")),
                List.of(backend),
                BackupCaptureGate.DIRECT,
                new LockingWorldOperationGate());
        CompletionStage<BackupResult> operation = coordinator.createBackup(
                request(WorldId.create(), "world-commit", BackupTrigger.MANUAL, Optional.empty()),
                ProgressListener.NO_OP);
        assertTrue(catalog.entered.await(5, TimeUnit.SECONDS));

        assertFalse(operation.toCompletableFuture().cancel(true));
        catalog.release.countDown();

        assertEquals(
                BackupStatus.SUCCESS,
                operation.toCompletableFuture().get(5, TimeUnit.SECONDS).status());
        assertEquals(1, catalog.records.size());
    }

    @Test
    void sharedWorldGateBlocksCreateUntilExternalMaintenancePermitCloses() throws Exception {
        LockingWorldOperationGate operationGate = new LockingWorldOperationGate();
        WorldId worldId = WorldId.create();
        WorldOperationGate.Permit maintenance = operationGate.enter(worldId);
        FakeBackend backend = FakeBackend.success(DestinationType.ZIP);
        SerializedBackupCoordinator coordinator = coordinator(
                new InMemoryCatalog(),
                new InMemoryInventoryStore(),
                new FakeCaptureFactory(temporaryDirectory.resolve("captures")),
                List.of(backend),
                BackupCaptureGate.DIRECT,
                operationGate);

        CompletionStage<BackupResult> operation = coordinator.createBackup(
                request(worldId, "world-a", BackupTrigger.MANUAL, Optional.empty()),
                ProgressListener.NO_OP);
        await(() -> coordinator.isBusy(worldId));
        assertEquals(0, backend.calls.get());

        maintenance.close();
        assertEquals(
                BackupStatus.SUCCESS,
                operation.toCompletableFuture().get(5, TimeUnit.SECONDS).status());
    }

    @Test
    void preparedCaptureDoesNotWaitForActiveDestinationAndQueuesBehindIt() throws Exception {
        WorldId worldId = WorldId.create();
        Map<BackupTrigger, CompletableFuture<DestinationResult>> releases = new ConcurrentHashMap<>();
        FakeBackend backend = new FakeBackend(DestinationType.ZIP, capture -> {
            CompletableFuture<DestinationResult> result = new CompletableFuture<>();
            releases.put(capture.manifest().trigger(), result);
            return result;
        });
        SerializedBackupCoordinator coordinator = coordinator(
                new InMemoryCatalog(),
                new InMemoryInventoryStore(),
                new FakeCaptureFactory(temporaryDirectory.resolve("captures")),
                List.of(backend),
                BackupCaptureGate.DIRECT,
                new LockingWorldOperationGate());
        ExecutorService serverExecutor = Executors.newSingleThreadExecutor();
        try {
            CompletionStage<BackupResult> normal = coordinator.createBackup(
                    request(worldId, "world-a", BackupTrigger.MANUAL, Optional.empty()),
                    ProgressListener.NO_OP);
            await(() -> releases.containsKey(BackupTrigger.MANUAL));

            Future<PreparedBackup> preparation = serverExecutor.submit(() -> coordinator.prepareCapture(
                    request(worldId, "world-a", BackupTrigger.WORLD_EXIT, Optional.empty()),
                    CaptureProgressListener.NO_OP));
            PreparedBackup prepared = preparation.get(5, TimeUnit.SECONDS);
            assertEquals(OperationPhase.PREPARING,
                    coordinator.currentOperation(worldId).orElseThrow().phase());

            CompletionStage<BackupResult> exit = coordinator.createPreparedBackup(
                    prepared,
                    ProgressListener.NO_OP);
            assertFalse(releases.containsKey(BackupTrigger.WORLD_EXIT));

            releases.get(BackupTrigger.MANUAL).complete(DestinationResult.success(
                    DestinationType.ZIP,
                    "manual"));
            assertEquals(BackupStatus.SUCCESS, normal.toCompletableFuture().get(5, TimeUnit.SECONDS).status());

            await(() -> releases.containsKey(BackupTrigger.WORLD_EXIT));
            releases.get(BackupTrigger.WORLD_EXIT).complete(DestinationResult.success(
                    DestinationType.ZIP,
                    "exit"));
            assertEquals(BackupStatus.SUCCESS, exit.toCompletableFuture().get(5, TimeUnit.SECONDS).status());
        } finally {
            serverExecutor.shutdownNow();
            assertTrue(serverExecutor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void combinesTheProgressOfBothDestinationsIntoOneStream() throws Exception {
        Map<DestinationType, ProgressListener> listeners = new ConcurrentHashMap<>();
        Map<DestinationType, CompletableFuture<DestinationResult>> releases = new ConcurrentHashMap<>();
        List<OperationProgress> reported = java.util.Collections.synchronizedList(new ArrayList<>());
        SerializedBackupCoordinator coordinator = coordinator(
                new InMemoryCatalog(),
                new InMemoryInventoryStore(),
                new FakeCaptureFactory(temporaryDirectory.resolve("captures-combined")),
                List.of(
                        pendingBackend(DestinationType.GIT, listeners, releases),
                        pendingBackend(DestinationType.ZIP, listeners, releases)),
                BackupCaptureGate.DIRECT,
                new LockingWorldOperationGate());

        CompletionStage<BackupResult> operation = coordinator.createBackup(
                request(WorldId.create(), "world-combined", BackupTrigger.MANUAL, Optional.empty()),
                reported::add);
        await(() -> listeners.size() == 2);
        listeners.get(DestinationType.ZIP).onProgress(backendProgress(
                OperationPhase.WRITING, 900, 1_000, "Writing ZIP backup"));
        listeners.get(DestinationType.GIT).onProgress(backendProgress(
                OperationPhase.READING, 3, 6, "Synchronizing Git snapshot"));
        listeners.get(DestinationType.ZIP).onProgress(backendProgress(
                OperationPhase.COMPLETE, 1_000, 1_000, "ZIP backup complete"));

        List<OperationProgress> writing = List.copyOf(reported).stream()
                .dropWhile(progress -> progress.phase() != OperationPhase.WRITING)
                .toList();
        assertEquals(
                List.of(OperationPhase.WRITING),
                writing.stream().map(OperationProgress::phase).distinct().toList());
        assertEquals(
                List.of("Writing backup destinations"),
                writing.stream().map(OperationProgress::message).distinct().toList());
        assertEquals(4, writing.size());
        assertTrue(
                writing.getLast().completedUnits() > 0,
                "The combined progress must advance while the destinations write");
        assertMonotonic(writing);

        releases.get(DestinationType.ZIP).complete(DestinationResult.success(DestinationType.ZIP, "zip"));
        releases.get(DestinationType.GIT).complete(DestinationResult.success(DestinationType.GIT, "ref"));
        assertEquals(
                BackupStatus.SUCCESS,
                operation.toCompletableFuture().get(5, TimeUnit.SECONDS).status());
    }

    @Test
    void oneDestinationKeepsItsOwnProgressAndRedactsTheMessage() throws Exception {
        Map<DestinationType, ProgressListener> listeners = new ConcurrentHashMap<>();
        Map<DestinationType, CompletableFuture<DestinationResult>> releases = new ConcurrentHashMap<>();
        List<OperationProgress> reported = java.util.Collections.synchronizedList(new ArrayList<>());
        SerializedBackupCoordinator coordinator = coordinator(
                new InMemoryCatalog(),
                new InMemoryInventoryStore(),
                new FakeCaptureFactory(temporaryDirectory.resolve("captures-single")),
                List.of(pendingBackend(DestinationType.GIT, listeners, releases)),
                BackupCaptureGate.DIRECT,
                new LockingWorldOperationGate());

        CompletionStage<BackupResult> operation = coordinator.createBackup(
                request(WorldId.create(), "world-single", BackupTrigger.MANUAL, Optional.empty()),
                reported::add);
        await(() -> listeners.size() == 1);
        listeners.get(DestinationType.GIT).onProgress(backendProgress(
                OperationPhase.VERIFYING,
                3,
                6,
                "Pushing to https://user:secret@example.com/world.git"));

        OperationProgress forwarded = reported.getLast();
        assertEquals(OperationPhase.VERIFYING, forwarded.phase());
        assertEquals(3, forwarded.completedUnits());
        assertEquals(6, forwarded.totalUnits());
        assertEquals(
                "Pushing to https://[REDACTED]@example.com/world.git",
                forwarded.message());

        releases.get(DestinationType.GIT).complete(DestinationResult.success(DestinationType.GIT, "ref"));
        assertEquals(
                BackupStatus.SUCCESS,
                operation.toCompletableFuture().get(5, TimeUnit.SECONDS).status());
    }

    /** Backend that publishes its progress listener and waits for the test to release its result. */
    private static FakeBackend pendingBackend(
            DestinationType destination,
            Map<DestinationType, ProgressListener> listeners,
            Map<DestinationType, CompletableFuture<DestinationResult>> releases) {
        return new FakeBackend(destination, (capture, listener) -> {
            CompletableFuture<DestinationResult> release = new CompletableFuture<>();
            releases.put(destination, release);
            listeners.put(destination, listener);
            return release;
        });
    }

    private static OperationProgress backendProgress(
            OperationPhase phase,
            long completed,
            long total,
            String message) {
        return new OperationProgress(
                OperationId.create(),
                WorldId.create(),
                Optional.of(BackupId.create()),
                BackupOperation.CREATE,
                phase,
                completed,
                total,
                message);
    }

    private static void assertMonotonic(List<OperationProgress> reported) {
        long previous = 0;
        for (OperationProgress progress : reported) {
            assertTrue(
                    progress.completedUnits() >= previous,
                    "Combined progress moved backward to " + progress.completedUnits());
            previous = progress.completedUnits();
        }
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

    private static String key(BackupManifest manifest) {
        return manifest.worldId() + ":" + manifest.label().orElse("none");
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

    private static final class FakeCaptureFactory implements BackupCaptureFactory {
        private final Path root;

        private final AtomicInteger calls = new AtomicInteger();

        private final WorldInventory inventory;

        private volatile java.util.function.Consumer<CreateBackupRequest> observer = ignored -> {
        };

        private FakeCaptureFactory(Path root) throws Exception {
            this.root = root;
            byte[] contents = "contents".getBytes(StandardCharsets.UTF_8);
            this.inventory = WorldInventory.create(List.of(new WorldInventory.Entry(
                    "level.dat",
                    contents.length,
                    java.util.HexFormat.of().formatHex(
                            MessageDigest.getInstance("SHA-256").digest(contents)))));
        }

        @Override
        public CapturedBackup capture(
                CreateBackupRequest request,
                BackupId backupId,
                Instant createdAt,
                Optional<WorldInventory> previousInventory,
                CaptureProgressListener progressListener) throws IOException {
            observer.accept(request);
            Files.createDirectories(root);
            Path staging = Files.createDirectory(root.resolve("capture-" + calls.incrementAndGet()));
            long changed = previousInventory.map(inventory::changedFilesSince).orElse(inventory.fileCount());
            BackupManifest manifest = BackupManifest.create(
                    backupId,
                    request.worldId(),
                    request.worldName(),
                    request.label(),
                    createdAt,
                    request.trigger(),
                    inventory.fileCount(),
                    inventory.byteCount(),
                    changed,
                    inventory.contentSha256(),
                    inventory.inventorySha256());
            return new CapturedBackup(
                    new BackupCapture(staging, manifest),
                    inventory,
                    () -> Files.deleteIfExists(staging));
        }
    }

    private static final class FakeBackend implements BackupBackend {
        private final DestinationType destination;

        private final BiFunction<BackupCapture, ProgressListener, CompletionStage<DestinationResult>> result;

        private final AtomicInteger calls = new AtomicInteger();

        private FakeBackend(
                DestinationType destination,
                Function<BackupCapture, CompletionStage<DestinationResult>> result) {
            this(destination, (capture, ignored) -> result.apply(capture));
        }

        private FakeBackend(
                DestinationType destination,
                BiFunction<BackupCapture, ProgressListener, CompletionStage<DestinationResult>> result) {
            this.destination = destination;
            this.result = result;
        }

        private static FakeBackend success(DestinationType destination) {
            return new FakeBackend(destination, ignored -> CompletableFuture.completedFuture(
                    DestinationResult.success(destination, destination.name().toLowerCase())));
        }

        @Override
        public DestinationType destinationType() {
            return destination;
        }

        @Override
        public CompletionStage<DestinationResult> createBackup(
                BackupCapture capture,
                ProgressListener progressListener) {
            calls.incrementAndGet();
            return result.apply(capture, progressListener);
        }
    }

    private static final class InMemoryInventoryStore implements WorldInventoryStore {
        private final Map<WorldId, WorldInventory> values = new ConcurrentHashMap<>();

        private IOException loadFailure;

        @Override
        public Optional<WorldInventory> load(WorldId worldId) throws IOException {
            if (loadFailure != null) {
                throw loadFailure;
            }
            return Optional.ofNullable(values.get(worldId));
        }

        @Override
        public void save(WorldId worldId, WorldInventory inventory) {
            values.put(worldId, inventory);
        }
    }

    private static class InMemoryCatalog implements BackupCatalog {
        protected final List<BackupRecord> records = java.util.Collections.synchronizedList(new ArrayList<>());

        @Override
        public void add(BackupRecord record) throws IOException {
            records.add(record);
        }

        @Override
        public Optional<BackupRecord> find(BackupId backupId) {
            return records.stream()
                    .filter(record -> record.manifest().backupId().equals(backupId))
                    .findFirst();
        }

        @Override
        public List<BackupRecord> listAll() {
            return List.copyOf(records);
        }

        @Override
        public List<BackupRecord> list(WorldId worldId) {
            return records.stream()
                    .filter(record -> record.manifest().worldId().equals(worldId))
                    .toList();
        }

        @Override
        public Optional<BackupRecord> update(
                BackupId backupId,
                UnaryOperator<BackupRecord> update) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean remove(BackupId backupId) {
            return records.removeIf(record -> record.manifest().backupId().equals(backupId));
        }
    }

    private static final class BlockingCatalog extends InMemoryCatalog {
        private final CountDownLatch entered = new CountDownLatch(1);

        private final CountDownLatch release = new CountDownLatch(1);

        @Override
        public void add(BackupRecord record) throws IOException {
            entered.countDown();
            try {
                if (!release.await(5, TimeUnit.SECONDS)) {
                    throw new IOException("Timed out waiting to publish test catalog record");
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted while publishing test catalog record", exception);
            }
            super.add(record);
        }
    }

    private static final class UnusedMaintenanceService implements BackupMaintenanceService {
        @Override
        public CompletionStage<List<BackupRecord>> listBackups(Optional<WorldId> worldId) {
            return CompletableFuture.failedFuture(new UnsupportedOperationException());
        }

        @Override
        public CompletionStage<Optional<BackupRecord>> findBackup(BackupId backupId) {
            return CompletableFuture.failedFuture(new UnsupportedOperationException());
        }

        @Override
        public CompletionStage<RestoreBackupResult> restoreBackup(
                RestoreBackupRequest request,
                ProgressListener progressListener) {
            return CompletableFuture.failedFuture(new UnsupportedOperationException());
        }

        @Override
        public CompletionStage<DeletePreparation> prepareDelete(BackupId backupId) {
            return CompletableFuture.failedFuture(new UnsupportedOperationException());
        }

        @Override
        public CompletionStage<BackupResult> deleteBackup(
                DeleteBackupRequest request,
                ProgressListener progressListener) {
            return CompletableFuture.failedFuture(new UnsupportedOperationException());
        }

        @Override
        public CompletionStage<BackupResult> verifyBackup(
                BackupId backupId,
                ProgressListener progressListener) {
            return CompletableFuture.failedFuture(new UnsupportedOperationException());
        }

        @Override
        public CompletionStage<BackupResult> syncBackup(
                BackupId backupId,
                ProgressListener progressListener) {
            return CompletableFuture.failedFuture(new UnsupportedOperationException());
        }

        @Override
        public CompletionStage<List<DestinationHealth>> health(Optional<WorldId> worldId) {
            return CompletableFuture.failedFuture(new UnsupportedOperationException());
        }
    }
}
