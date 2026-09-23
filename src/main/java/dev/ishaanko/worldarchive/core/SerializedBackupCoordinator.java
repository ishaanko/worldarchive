package dev.ishaanko.worldarchive.core;

import dev.ishaanko.worldarchive.catalog.BackupCatalog;
import dev.ishaanko.worldarchive.model.BackupId;
import dev.ishaanko.worldarchive.model.BackupManifest;
import dev.ishaanko.worldarchive.model.BackupOperation;
import dev.ishaanko.worldarchive.model.BackupRecord;
import dev.ishaanko.worldarchive.model.BackupResult;
import dev.ishaanko.worldarchive.model.DestinationResult;
import dev.ishaanko.worldarchive.model.DestinationType;
import dev.ishaanko.worldarchive.model.OperationId;
import dev.ishaanko.worldarchive.model.OperationPhase;
import dev.ishaanko.worldarchive.model.OperationProgress;
import dev.ishaanko.worldarchive.model.ProgressListener;
import dev.ishaanko.worldarchive.model.SensitiveDataRedactor;
import dev.ishaanko.worldarchive.model.WorldId;
import dev.ishaanko.worldarchive.support.AsyncTasks;
import dev.ishaanko.worldarchive.support.AsyncTasks.InterruptibleFuture;
import dev.ishaanko.worldarchive.support.Observers;
import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;

/**
 * Runs backups. A backup first copies its world into a private capture; only one capture of a
 * world runs at a time. The selected destinations then write that capture in parallel while the
 * world's operation gate keeps restore, delete and cleanup of the same world out. The backups of
 * one world write in the order their captures finished; different worlds proceed independently.
 * The result is recorded in the catalog.
 *
 * <p>Cancelling a returned stage stops a backup that captures or waits. While destinations write,
 * a cancel interrupts them; a destination that already made a copy keeps it and it is recorded.
 * Once recording starts, a cancel is refused and the backup completes normally.</p>
 */
public final class SerializedBackupCoordinator {
    private static final String WAITING_MESSAGE = "Waiting for the previous backup of this world";

    private final BackupCatalog catalog;

    private final FileSystemBackupCaptureFactory captureFactory;

    private final FileWorldInventoryStore inventoryStore;

    private final BackupDestinationSelector destinationSelector;

    private final WorldOperationGate captureMutex;

    private final WorldOperationGate operationGate;

    private final ExecutorService executor;

    private final Clock clock;

    /** Never removed, so every operation of a world finds the same lane. */
    private final ConcurrentMap<WorldId, Lane> lanes = new ConcurrentHashMap<>();

    /**
     * Both gates may be shared with coordinators that replaced this one after a settings change:
     * {@code captureMutex} admits one capture per world, and {@code operationGate} is the per-world
     * gate that restore, delete and cleanup also enter.
     */
    public SerializedBackupCoordinator(
            BackupCatalog catalog,
            FileSystemBackupCaptureFactory captureFactory,
            FileWorldInventoryStore inventoryStore,
            BackupDestinationSelector destinationSelector,
            WorldOperationGate captureMutex,
            WorldOperationGate operationGate,
            ExecutorService executor,
            Clock clock) {
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.captureFactory = Objects.requireNonNull(captureFactory, "captureFactory");
        this.inventoryStore = Objects.requireNonNull(inventoryStore, "inventoryStore");
        this.destinationSelector = Objects.requireNonNull(destinationSelector, "destinationSelector");
        this.captureMutex = Objects.requireNonNull(captureMutex, "captureMutex");
        this.operationGate = Objects.requireNonNull(operationGate, "operationGate");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Captures the world on the calling thread, for a save hook that must copy the world right
     * after a save; {@code kind} says whether the game still runs it. Waits while another capture
     * of the same world runs. Interrupting the calling thread stops the capture.
     */
    public PreparedBackup prepareCapture(
            CreateBackupRequest request,
            CaptureKind kind,
            ProgressListener progressListener) throws IOException, InterruptedException {
        Progress progress = new Progress(
                OperationId.create(), request.worldId(), BackupId.create(), progressListener);
        Lane lane = lane(request.worldId());
        lane.register(progress.operationId);
        boolean handedOver = false;
        try {
            CapturedBackup captured = capture(request, kind, progress);
            PreparedBackup prepared = new PreparedBackup(
                    this, request, progress.operationId, captured, () -> lane.unregister(progress.operationId));
            handedOver = true;
            return prepared;
        } finally {
            if (!handedOver) {
                lane.unregister(progress.operationId);
            }
        }
    }

    /** Queues destination work for a capture from {@link #prepareCapture}; the backup owns it from here on. */
    public CompletionStage<BackupResult> createPreparedBackup(
            PreparedBackup prepared,
            ProgressListener progressListener) {
        Objects.requireNonNull(progressListener, "progressListener");
        CapturedBackup captured;
        try {
            if (prepared.owner() != this) {
                throw new IllegalArgumentException("The prepared capture belongs to another coordinator");
            }
            captured = prepared.claim();
        } catch (RuntimeException exception) {
            return CompletableFuture.failedFuture(exception);
        }
        CreateBackupRequest request = prepared.request();
        BackupId backupId = captured.capture().manifest().backupId();
        Progress progress = new Progress(prepared.operationId(), request.worldId(), backupId, progressListener);
        List<BackupBackend> backends;
        try {
            backends = selectDestinations(request);
        } catch (RuntimeException exception) {
            discard(captured, progress, exception);
            return CompletableFuture.failedFuture(exception);
        }
        if (backends.isEmpty()) {
            discard(captured, progress, null);
            return CompletableFuture.completedFuture(skipped(progress.backupId, request));
        }
        Operation operation = new Operation(request, progress, backends);
        enqueue(operation, captured);
        return operation.result;
    }

    /** Captures a world that no game runs on a worker, then queues destination work. */
    public CompletionStage<BackupResult> createBackup(
            CreateBackupRequest request,
            ProgressListener progressListener) {
        Progress progress = new Progress(
                OperationId.create(), request.worldId(), BackupId.create(), progressListener);
        List<BackupBackend> backends;
        try {
            backends = selectDestinations(request);
        } catch (RuntimeException exception) {
            return CompletableFuture.failedFuture(exception);
        }
        if (backends.isEmpty()) {
            return CompletableFuture.completedFuture(skipped(progress.backupId, request));
        }
        Operation operation = new Operation(request, progress, backends);
        operation.lane.register(progress.operationId);
        InterruptibleFuture<CapturedBackup> capturing = AsyncTasks.supplyInterruptible(
                executor, () -> capture(request, CaptureKind.CLOSED_WORLD, progress));
        operation.attach(capturing);
        capturing.whenComplete((captured, failure) -> {
            if (failure == null) {
                enqueue(operation, captured);
            } else {
                finish(operation, null, failure);
            }
        });
        return operation.result;
    }

    /** True while a backup of the world captures, waits, writes, or records. */
    public boolean isBusy(WorldId worldId) {
        Lane lane = lanes.get(Objects.requireNonNull(worldId, "worldId"));
        return lane != null && lane.busy();
    }

    /**
     * Completes once no backup of the world captures, waits, writes, or records. A cancelled
     * backup's stage completes before its workers have stopped; this stage completes after they
     * have, so a caller that must not change destination folders under a backup waits for it.
     */
    public CompletionStage<Void> whenIdle(WorldId worldId) {
        Lane lane = lanes.get(Objects.requireNonNull(worldId, "worldId"));
        return lane == null ? CompletableFuture.completedFuture(null) : lane.whenIdle();
    }

    private Lane lane(WorldId worldId) {
        return lanes.computeIfAbsent(worldId, ignored -> new Lane());
    }

    private CapturedBackup capture(CreateBackupRequest request, CaptureKind kind, Progress progress)
            throws IOException, InterruptedException {
        progress.report(OperationPhase.PREPARING, 0, 0, "Preparing private world capture");
        try (WorldOperationGate.Permit ignored = captureMutex.enter(request.worldId())) {
            Optional<WorldInventory> previous = previousInventory(request.worldId(), progress);
            return captureFactory.capture(
                    request,
                    kind,
                    progress.backupId,
                    clock.instant(),
                    previous,
                    (completed, total) -> progress.report(
                            OperationPhase.READING, completed, total, "Capturing world files"));
        }
    }

    /** The last recorded inventory; without one, every file counts as changed. */
    private Optional<WorldInventory> previousInventory(WorldId worldId, Progress progress) {
        try {
            return inventoryStore.load(worldId);
        } catch (IOException exception) {
            progress.report(OperationPhase.PREPARING, 0, 0,
                    "Change inventory is unavailable; capturing a full baseline");
            return Optional.empty();
        }
    }

    /**
     * Starts the operation's destination work, or queues it behind the backup of the same world
     * that is writing. The waiting report comes first, so it never follows the writing report.
     */
    private void enqueue(Operation operation, CapturedBackup captured) {
        Lane lane = operation.lane;
        if (lane.hasWriter()) {
            operation.progress.report(OperationPhase.QUEUED, 0, 0, WAITING_MESSAGE);
        }
        boolean cancelled;
        boolean writeNow = false;
        synchronized (lane) {
            operation.capture = captured;
            cancelled = operation.cancelRequested;
            if (!cancelled && lane.writer == null) {
                lane.writer = operation;
                operation.stage = Stage.WRITING;
                writeNow = true;
            } else if (!cancelled) {
                lane.waiting.addLast(operation);
                operation.stage = Stage.WAITING;
            }
        }
        if (cancelled) {
            finish(operation, null, cancellation());
        } else if (writeNow) {
            startWriting(operation);
        }
    }

    private void startWriting(Operation operation) {
        InterruptibleFuture<WorldOperationGate.Permit> entering = AsyncTasks.supplyInterruptible(
                executor, () -> operationGate.enter(operation.request.worldId()));
        operation.attach(entering);
        entering.whenComplete((permit, failure) -> {
            if (failure == null) {
                writeDestinations(operation, permit);
            } else {
                finish(operation, null, failure);
            }
        });
    }

    /** Starts one worker per destination; each can be interrupted and still reports the copy it made. */
    private void writeDestinations(Operation operation, WorldOperationGate.Permit permit) {
        try {
            BackupCapture capture = operation.admit(permit);
            if (capture == null) {
                finish(operation, null, cancellation());
                return;
            }
            long total = capture.manifest().sourceByteCount();
            operation.progress.report(
                    OperationPhase.WRITING, 0, total, DestinationProgressAggregator.WRITING_MESSAGE);
            DestinationProgressAggregator aggregator = new DestinationProgressAggregator(
                    operation.backends.stream().map(BackupBackend::destinationType).toList(), total);
            List<CompletableFuture<DestinationResult>> outcomes = new ArrayList<>(operation.backends.size());
            for (BackupBackend backend : operation.backends) {
                DestinationType destination = backend.destinationType();
                InterruptibleFuture<DestinationResult> write = AsyncTasks.supplyInterruptible(
                        executor,
                        () -> backend.createBackup(capture, progress -> forwardDestinationProgress(
                                operation, aggregator, destination, progress)));
                operation.addDestination(write);
                outcomes.add(write.handle(
                        (result, failure) -> destinationOutcome(destination, result, failure)));
            }
            CompletableFuture.allOf(outcomes.toArray(CompletableFuture[]::new))
                    .whenComplete((ignored, failure) -> commitOnExecutor(operation, outcomes));
        } catch (Throwable throwable) {
            // A continuation must always release the permit and the lane.
            finish(operation, null, throwable);
        }
    }

    /**
     * Records the outcomes on a coordinator worker, never on the destination worker that
     * finished last: a cancelled write can leave that thread interrupted, and file locks fail on
     * an interrupted thread, so the record of a finished copy would be lost.
     */
    private void commitOnExecutor(Operation operation, List<CompletableFuture<DestinationResult>> outcomes) {
        try {
            executor.execute(() -> commit(operation, outcomes));
        } catch (RejectedExecutionException exception) {
            // The game is closing. Recording here beats losing a finished copy.
            commit(operation, outcomes);
        }
    }

    /** Records every destination that made a copy, even when the backup was cancelled meanwhile. */
    private void commit(Operation operation, List<CompletableFuture<DestinationResult>> outcomes) {
        try {
            boolean cancelled = operation.beginCommit();
            BackupCapture capture = operation.capture();
            BackupManifest manifest = capture.manifest();
            List<DestinationResult> destinations = outcomes.stream().map(CompletableFuture::join).toList();
            BackupResult result = new BackupResult(
                    manifest.backupId(), operation.request.worldId(), destinations, completionTime(manifest));
            if (destinations.stream().anyMatch(DestinationResult::isDurable)) {
                record(operation, capture, result);
            }
            if (cancelled) {
                finish(operation, null, cancellation());
            } else {
                finish(operation, result, null);
            }
        } catch (Throwable throwable) {
            // A continuation must always release the permit and the lane.
            finish(operation, null, throwable);
        }
    }

    private void record(Operation operation, BackupCapture capture, BackupResult result) throws IOException {
        long bytes = capture.manifest().sourceByteCount();
        operation.progress.report(OperationPhase.PUBLISHING, bytes, bytes, "Recording backup metadata");
        try {
            catalog.add(new BackupRecord(capture.manifest(), result));
        } catch (IOException exception) {
            throw new IOException("The backup was saved, but WorldArchive could not add it to its backup list ("
                    + exception.getMessage() + "). It appears in the list after the game restarts.", exception);
        }
        try {
            inventoryStore.save(operation.request.worldId(), capture.inventory());
        } catch (IOException exception) {
            // The backup is recorded; the next backup of this world counts every file as changed.
            operation.progress.report(OperationPhase.PUBLISHING, bytes, bytes,
                    "Backup complete; change inventory could not be updated");
        }
    }

    /**
     * Ends the operation once: deletes its capture, releases its permit and its place in the lane,
     * reports and completes its stage, and starts the next backup of the world.
     */
    private void finish(Operation operation, BackupResult result, Throwable failure) {
        Lane lane = operation.lane;
        CapturedBackup capture;
        WorldOperationGate.Permit permit;
        Operation next = null;
        List<CompletableFuture<Void>> idle;
        synchronized (lane) {
            if (operation.stage == Stage.DONE) {
                return;
            }
            operation.stage = Stage.DONE;
            capture = operation.capture;
            operation.capture = null;
            permit = operation.permit;
            operation.permit = null;
            idle = lane.remove(operation.progress.operationId);
            lane.waiting.remove(operation);
            if (lane.writer == operation) {
                next = lane.waiting.pollFirst();
                lane.writer = next;
                if (next != null) {
                    next.stage = Stage.WRITING;
                }
            }
        }
        release(capture, failure);
        if (permit != null) {
            permit.close();
        }
        if (failure == null) {
            reportCompleted(operation, result);
            operation.result.complete(result);
        } else {
            // A cancelled operation is silenced, so this reports real failures only.
            operation.progress.report(OperationPhase.FAILED, 0, 0, "Backup could not be completed");
            operation.result.completeExceptionally(failure);
        }
        // Waiters learn that the world is idle only once its capture is deleted and its permit released.
        idle.forEach(waiter -> waiter.complete(null));
        if (next != null) {
            startWriting(next);
        }
    }

    /**
     * Deletes the capture. When the backup succeeded, a folder that could not be deleted does not
     * turn it into a failure; the next game start removes it.
     */
    private static void release(CapturedBackup capture, Throwable failure) {
        if (capture == null) {
            return;
        }
        try {
            capture.close();
        } catch (IOException exception) {
            if (failure != null) {
                failure.addSuppressed(exception);
            }
        }
    }

    /** Ends a claimed capture that never became an operation. */
    private void discard(CapturedBackup captured, Progress progress, Throwable failure) {
        release(captured, failure);
        lane(progress.worldId).unregister(progress.operationId);
    }

    private static void reportCompleted(Operation operation, BackupResult result) {
        Progress progress = operation.progress;
        switch (result.status()) {
            case SUCCESS -> progress.report(OperationPhase.COMPLETE, 1, 1, "Backup complete");
            case PARTIAL_SUCCESS ->
                    progress.report(OperationPhase.COMPLETE, 1, 1, "Backup complete with warnings");
            case SKIPPED -> progress.report(OperationPhase.COMPLETE, 1, 1, "Backup skipped");
            case FAILED -> progress.report(OperationPhase.FAILED, 0, 0, "Backup destinations failed");
            default -> throw new IllegalStateException("Unknown backup status: " + result.status());
        }
    }

    /**
     * Reports one destination's progress. While more than one destination writes, their progress
     * is combined into one stream, so a destination that finishes first cannot make the whole
     * backup look complete. A single destination's own phases are passed on, except that its end
     * is still part of writing: recording follows.
     */
    private static void forwardDestinationProgress(
            Operation operation,
            DestinationProgressAggregator aggregator,
            DestinationType destination,
            OperationProgress progress) {
        if (aggregator.aggregates()) {
            operation.progress.report(
                    OperationPhase.WRITING,
                    aggregator.accept(destination, progress),
                    aggregator.totalUnits(),
                    DestinationProgressAggregator.WRITING_MESSAGE);
            return;
        }
        boolean destinationDone = progress.phase() == OperationPhase.COMPLETE
                || progress.phase() == OperationPhase.FAILED;
        operation.progress.report(
                destinationDone ? OperationPhase.WRITING : progress.phase(),
                progress.completedUnits(),
                progress.totalUnits(),
                SensitiveDataRedactor.redact(progress.message()));
    }

    private List<BackupBackend> selectDestinations(CreateBackupRequest request) {
        List<BackupBackend> backends = List.copyOf(destinationSelector.select(request));
        if (backends.stream().map(BackupBackend::destinationType).distinct().count() != backends.size()) {
            throw new IllegalStateException("Each destination may be selected only once");
        }
        return backends;
    }

    private BackupResult skipped(BackupId backupId, CreateBackupRequest request) {
        return new BackupResult(backupId, request.worldId(), List.of(), clock.instant());
    }

    private Instant completionTime(BackupManifest manifest) {
        Instant now = clock.instant();
        return now.isBefore(manifest.createdAt()) ? manifest.createdAt() : now;
    }

    private static DestinationResult destinationOutcome(
            DestinationType destination,
            DestinationResult result,
            Throwable failure) {
        if (failure instanceof CancellationException || failure instanceof InterruptedException) {
            return DestinationResult.failed(destination, "Cancelled before this destination finished");
        }
        if (failure != null || result == null || result.destination() != destination) {
            return DestinationResult.failed(
                    destination, "Destination failed before a trustworthy result was available");
        }
        return result;
    }

    private static CancellationException cancellation() {
        return new CancellationException("Backup was cancelled");
    }

    /** Where an operation is; every stage but DONE counts as busy. */
    private enum Stage {
        CAPTURING,
        WAITING,
        WRITING,
        COMMITTING,
        DONE
    }

    /**
     * One world's unfinished operations: the backup whose destinations write and the captured
     * backups that wait behind it. Its monitor also guards the mutable fields of every operation
     * of the world, so a cancel and a stage change never interleave.
     */
    private static final class Lane {
        private final Set<OperationId> unfinished = new HashSet<>();

        private final ArrayDeque<Operation> waiting = new ArrayDeque<>();

        private final List<CompletableFuture<Void>> idleWaiters = new ArrayList<>();

        private Operation writer;

        private synchronized void register(OperationId operationId) {
            unfinished.add(operationId);
        }

        private void unregister(OperationId operationId) {
            List<CompletableFuture<Void>> idle;
            synchronized (this) {
                idle = remove(operationId);
            }
            idle.forEach(waiter -> waiter.complete(null));
        }

        /** Removes an operation; returns the idle waiters to complete, outside the lock, when it was the last. */
        private List<CompletableFuture<Void>> remove(OperationId operationId) {
            unfinished.remove(operationId);
            if (!unfinished.isEmpty() || idleWaiters.isEmpty()) {
                return List.of();
            }
            List<CompletableFuture<Void>> idle = List.copyOf(idleWaiters);
            idleWaiters.clear();
            return idle;
        }

        private synchronized CompletionStage<Void> whenIdle() {
            if (unfinished.isEmpty()) {
                return CompletableFuture.completedFuture(null);
            }
            CompletableFuture<Void> waiter = new CompletableFuture<>();
            idleWaiters.add(waiter);
            return waiter;
        }

        private synchronized boolean busy() {
            return !unfinished.isEmpty();
        }

        private synchronized boolean hasWriter() {
            return writer != null;
        }
    }

    /** Sends one operation's progress to its listener; after a cancel it reports nothing more. */
    private static final class Progress {
        private final OperationId operationId;

        private final WorldId worldId;

        private final BackupId backupId;

        private final ProgressListener listener;

        private volatile boolean silenced;

        private Progress(
                OperationId operationId,
                WorldId worldId,
                BackupId backupId,
                ProgressListener listener) {
            this.operationId = operationId;
            this.worldId = worldId;
            this.backupId = backupId;
            this.listener = Objects.requireNonNull(listener, "listener");
        }

        /** A listener or an invalid event never affects the backup. */
        private void report(OperationPhase phase, long completed, long total, String message) {
            if (!silenced) {
                Observers.safely(() -> listener.onProgress(new OperationProgress(
                        operationId,
                        worldId,
                        Optional.of(backupId),
                        BackupOperation.CREATE,
                        phase,
                        completed,
                        total,
                        message)));
            }
        }
    }

    /** One backup from its capture to its catalog record. Mutable fields are guarded by the lane. */
    private final class Operation {
        private final CreateBackupRequest request;

        private final Progress progress;

        private final List<BackupBackend> backends;

        private final Lane lane;

        private final OperationFuture result = new OperationFuture(this);

        private final List<InterruptibleFuture<DestinationResult>> destinations = new ArrayList<>();

        private Stage stage = Stage.CAPTURING;

        private boolean cancelRequested;

        private InterruptibleFuture<?> worker;

        private CapturedBackup capture;

        private WorldOperationGate.Permit permit;

        private Operation(CreateBackupRequest request, Progress progress, List<BackupBackend> backends) {
            this.request = request;
            this.progress = progress;
            this.backends = backends;
            this.lane = lane(request.worldId());
        }

        /** Accepts a cancel unless the operation is recording or done; from here on it reports nothing. */
        private boolean acceptCancel() {
            synchronized (lane) {
                if (stage == Stage.COMMITTING || stage == Stage.DONE) {
                    return false;
                }
                cancelRequested = true;
                progress.silenced = true;
                return true;
            }
        }

        /**
         * Interrupts the running work. The worker or commit path then ends the operation; only a
         * waiting operation, which no path would reach again, is taken out of the queue and ended
         * on a worker, because ending it deletes its capture and a cancel may come from the render
         * thread.
         */
        private void stopWork() {
            List<InterruptibleFuture<?>> running = new ArrayList<>();
            boolean dequeued;
            synchronized (lane) {
                dequeued = stage == Stage.WAITING && lane.waiting.remove(this);
                if (worker != null) {
                    running.add(worker);
                }
                running.addAll(destinations);
            }
            running.forEach(task -> task.stop(true));
            if (dequeued) {
                try {
                    executor.execute(() -> finish(this, null, cancellation()));
                } catch (RejectedExecutionException exception) {
                    finish(this, null, cancellation());
                }
            }
        }

        private void attach(InterruptibleFuture<?> task) {
            boolean stop;
            synchronized (lane) {
                worker = task;
                stop = cancelRequested;
            }
            if (stop) {
                task.stop(true);
            }
        }

        private void addDestination(InterruptibleFuture<DestinationResult> write) {
            boolean stop;
            synchronized (lane) {
                destinations.add(write);
                stop = cancelRequested;
            }
            if (stop) {
                write.stop(true);
            }
        }

        /** Takes the world permit; null when the backup was cancelled while it waited for it. */
        private BackupCapture admit(WorldOperationGate.Permit worldPermit) {
            synchronized (lane) {
                permit = worldPermit;
                return cancelRequested ? null : capture.capture();
            }
        }

        /** The point of no return; true when a cancel arrived before it. */
        private boolean beginCommit() {
            synchronized (lane) {
                stage = Stage.COMMITTING;
                return cancelRequested;
            }
        }

        private BackupCapture capture() {
            synchronized (lane) {
                return capture.capture();
            }
        }
    }

    /** The stage handed to callers; cancelling it cancels the operation. */
    private static final class OperationFuture extends CompletableFuture<BackupResult> {
        private final Operation operation;

        private OperationFuture(Operation operation) {
            this.operation = operation;
        }

        /**
         * Refused once the backup is recording. The stage completes as cancelled before the work is
         * stopped, so an interrupted worker cannot complete it with its own failure first. Running
         * work is interrupted whatever the flag says.
         */
        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            if (!operation.acceptCancel()) {
                return false;
            }
            boolean cancelled = super.cancel(mayInterruptIfRunning);
            operation.stopWork();
            return cancelled;
        }
    }
}
