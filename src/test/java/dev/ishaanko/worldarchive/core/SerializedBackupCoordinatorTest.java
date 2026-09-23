package dev.ishaanko.worldarchive.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.ishaanko.worldarchive.catalog.FileBackupCatalog;
import dev.ishaanko.worldarchive.model.BackupId;
import dev.ishaanko.worldarchive.model.BackupOperation;
import dev.ishaanko.worldarchive.model.BackupResult;
import dev.ishaanko.worldarchive.model.BackupStatus;
import dev.ishaanko.worldarchive.model.BackupTrigger;
import dev.ishaanko.worldarchive.model.DestinationResult;
import dev.ishaanko.worldarchive.model.DestinationStatus;
import dev.ishaanko.worldarchive.model.DestinationType;
import dev.ishaanko.worldarchive.model.OperationId;
import dev.ishaanko.worldarchive.model.OperationPhase;
import dev.ishaanko.worldarchive.model.OperationProgress;
import dev.ishaanko.worldarchive.model.ProgressListener;
import dev.ishaanko.worldarchive.model.WorldId;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The coordinator on a real catalog, inventory store and capture of a world folder; only destinations are fakes. */
final class SerializedBackupCoordinatorTest {
    private static final Instant NOW = Instant.parse("2026-07-17T12:00:00Z");

    @TempDir
    Path temporaryDirectory;

    private final ExecutorService executor = Executors.newThreadPerTaskExecutor(Thread.ofVirtual().factory());

    private final WorldId worldId = WorldId.create();

    private FileBackupCatalog catalog;

    private FileWorldInventoryStore inventories;

    private Path world;

    @BeforeEach
    void createWorld() throws IOException {
        catalog = new FileBackupCatalog(temporaryDirectory.resolve("catalog.json"));
        inventories = new FileWorldInventoryStore(temporaryDirectory.resolve("inventories"));
        world = createWorld("world");
    }

    @AfterEach
    void stopWorkers() throws InterruptedException {
        executor.shutdownNow();
        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
    }

    @Test
    void writesEveryDestinationFromOneCaptureAndRecordsTheBackupOnce() throws Exception {
        List<Path> captures = new CopyOnWriteArrayList<>();
        FakeBackend git = FakeBackend.writing(DestinationType.GIT, (capture, listener) -> {
            captures.add(capture.worldDirectory());
            return DestinationResult.success(DestinationType.GIT, "ref");
        });
        FakeBackend zip = FakeBackend.writing(DestinationType.ZIP, (capture, listener) -> {
            captures.add(capture.worldDirectory());
            throw new IllegalStateException("untrustworthy failure");
        });
        SerializedBackupCoordinator coordinator = coordinator(git, zip);

        BackupResult first = await(coordinator.createBackup(request(BackupTrigger.MANUAL), ProgressListener.NO_OP));
        BackupResult second = await(coordinator.createBackup(request(BackupTrigger.MANUAL), ProgressListener.NO_OP));

        assertEquals(BackupStatus.PARTIAL_SUCCESS, first.status());
        assertEquals(DestinationStatus.FAILED, destination(first, DestinationType.ZIP).status());
        assertEquals(2, catalog.list(worldId).size());
        assertEquals(2, changedFiles(first));
        assertEquals(0, changedFiles(second));
        assertEquals(captures.get(0), captures.get(1));
        assertEquals(List.of(), worldCopies());
    }

    @Test
    void aDamagedInventoryFileCountsEveryFileAsChangedAndIsReplaced() throws Exception {
        Path inventory = temporaryDirectory.resolve("inventories").resolve(worldId + ".json");
        Files.createDirectories(inventory.getParent());
        Files.writeString(inventory, "not json", StandardCharsets.UTF_8);
        SerializedBackupCoordinator coordinator = coordinator(FakeBackend.success(DestinationType.ZIP));

        BackupResult manual = await(coordinator.createBackup(request(BackupTrigger.MANUAL), ProgressListener.NO_OP));
        PreparedBackup exitCapture = coordinator.prepareCapture(
                request(BackupTrigger.WORLD_EXIT), CaptureKind.CLOSED_WORLD, ProgressListener.NO_OP);
        BackupResult exit = await(coordinator.createPreparedBackup(exitCapture, ProgressListener.NO_OP));

        assertEquals(BackupStatus.SUCCESS, manual.status());
        assertEquals(BackupStatus.SUCCESS, exit.status());
        assertEquals(2, changedFiles(manual));
        assertEquals(0, changedFiles(exit));
    }

    @Test
    void aPreparedCaptureIsCompleteWhenItReturnsAndOnlyItsDestinationWorkIsQueued() throws Exception {
        FakeBackend zip = FakeBackend.success(DestinationType.ZIP);
        SerializedBackupCoordinator coordinator = coordinator(zip);

        PreparedBackup prepared = coordinator.prepareCapture(
                request(BackupTrigger.WORLD_EXIT), CaptureKind.CLOSED_WORLD, ProgressListener.NO_OP);
        assertEquals(2, worldCopies().size());
        assertEquals(0, zip.calls.get());
        assertTrue(coordinator.isBusy(worldId));
        BackupResult result = await(coordinator.createPreparedBackup(prepared, ProgressListener.NO_OP));
        PreparedBackup abandoned = coordinator.prepareCapture(
                request(BackupTrigger.WORLD_EXIT), CaptureKind.CLOSED_WORLD, ProgressListener.NO_OP);
        abandoned.close();

        assertEquals(BackupStatus.SUCCESS, result.status());
        assertEquals(1, zip.calls.get());
        assertFalse(coordinator.isBusy(worldId));
        assertEquals(List.of(), worldCopies());
        assertEquals(BackupStatus.SUCCESS, await(coordinator.createBackup(
                request(BackupTrigger.MANUAL), ProgressListener.NO_OP)).status());
    }

    @Test
    void backupsOfOneWorldWriteInOrderWhileAnotherWorldProceeds() throws Exception {
        WorldId otherWorldId = WorldId.create();
        Path otherWorld = createWorld("other-world");
        Map<String, CompletableFuture<DestinationResult>> releases = new ConcurrentHashMap<>();
        FakeBackend zip = FakeBackend.awaiting(DestinationType.ZIP, capture -> releases.computeIfAbsent(
                capture.manifest().label().orElseThrow(), ignored -> new CompletableFuture<>()));
        SerializedBackupCoordinator coordinator = coordinator(zip);
        List<OperationProgress> secondProgress = Collections.synchronizedList(new ArrayList<>());

        CompletableFuture<BackupResult> first = start(coordinator, request(worldId, world, "first"), ProgressListener.NO_OP);
        awaitCondition(() -> releases.containsKey("first"));
        CompletableFuture<BackupResult> second = start(coordinator, request(worldId, world, "second"), secondProgress::add);
        CompletableFuture<BackupResult> other = start(
                coordinator, request(otherWorldId, otherWorld, "other"), ProgressListener.NO_OP);
        awaitCondition(() -> releases.containsKey("other")
                && secondProgress.stream().anyMatch(progress -> progress.phase() == OperationPhase.QUEUED));
        releases.get("other").complete(DestinationResult.success(DestinationType.ZIP, "other"));

        assertEquals(BackupStatus.SUCCESS, other.get(5, TimeUnit.SECONDS).status());
        assertFalse(releases.containsKey("second"));
        releases.get("first").complete(DestinationResult.success(DestinationType.ZIP, "first"));
        assertEquals(BackupStatus.SUCCESS, first.get(5, TimeUnit.SECONDS).status());
        awaitCondition(() -> releases.containsKey("second"));
        releases.get("second").complete(DestinationResult.success(DestinationType.ZIP, "second"));
        assertEquals(BackupStatus.SUCCESS, second.get(5, TimeUnit.SECONDS).status());
    }

    @Test
    void destinationsWaitWhileMaintenanceHoldsTheWorld() throws Exception {
        LockingWorldOperationGate shared = new LockingWorldOperationGate();
        WorldOperationGate.Permit maintenance = shared.enter(worldId);
        CountDownLatch waitingForWorld = new CountDownLatch(1);
        WorldOperationGate operationGate = id -> {
            waitingForWorld.countDown();
            return shared.enter(id);
        };
        FakeBackend zip = FakeBackend.success(DestinationType.ZIP);
        SerializedBackupCoordinator coordinator = coordinator(SourceCaptureObserver.NONE, operationGate, zip);

        CompletableFuture<BackupResult> backup = start(coordinator, request(BackupTrigger.MANUAL), ProgressListener.NO_OP);
        assertTrue(waitingForWorld.await(5, TimeUnit.SECONDS));
        assertEquals(0, zip.calls.get());
        maintenance.close();

        assertEquals(BackupStatus.SUCCESS, backup.get(5, TimeUnit.SECONDS).status());
    }

    @Test
    void aPreparedCaptureDoesNotWaitForAnotherBackupsDestinationsAndQueuesBehindThem() throws Exception {
        Map<BackupTrigger, CompletableFuture<DestinationResult>> releases = new ConcurrentHashMap<>();
        FakeBackend zip = FakeBackend.awaiting(DestinationType.ZIP, capture -> releases.computeIfAbsent(
                capture.manifest().trigger(), ignored -> new CompletableFuture<>()));
        SerializedBackupCoordinator coordinator = coordinator(zip);
        CompletableFuture<BackupResult> manual = start(coordinator, request(BackupTrigger.MANUAL), ProgressListener.NO_OP);
        awaitCondition(() -> releases.containsKey(BackupTrigger.MANUAL));
        List<OperationProgress> exitProgress = Collections.synchronizedList(new ArrayList<>());

        PreparedBackup prepared = coordinator.prepareCapture(
                request(BackupTrigger.WORLD_EXIT), CaptureKind.CLOSED_WORLD, ProgressListener.NO_OP);
        CompletableFuture<BackupResult> exit = coordinator.createPreparedBackup(prepared, exitProgress::add)
                .toCompletableFuture();

        assertEquals(OperationPhase.QUEUED, exitProgress.getLast().phase());
        assertFalse(releases.containsKey(BackupTrigger.WORLD_EXIT));
        releases.get(BackupTrigger.MANUAL).complete(DestinationResult.success(DestinationType.ZIP, "manual"));
        assertEquals(BackupStatus.SUCCESS, manual.get(5, TimeUnit.SECONDS).status());
        awaitCondition(() -> releases.containsKey(BackupTrigger.WORLD_EXIT));
        releases.get(BackupTrigger.WORLD_EXIT).complete(DestinationResult.success(DestinationType.ZIP, "exit"));
        assertEquals(BackupStatus.SUCCESS, exit.get(5, TimeUnit.SECONDS).status());
    }

    @Test
    void cancellingWhileCapturingPublishesNothingAndDeletesTheCapture() throws Exception {
        CountDownLatch copying = new CountDownLatch(1);
        SourceCaptureObserver blockCopies = new SourceCaptureObserver() {
            @Override
            public void beforeFileCopy(Path relativePath) throws InterruptedException {
                copying.countDown();
                new CountDownLatch(1).await();
            }
        };
        FakeBackend zip = FakeBackend.success(DestinationType.ZIP);
        SerializedBackupCoordinator coordinator = coordinator(blockCopies, new LockingWorldOperationGate(), zip);
        CompletableFuture<BackupResult> backup = start(coordinator, request(BackupTrigger.MANUAL), ProgressListener.NO_OP);
        assertTrue(copying.await(5, TimeUnit.SECONDS));

        assertTrue(backup.cancel(true));

        awaitCondition(() -> !coordinator.isBusy(worldId));
        assertTrue(backup.isCancelled());
        assertEquals(0, zip.calls.get());
        assertEquals(List.of(), catalog.list(worldId));
        assertEquals(Optional.empty(), inventories.load(worldId));
        assertEquals(List.of(), worldCopies());
    }

    @Test
    void cancellingAPreparedBackupQueuedBehindAnotherDeletesItsCapture() throws Exception {
        CompletableFuture<DestinationResult> firstWrite = new CompletableFuture<>();
        AtomicInteger writes = new AtomicInteger();
        FakeBackend zip = FakeBackend.awaiting(DestinationType.ZIP, capture -> writes.incrementAndGet() == 1
                ? firstWrite
                : CompletableFuture.completedFuture(DestinationResult.success(DestinationType.ZIP, "later")));
        SerializedBackupCoordinator coordinator = coordinator(zip);
        CompletableFuture<BackupResult> first = start(coordinator, request(BackupTrigger.MANUAL), ProgressListener.NO_OP);
        awaitCondition(() -> writes.get() == 1);
        PreparedBackup prepared = coordinator.prepareCapture(
                request(BackupTrigger.WORLD_EXIT), CaptureKind.CLOSED_WORLD, ProgressListener.NO_OP);
        CompletableFuture<BackupResult> queued = coordinator.createPreparedBackup(prepared, ProgressListener.NO_OP)
                .toCompletableFuture();

        assertTrue(queued.cancel(true));
        firstWrite.complete(DestinationResult.success(DestinationType.ZIP, "first"));

        assertEquals(BackupStatus.SUCCESS, first.get(5, TimeUnit.SECONDS).status());
        assertTrue(queued.isCancelled());
        assertEquals(BackupStatus.SUCCESS, await(coordinator.createBackup(
                request(BackupTrigger.MANUAL), ProgressListener.NO_OP)).status());
        assertEquals(2, writes.get());
        assertEquals(2, catalog.list(worldId).size());
        assertEquals(List.of(), worldCopies());
    }

    @Test
    void cancellingWhileWritingRecordsTheDestinationsThatFinished() throws Exception {
        FakeBackend zip = FakeBackend.success(DestinationType.ZIP);
        FakeBackend git = FakeBackend.awaiting(DestinationType.GIT, capture -> new CompletableFuture<>());
        SerializedBackupCoordinator coordinator = coordinator(zip, git);
        CompletableFuture<BackupResult> backup = start(coordinator, request(BackupTrigger.MANUAL), ProgressListener.NO_OP);
        awaitCondition(() -> git.calls.get() == 1 && zip.calls.get() == 1);

        assertTrue(backup.cancel(true));
        awaitCondition(() -> !coordinator.isBusy(worldId));

        assertTrue(backup.isCancelled());
        assertEquals(1, catalog.list(worldId).size());
        BackupResult recorded = catalog.list(worldId).getFirst().result();
        assertEquals(BackupStatus.PARTIAL_SUCCESS, recorded.status());
        assertEquals(DestinationStatus.SUCCESS, destination(recorded, DestinationType.ZIP).status());
        assertEquals(
                "Cancelled before this destination finished",
                destination(recorded, DestinationType.GIT).message().orElseThrow());
        assertTrue(inventories.load(worldId).isPresent());
    }

    /**
     * A cancel completes the backup's stage at once, while a destination may still be stopping.
     * whenIdle waits for it, so the runtime keeps the backup folders in place until then.
     */
    @Test
    void whenIdleWaitsForADestinationThatIsStillStoppingAfterACancel() throws Exception {
        CountDownLatch interrupted = new CountDownLatch(1);
        CountDownLatch stopped = new CountDownLatch(1);
        FakeBackend git = FakeBackend.writing(DestinationType.GIT, (capture, listener) -> {
            try {
                Thread.sleep(Long.MAX_VALUE);
            } catch (InterruptedException stop) {
                interrupted.countDown();
                stopped.await();
            }
            return DestinationResult.failed(DestinationType.GIT, "Stopped");
        });
        SerializedBackupCoordinator coordinator = coordinator(git);
        CompletableFuture<BackupResult> backup = start(coordinator, request(BackupTrigger.MANUAL), ProgressListener.NO_OP);
        awaitCondition(() -> git.calls.get() == 1);

        assertTrue(backup.cancel(true));
        assertTrue(interrupted.await(5, TimeUnit.SECONDS));
        CompletableFuture<Void> idle = coordinator.whenIdle(worldId).toCompletableFuture();

        assertTrue(backup.isCancelled());
        assertFalse(idle.isDone());
        stopped.countDown();
        idle.get(5, TimeUnit.SECONDS);
        assertFalse(coordinator.isBusy(worldId));
    }

    @Test
    void cancellingKeepsAGitSnapshotWhoseSyncWasInterrupted() throws Exception {
        CountDownLatch pushing = new CountDownLatch(1);
        FakeBackend git = FakeBackend.writing(DestinationType.GIT, (capture, listener) -> {
            pushing.countDown();
            try {
                Thread.sleep(Long.MAX_VALUE);
            } catch (InterruptedException exception) {
                // Like the Git backend: the local snapshot exists, only the push was cut short, and
                // the interrupt stays set, so the catalog write must not run on this thread.
                Thread.currentThread().interrupt();
                return DestinationResult.pendingSync(
                        DestinationType.GIT, "snapshot", "Remote synchronization was cancelled");
            }
            return DestinationResult.success(DestinationType.GIT, "snapshot");
        });
        SerializedBackupCoordinator coordinator = coordinator(git);
        CompletableFuture<BackupResult> backup = start(coordinator, request(BackupTrigger.MANUAL), ProgressListener.NO_OP);
        assertTrue(pushing.await(5, TimeUnit.SECONDS));

        assertTrue(backup.cancel(true));
        awaitCondition(() -> !coordinator.isBusy(worldId));

        assertEquals(1, catalog.list(worldId).size());
        assertEquals(
                DestinationStatus.PENDING_SYNC,
                destination(catalog.list(worldId).getFirst().result(), DestinationType.GIT).status());
    }

    @Test
    void cancellingWhileRecordingIsRefusedAndTheBackupCompletes() throws Exception {
        CountDownLatch recording = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ProgressListener holdWhileRecording = progress -> {
            if (progress.phase() == OperationPhase.PUBLISHING) {
                recording.countDown();
                try {
                    release.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                }
            }
        };
        SerializedBackupCoordinator coordinator = coordinator(FakeBackend.success(DestinationType.ZIP));
        CompletableFuture<BackupResult> backup = start(coordinator, request(BackupTrigger.MANUAL), holdWhileRecording);
        assertTrue(recording.await(5, TimeUnit.SECONDS));

        assertFalse(backup.cancel(true));
        release.countDown();

        assertEquals(BackupStatus.SUCCESS, backup.get(5, TimeUnit.SECONDS).status());
        assertEquals(1, catalog.list(worldId).size());
    }

    @Test
    void aWorldThatKeepsChangingFailsWithCaptureChangedAndPublishesNothing() throws Exception {
        AtomicInteger writes = new AtomicInteger();
        SourceCaptureObserver rewriteAfterEachCopy = new SourceCaptureObserver() {
            @Override
            public void afterFileCopy(Path relativePath) throws IOException {
                if (relativePath.toString().equals("level.dat")) {
                    Files.writeString(world.resolve("level.dat"), "changed-" + writes.incrementAndGet());
                }
            }
        };
        FakeBackend zip = FakeBackend.success(DestinationType.ZIP);
        SerializedBackupCoordinator coordinator = coordinator(
                rewriteAfterEachCopy, new LockingWorldOperationGate(), zip);

        ExecutionException failure = assertThrows(ExecutionException.class, () -> await(
                coordinator.createBackup(request(BackupTrigger.MANUAL), ProgressListener.NO_OP)));

        assertInstanceOf(CaptureChangedException.class, failure.getCause());
        assertEquals(0, zip.calls.get());
        assertEquals(List.of(), catalog.list(worldId));
        assertEquals(Optional.empty(), inventories.load(worldId));
        assertEquals(List.of(), worldCopies());
    }

    @Test
    void combinesTheProgressOfBothDestinationsIntoOneStream() throws Exception {
        Map<DestinationType, ProgressListener> listeners = new ConcurrentHashMap<>();
        Map<DestinationType, CompletableFuture<DestinationResult>> releases = new ConcurrentHashMap<>();
        List<OperationProgress> reported = Collections.synchronizedList(new ArrayList<>());
        SerializedBackupCoordinator coordinator = coordinator(
                pendingBackend(DestinationType.GIT, listeners, releases),
                pendingBackend(DestinationType.ZIP, listeners, releases));

        CompletableFuture<BackupResult> backup = start(coordinator, request(BackupTrigger.MANUAL), reported::add);
        awaitCondition(() -> listeners.size() == 2);
        listeners.get(DestinationType.ZIP).onProgress(backendProgress(OperationPhase.WRITING, 900, 1_000, "Writing ZIP"));
        listeners.get(DestinationType.GIT).onProgress(backendProgress(OperationPhase.READING, 3, 6, "Pushing"));
        listeners.get(DestinationType.ZIP).onProgress(backendProgress(OperationPhase.COMPLETE, 1_000, 1_000, "ZIP done"));

        List<OperationProgress> writing = List.copyOf(reported).stream()
                .dropWhile(progress -> progress.phase() != OperationPhase.WRITING)
                .toList();
        assertEquals(List.of(OperationPhase.WRITING), writing.stream().map(OperationProgress::phase).distinct().toList());
        assertEquals(
                List.of(DestinationProgressAggregator.WRITING_MESSAGE),
                writing.stream().map(OperationProgress::message).distinct().toList());
        assertTrue(writing.getLast().completedUnits() > 0, "The combined progress must advance");
        for (int index = 1; index < writing.size(); index++) {
            assertTrue(writing.get(index).completedUnits() >= writing.get(index - 1).completedUnits());
        }
        releases.get(DestinationType.ZIP).complete(DestinationResult.success(DestinationType.ZIP, "zip"));
        releases.get(DestinationType.GIT).complete(DestinationResult.success(DestinationType.GIT, "ref"));
        assertEquals(BackupStatus.SUCCESS, backup.get(5, TimeUnit.SECONDS).status());
    }

    @Test
    void oneDestinationKeepsItsOwnProgressUntilTheBackupIsRecorded() throws Exception {
        Map<DestinationType, ProgressListener> listeners = new ConcurrentHashMap<>();
        Map<DestinationType, CompletableFuture<DestinationResult>> releases = new ConcurrentHashMap<>();
        List<OperationProgress> reported = Collections.synchronizedList(new ArrayList<>());
        SerializedBackupCoordinator coordinator = coordinator(pendingBackend(DestinationType.GIT, listeners, releases));

        CompletableFuture<BackupResult> backup = start(coordinator, request(BackupTrigger.MANUAL), reported::add);
        awaitCondition(() -> listeners.size() == 1);
        ProgressListener git = listeners.get(DestinationType.GIT);
        git.onProgress(backendProgress(OperationPhase.VERIFYING, 3, 6, "Pushing to https://user:secret@example.com/world.git"));
        OperationProgress forwarded = reported.getLast();
        git.onProgress(backendProgress(OperationPhase.COMPLETE, 6, 6, "Git snapshot complete"));
        OperationProgress destinationDone = reported.getLast();
        releases.get(DestinationType.GIT).complete(DestinationResult.success(DestinationType.GIT, "ref"));

        assertEquals(BackupStatus.SUCCESS, backup.get(5, TimeUnit.SECONDS).status());
        assertEquals(OperationPhase.VERIFYING, forwarded.phase());
        assertEquals(3, forwarded.completedUnits());
        assertEquals("Pushing to https://[REDACTED]@example.com/world.git", forwarded.message());
        assertEquals(OperationPhase.WRITING, destinationDone.phase());
        assertEquals(OperationPhase.COMPLETE, reported.getLast().phase());
    }

    private SerializedBackupCoordinator coordinator(BackupBackend... backends) {
        return coordinator(SourceCaptureObserver.NONE, new LockingWorldOperationGate(), backends);
    }

    private SerializedBackupCoordinator coordinator(
            SourceCaptureObserver observer,
            WorldOperationGate operationGate,
            BackupBackend... backends) {
        return new SerializedBackupCoordinator(
                catalog,
                new FileSystemBackupCaptureFactory(temporaryDirectory.resolve("capture-temp"), Optional.empty(), observer),
                inventories,
                request -> List.of(backends),
                new LockingWorldOperationGate(),
                operationGate,
                executor,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    /** Publishes its progress listener and waits for the test to release its result. */
    private static FakeBackend pendingBackend(
            DestinationType destination,
            Map<DestinationType, ProgressListener> listeners,
            Map<DestinationType, CompletableFuture<DestinationResult>> releases) {
        return FakeBackend.writing(destination, (capture, listener) -> {
            CompletableFuture<DestinationResult> release = new CompletableFuture<>();
            releases.put(destination, release);
            listeners.put(destination, listener);
            try {
                return release.get();
            } catch (ExecutionException exception) {
                throw new IllegalStateException(exception);
            }
        });
    }

    private Path createWorld(String name) throws IOException {
        Path folder = Files.createDirectory(temporaryDirectory.resolve(name));
        Files.writeString(folder.resolve("level.dat"), "level", StandardCharsets.UTF_8);
        Files.write(Files.createDirectory(folder.resolve("region")).resolve("r.0.0.mca"), new byte[1_024]);
        return folder;
    }

    private CreateBackupRequest request(BackupTrigger trigger) {
        return new CreateBackupRequest(worldId, world, "World", Optional.empty(), trigger);
    }

    private static CreateBackupRequest request(WorldId id, Path folder, String label) {
        return new CreateBackupRequest(id, folder, "World", Optional.of(label), BackupTrigger.MANUAL);
    }

    private static CompletableFuture<BackupResult> start(
            SerializedBackupCoordinator coordinator,
            CreateBackupRequest request,
            ProgressListener listener) {
        return coordinator.createBackup(request, listener).toCompletableFuture();
    }

    private long changedFiles(BackupResult result) throws IOException {
        return catalog.find(result.backupId()).orElseThrow().manifest().changedFileCount();
    }

    /** Every copied world file still under the capture folder. */
    private List<Path> worldCopies() throws IOException {
        Path captures = temporaryDirectory.resolve("capture-temp");
        if (!Files.exists(captures)) {
            return List.of();
        }
        try (Stream<Path> files = Files.walk(captures)) {
            return files.filter(file -> file.endsWith("level.dat") || file.toString().endsWith(".mca")).toList();
        }
    }

    private static OperationProgress backendProgress(OperationPhase phase, long completed, long total, String message) {
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

    private static DestinationResult destination(BackupResult result, DestinationType type) {
        return result.destinations().stream()
                .filter(destination -> destination.destination() == type)
                .findFirst()
                .orElseThrow();
    }

    private static <T> T await(CompletionStage<T> stage) throws Exception {
        return stage.toCompletableFuture().get(5, TimeUnit.SECONDS);
    }

    private static void awaitCondition(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() >= deadline) {
                throw new AssertionError("Condition was not satisfied before timeout");
            }
            Thread.sleep(10);
        }
    }
}
