package dev.ishaankot.worldarchive.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.ishaankot.worldarchive.catalog.BackupCatalog;
import dev.ishaankot.worldarchive.model.BackupId;
import dev.ishaankot.worldarchive.model.BackupManifest;
import dev.ishaankot.worldarchive.model.BackupRecord;
import dev.ishaankot.worldarchive.model.BackupResult;
import dev.ishaankot.worldarchive.model.BackupStatus;
import dev.ishaankot.worldarchive.model.BackupTrigger;
import dev.ishaankot.worldarchive.model.DestinationHealth;
import dev.ishaankot.worldarchive.model.DestinationResult;
import dev.ishaankot.worldarchive.model.DestinationStatus;
import dev.ishaankot.worldarchive.model.DestinationType;
import dev.ishaankot.worldarchive.model.WorldId;
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
        FileSystemBackupCaptureFactory captures = new FileSystemBackupCaptureFactory(
                temporaryDirectory.resolve("captures-mutation"),
                new SourceCaptureObserver() {
                    @Override
                    public void afterFileCopy(Path relativePath) throws IOException {
                        Files.writeString(world.resolve(relativePath), "after", StandardCharsets.UTF_8);
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
    void preparedCaptureThatWinsGateCannotDeadlockNormalCreateLane() throws Exception {
        LockingWorldOperationGate underlyingGate = new LockingWorldOperationGate();
        WorldId worldId = WorldId.create();
        WorldOperationGate.Permit external = underlyingGate.enter(worldId);
        AtomicInteger gateWaiters = new AtomicInteger();
        WorldOperationGate observedGate = requestedWorld -> {
            gateWaiters.incrementAndGet();
            return underlyingGate.enter(requestedWorld);
        };
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
                observedGate);
        ExecutorService serverExecutor = Executors.newSingleThreadExecutor();
        try {
            Future<PreparedBackup> preparation = serverExecutor.submit(() -> coordinator.prepareCapture(
                    request(worldId, "world-a", BackupTrigger.WORLD_EXIT, Optional.empty()),
                    CaptureProgressListener.NO_OP));
            await(() -> gateWaiters.get() == 1);
            CompletionStage<BackupResult> normal = coordinator.createBackup(
                    request(worldId, "world-a", BackupTrigger.MANUAL, Optional.empty()),
                    ProgressListener.NO_OP);
            await(() -> gateWaiters.get() == 2);

            external.close();
            PreparedBackup prepared = preparation.get(5, TimeUnit.SECONDS);
            assertEquals(OperationPhase.PREPARING,
                    coordinator.currentOperation(worldId).orElseThrow().phase());
            assertFalse(releases.containsKey(BackupTrigger.MANUAL));

            CompletionStage<BackupResult> exit = coordinator.createPreparedBackup(
                    prepared,
                    ProgressListener.NO_OP);
            await(() -> releases.containsKey(BackupTrigger.WORLD_EXIT));
            releases.get(BackupTrigger.WORLD_EXIT).complete(DestinationResult.success(
                    DestinationType.ZIP,
                    "exit"));
            assertEquals(BackupStatus.SUCCESS, exit.toCompletableFuture().get(5, TimeUnit.SECONDS).status());

            await(() -> releases.containsKey(BackupTrigger.MANUAL));
            releases.get(BackupTrigger.MANUAL).complete(DestinationResult.success(
                    DestinationType.ZIP,
                    "manual"));
            assertEquals(BackupStatus.SUCCESS, normal.toCompletableFuture().get(5, TimeUnit.SECONDS).status());
        } finally {
            external.close();
            serverExecutor.shutdownNow();
            assertTrue(serverExecutor.awaitTermination(5, TimeUnit.SECONDS));
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

        private final Function<BackupCapture, CompletionStage<DestinationResult>> result;

        private final AtomicInteger calls = new AtomicInteger();

        private FakeBackend(
                DestinationType destination,
                Function<BackupCapture, CompletionStage<DestinationResult>> result) {
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
            return result.apply(capture);
        }
    }

    private static final class InMemoryInventoryStore implements WorldInventoryStore {
        private final Map<WorldId, WorldInventory> values = new ConcurrentHashMap<>();

        @Override
        public Optional<WorldInventory> load(WorldId worldId) {
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
