package dev.ishaankot.worldarchive.core;

import dev.ishaankot.worldarchive.catalog.BackupCatalog;
import dev.ishaankot.worldarchive.model.BackupId;
import dev.ishaankot.worldarchive.model.BackupManifest;
import dev.ishaankot.worldarchive.model.BackupRecord;
import dev.ishaankot.worldarchive.model.BackupResult;
import dev.ishaankot.worldarchive.model.BackupTrigger;
import dev.ishaankot.worldarchive.model.DestinationHealth;
import dev.ishaankot.worldarchive.model.DestinationResult;
import dev.ishaankot.worldarchive.model.DestinationStatus;
import dev.ishaankot.worldarchive.model.DestinationType;
import dev.ishaankot.worldarchive.model.SensitiveDataRedactor;
import dev.ishaankot.worldarchive.model.WorldId;
import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Create coordinator with FIFO per-world lanes and independent parallel destination attempts.
 *
 * <p>Capture is the only phase allowed through {@link BackupCaptureGate}; every backend starts
 * after that gate has returned and consumes the same private source tree.</p>
 */
public final class SerializedBackupCoordinator implements BackupCoordinator {
    private final BackupCatalog catalog;

    private final BackupCaptureFactory captureFactory;

    private final WorldInventoryStore inventoryStore;

    private final BackupDestinationSelector destinationSelector;

    private final BackupMaintenanceService maintenanceService;

    private final BackupCaptureGate captureGate;

    private final WorldOperationGate captureMutex;

    private final WorldOperationGate operationGate;

    private final ExecutorService coordinatorExecutor;

    private final Clock clock;

    private final ConcurrentMap<WorldId, WorldLane> lanes = new ConcurrentHashMap<>();

    private final ConcurrentMap<WorldId, OperationProgress> capturePreparations = new ConcurrentHashMap<>();

    public SerializedBackupCoordinator(
            BackupCatalog catalog,
            BackupCaptureFactory captureFactory,
            WorldInventoryStore inventoryStore,
            BackupDestinationSelector destinationSelector,
            BackupMaintenanceService maintenanceService,
            ExecutorService coordinatorExecutor,
            Clock clock) {
        this(
                catalog,
                captureFactory,
                inventoryStore,
                destinationSelector,
                maintenanceService,
                BackupCaptureGate.DIRECT,
                new LockingWorldOperationGate(),
                new LockingWorldOperationGate(),
                coordinatorExecutor,
                clock);
    }

    public SerializedBackupCoordinator(
            BackupCatalog catalog,
            BackupCaptureFactory captureFactory,
            WorldInventoryStore inventoryStore,
            BackupDestinationSelector destinationSelector,
            BackupMaintenanceService maintenanceService,
            BackupCaptureGate captureGate,
            WorldOperationGate operationGate,
            ExecutorService coordinatorExecutor,
            Clock clock) {
        this(
                catalog,
                captureFactory,
                inventoryStore,
                destinationSelector,
                maintenanceService,
                captureGate,
                new LockingWorldOperationGate(),
                operationGate,
                coordinatorExecutor,
                clock);
    }

    public SerializedBackupCoordinator(
            BackupCatalog catalog,
            BackupCaptureFactory captureFactory,
            WorldInventoryStore inventoryStore,
            BackupDestinationSelector destinationSelector,
            BackupMaintenanceService maintenanceService,
            BackupCaptureGate captureGate,
            WorldOperationGate captureMutex,
            WorldOperationGate operationGate,
            ExecutorService coordinatorExecutor,
            Clock clock) {
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.captureFactory = Objects.requireNonNull(captureFactory, "captureFactory");
        this.inventoryStore = Objects.requireNonNull(inventoryStore, "inventoryStore");
        this.destinationSelector = Objects.requireNonNull(destinationSelector, "destinationSelector");
        this.maintenanceService = Objects.requireNonNull(maintenanceService, "maintenanceService");
        this.captureGate = Objects.requireNonNull(captureGate, "captureGate");
        this.captureMutex = Objects.requireNonNull(captureMutex, "captureMutex");
        this.operationGate = Objects.requireNonNull(operationGate, "operationGate");
        this.coordinatorExecutor = Objects.requireNonNull(coordinatorExecutor, "coordinatorExecutor");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public PreparedBackup prepareCapture(
            CreateBackupRequest request,
            CaptureProgressListener progressListener)
            throws IOException, InterruptedException {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(progressListener, "progressListener");
        BackupId backupId = BackupId.create();
        OperationId operationId = OperationId.create();
        OperationProgress queued = preparationProgress(
                request,
                backupId,
                operationId,
                OperationPhase.QUEUED,
                0,
                0,
                "Waiting for another world capture");
        if (capturePreparations.putIfAbsent(request.worldId(), queued) != null) {
            throw new IllegalStateException("A synchronous capture is already pending for this world");
        }
        CapturedBackup captured = null;
        boolean transferred = false;
        try (WorldOperationGate.Permit ignored = captureMutex.enter(request.worldId())) {
            capturePreparations.put(request.worldId(), preparationProgress(
                    request,
                    backupId,
                    operationId,
                    OperationPhase.PREPARING,
                    0,
                    0,
                    "Preparing private world capture"));
            Optional<WorldInventory> previous = inventoryStore.load(request.worldId());
            captured = captureFactory.capture(
                    request,
                    backupId,
                    clock.instant(),
                    previous,
                    (completed, total) -> {
                        capturePreparations.put(request.worldId(), preparationProgress(
                                request,
                                backupId,
                                operationId,
                                OperationPhase.READING,
                                completed,
                                total,
                                "Capturing world files"));
                        progressListener.onProgress(completed, total);
                    });
            capturePreparations.put(request.worldId(), preparationProgress(
                    request,
                    backupId,
                    operationId,
                    OperationPhase.PREPARING,
                    1,
                    1,
                    "Private capture prepared"));
            PreparedBackup prepared = new PreparedBackup(
                    request,
                    captured,
                    previous.isPresent(),
                    operationId,
                    () -> capturePreparations.remove(request.worldId()));
            transferred = true;
            return prepared;
        } finally {
            if (!transferred) {
                capturePreparations.remove(request.worldId());
                if (captured != null) {
                    captured.close();
                }
            }
        }
    }

    @Override
    public CompletionStage<BackupResult> createPreparedBackup(
            PreparedBackup preparedBackup,
            ProgressListener progressListener) {
        Objects.requireNonNull(preparedBackup, "preparedBackup");
        Objects.requireNonNull(progressListener, "progressListener");
        PreparedBackup.Resources resources = preparedBackup.claim();
        CapturedBackup captured = resources.capturedBackup();
        try {
            validatePrepared(preparedBackup.request(), captured.capture().manifest());
            DestinationPlan plan = selectDestinations(preparedBackup.request());
            if (plan.backends().isEmpty()) {
                BackupResult skipped = BackupResult.aggregate(
                        captured.capture().manifest().backupId(),
                        preparedBackup.request().worldId(),
                        List.of(),
                        completionTime(captured.capture().manifest()));
                resources.close();
                return CompletableFuture.completedFuture(skipped);
            }
            CreateOperation operation = new CreateOperation(
                    preparedBackup.request(),
                    plan,
                    captured.capture().manifest().backupId(),
                    preparedBackup.operationId(),
                    false,
                    captured,
                    preparedBackup.previousInventoryPresent(),
                    progressListener);
            return enqueue(operation);
        } catch (IOException | RuntimeException exception) {
            closeAfterFailure(resources, exception);
            return CompletableFuture.failedFuture(exception);
        }
    }

    @Override
    public CompletionStage<BackupResult> createBackup(
            CreateBackupRequest request,
            ProgressListener progressListener) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(progressListener, "progressListener");
        final DestinationPlan plan;
        try {
            plan = selectDestinations(request);
        } catch (RuntimeException exception) {
            return CompletableFuture.failedFuture(exception);
        }
        BackupId backupId = BackupId.create();
        if (plan.backends().isEmpty()) {
            return CompletableFuture.completedFuture(BackupResult.aggregate(
                    backupId,
                    request.worldId(),
                    List.of(),
                    clock.instant()));
        }
        return enqueue(new CreateOperation(
                request,
                plan,
                backupId,
                OperationId.create(),
                true,
                null,
                false,
                progressListener));
    }

    @Override
    public Optional<OperationProgress> currentOperation(WorldId worldId) {
        Objects.requireNonNull(worldId, "worldId");
        OperationProgress preparation = capturePreparations.get(worldId);
        if (preparation != null && preparation.phase() != OperationPhase.QUEUED) {
            return Optional.of(preparation);
        }
        WorldLane lane = lanes.get(worldId);
        if (lane != null) {
            synchronized (lane) {
                if (lane.active != null) {
                    return Optional.ofNullable(lane.active.progress.get());
                }
            }
        }
        return Optional.ofNullable(preparation);
    }

    @Override
    public CompletionStage<List<BackupRecord>> listBackups(Optional<WorldId> worldId) {
        return maintenanceService.listBackups(worldId);
    }

    @Override
    public CompletionStage<Optional<BackupRecord>> findBackup(BackupId backupId) {
        return maintenanceService.findBackup(backupId);
    }

    @Override
    public CompletionStage<RestoreBackupResult> restoreBackup(
            RestoreBackupRequest request,
            ProgressListener progressListener) {
        return maintenanceService.restoreBackup(request, progressListener);
    }

    @Override
    public CompletionStage<DeletePreparation> prepareDelete(BackupId backupId) {
        return maintenanceService.prepareDelete(backupId);
    }

    @Override
    public CompletionStage<BackupResult> deleteBackup(
            DeleteBackupRequest request,
            ProgressListener progressListener) {
        return maintenanceService.deleteBackup(request, progressListener);
    }

    @Override
    public CompletionStage<BackupResult> verifyBackup(
            BackupId backupId,
            ProgressListener progressListener) {
        return maintenanceService.verifyBackup(backupId, progressListener);
    }

    @Override
    public CompletionStage<BackupResult> syncBackup(
            BackupId backupId,
            ProgressListener progressListener) {
        return maintenanceService.syncBackup(backupId, progressListener);
    }

    @Override
    public CompletionStage<List<DestinationHealth>> health(Optional<WorldId> worldId) {
        return maintenanceService.health(worldId);
    }

    private CompletionStage<BackupResult> enqueue(CreateOperation operation) {
        WorldLane lane = lanes.computeIfAbsent(operation.request.worldId(), ignored -> new WorldLane());
        operation.lane = lane;
        CreateOperation start = null;
        synchronized (lane) {
            if (operation.coalescible) {
                CreateOperation compatible = compatibleOperation(lane, operation);
                if (compatible != null) {
                    compatible.listeners.addAll(operation.listeners);
                    OperationProgress current = compatible.progress.get();
                    if (current != null) {
                        safeNotify(operation.listeners.getFirst(), current);
                    }
                    return compatible.result;
                }
            }
            lane.queue.addLast(operation);
            report(operation, OperationPhase.QUEUED, 0, 0, "Backup queued");
            if (lane.active == null) {
                start = activateNext(lane);
            }
        }
        if (start != null) {
            startOperation(start);
        }
        return operation.result;
    }

    private static CreateOperation compatibleOperation(
            WorldLane lane,
            CreateOperation requested) {
        if (lane.active != null && lane.active.isCompatibleWith(requested)) {
            return lane.active;
        }
        CreateOperation queued = lane.queue.peekLast();
        if (queued != null && queued.isCompatibleWith(requested)) {
            return queued;
        }
        return null;
    }

    private static CreateOperation activateNext(WorldLane lane) {
        CreateOperation next = lane.queue.pollFirst();
        lane.active = next;
        return next;
    }

    private void startOperation(CreateOperation operation) {
        report(operation, OperationPhase.PREPARING, 0, 0, "Preparing private world capture");
        try {
            Future<?> worker = coordinatorExecutor.submit(() -> {
                operation.workerStarted.set(true);
                execute(operation);
            });
            operation.worker.set(worker);
            if (operation.cancelled.get()) {
                boolean preventedStart = worker.cancel(operation.interruptRequested.get());
                if (preventedStart && !operation.workerStarted.get()) {
                    finish(operation, null, new CancellationException("Backup was cancelled"));
                }
            }
        } catch (RejectedExecutionException exception) {
            finish(operation, null, exception);
        }
    }

    private void execute(CreateOperation operation) {
        try {
            if (operation.cancelled.get()) {
                throw new CancellationException("Backup was cancelled");
            }
            if (operation.permit.get() == null) {
                WorldOperationGate.Permit permit = operationGate.enter(operation.request.worldId());
                if (!operation.permit.compareAndSet(null, permit)) {
                    permit.close();
                    throw new IllegalStateException("Backup operation already owns a world permit");
                }
            }
            CapturedBackup captured = operation.capture.get();
            if (captured == null) {
                try (WorldOperationGate.Permit ignored = captureMutex.enter(
                        operation.request.worldId())) {
                    Optional<WorldInventory> previous = inventoryStore.load(
                            operation.request.worldId());
                    operation.previousInventoryPresent = previous.isPresent();
                    captured = captureGate.capture(() -> captureFactory.capture(
                            operation.request,
                            operation.backupId,
                            clock.instant(),
                            previous,
                            (completed, total) -> report(
                                    operation,
                                    OperationPhase.READING,
                                    completed,
                                    total,
                                    "Capturing world files")));
                }
                if (!operation.capture.compareAndSet(null, captured)) {
                    captured.close();
                    throw new IllegalStateException("Backup operation already owns a private capture");
                }
            }
            if (operation.cancelled.get()) {
                throw new CancellationException("Backup was cancelled");
            }
            BackupManifest manifest = captured.capture().manifest();
            if (operation.request.trigger() == BackupTrigger.SCHEDULED
                    && operation.previousInventoryPresent
                    && manifest.changedFileCount() == 0) {
                List<DestinationResult> skipped = operation.plan.backends().stream()
                        .map(backend -> DestinationResult.skipped(
                                backend.destinationType(),
                                "World is unchanged"))
                        .toList();
                finish(
                        operation,
                        BackupResult.aggregate(
                                operation.backupId,
                                operation.request.worldId(),
                                skipped,
                                completionTime(manifest)),
                        null);
                return;
            }
            startDestinations(operation, captured);
        } catch (Throwable throwable) {
            finish(operation, null, throwable);
        }
    }

    private void startDestinations(
            CreateOperation operation,
            CapturedBackup captured) {
        synchronized (operation) {
            if (operation.cancellationState.compareAndSet(
                    CancellationState.CANCELLABLE,
                    CancellationState.COMMITTING)) {
                // Destination publication is now the operation's point of no return.
            } else if (operation.cancellationState.get() == CancellationState.CANCELLATION_REQUESTED) {
                finish(operation, null, new CancellationException("Backup was cancelled"));
                return;
            } else {
                return;
            }
        }
        if (operation.terminal.get()) {
            return;
        }
        report(
                operation,
                OperationPhase.WRITING,
                0,
                captured.capture().manifest().sourceByteCount(),
                "Writing backup destinations");
        List<CompletableFuture<DestinationResult>> outcomes = new ArrayList<>(operation.plan.backends().size());
        for (BackupBackend backend : operation.plan.backends()) {
            CompletableFuture<DestinationResult> source;
            try {
                CompletionStage<DestinationResult> stage = Objects.requireNonNull(
                        backend.createBackup(
                                captured.capture(),
                                progress -> forwardDestinationProgress(operation, progress)),
                        "Backup backend returned a null stage");
                source = stage.toCompletableFuture();
            } catch (Throwable throwable) {
                source = CompletableFuture.failedFuture(throwable);
            }
            operation.destinationTasks.add(source);
            DestinationType expectedDestination = backend.destinationType();
            outcomes.add(source.handle((result, throwable) -> destinationOutcome(
                    expectedDestination,
                    result,
                    throwable)));
        }
        CompletableFuture.allOf(outcomes.toArray(CompletableFuture[]::new))
                .whenComplete((ignored, throwable) -> finalizeDestinations(operation, outcomes));
    }

    private void finalizeDestinations(
            CreateOperation operation,
            List<CompletableFuture<DestinationResult>> outcomes) {
        if (operation.cancelled.get()) {
            finish(operation, null, new CancellationException("Backup was cancelled"));
            return;
        }
        try {
            List<DestinationResult> destinations = outcomes.stream()
                    .map(CompletableFuture::join)
                    .toList();
            BackupManifest manifest = Objects.requireNonNull(operation.capture.get(), "capture")
                    .capture()
                    .manifest();
            BackupResult result = BackupResult.aggregate(
                    operation.backupId,
                    operation.request.worldId(),
                    destinations,
                    completionTime(manifest));
            if (hasDurableDestination(destinations)) {
                report(
                        operation,
                        OperationPhase.PUBLISHING,
                        manifest.sourceByteCount(),
                        manifest.sourceByteCount(),
                        "Recording backup metadata");
                catalog.add(new BackupRecord(manifest, result));
                try {
                    inventoryStore.save(
                            operation.request.worldId(),
                            operation.capture.get().inventory());
                } catch (IOException exception) {
                    report(
                            operation,
                            OperationPhase.PUBLISHING,
                            manifest.sourceByteCount(),
                            manifest.sourceByteCount(),
                            "Backup complete; change inventory could not be updated");
                }
            }
            finish(operation, result, null);
        } catch (Throwable throwable) {
            finish(operation, null, throwable);
        }
    }

    private void cancel(CreateOperation operation, boolean mayInterrupt) {
        operation.cancelled.set(true);
        operation.interruptRequested.compareAndSet(false, mayInterrupt);
        WorldLane lane = operation.lane;
        boolean queued = false;
        if (lane != null) {
            synchronized (lane) {
                if (lane.active != operation) {
                    queued = lane.queue.remove(operation);
                    if (lane.active == null && lane.queue.isEmpty()) {
                        lanes.remove(operation.request.worldId(), lane);
                    }
                }
            }
        }
        if (queued) {
            finish(operation, null, new CancellationException("Queued backup was cancelled"));
            return;
        }
        Future<?> worker = operation.worker.get();
        if (worker != null) {
            boolean preventedStart = worker.cancel(mayInterrupt);
            if (preventedStart && !operation.workerStarted.get()) {
                finish(operation, null, new CancellationException("Backup was cancelled"));
                return;
            }
        }
        for (CompletableFuture<?> destination : operation.destinationTasks) {
            destination.cancel(mayInterrupt);
        }
    }

    private void finish(
            CreateOperation operation,
            BackupResult result,
            Throwable failure) {
        if (!operation.terminal.compareAndSet(false, true)) {
            return;
        }
        Throwable terminalFailure = failure;
        CapturedBackup captured = operation.capture.getAndSet(null);
        if (captured != null) {
            try {
                captured.close();
            } catch (IOException exception) {
                if (terminalFailure != null) {
                    terminalFailure.addSuppressed(exception);
                }
            }
        }
        WorldOperationGate.Permit permit = operation.permit.getAndSet(null);
        if (permit != null) {
            try {
                permit.close();
            } catch (RuntimeException exception) {
                if (terminalFailure == null) {
                    terminalFailure = exception;
                } else {
                    terminalFailure.addSuppressed(exception);
                }
            }
        }

        if (terminalFailure == null && result.status() != dev.ishaankot.worldarchive.model.BackupStatus.FAILED) {
            report(operation, OperationPhase.COMPLETE, 1, 1, completionMessage(result));
        } else if (terminalFailure == null) {
            report(operation, OperationPhase.FAILED, 0, 0, completionMessage(result));
        } else if (!operation.cancelled.get()) {
            report(operation, OperationPhase.FAILED, 0, 0, "Backup could not be completed");
        }

        CreateOperation next = null;
        WorldLane lane = operation.lane;
        if (lane != null) {
            synchronized (lane) {
                if (lane.active == operation) {
                    lane.active = null;
                    if (!lane.queue.isEmpty()) {
                        next = activateNext(lane);
                    } else {
                        lanes.remove(operation.request.worldId(), lane);
                    }
                }
            }
        }

        if (!operation.result.isCancelled()) {
            if (terminalFailure == null) {
                operation.result.complete(result);
            } else {
                operation.result.completeExceptionally(terminalFailure);
            }
        }
        if (next != null) {
            startOperation(next);
        }
    }

    private void forwardDestinationProgress(
            CreateOperation operation,
            OperationProgress progress) {
        if (operation.cancelled.get()) {
            return;
        }
        report(
                operation,
                progress.phase(),
                progress.completedUnits(),
                progress.totalUnits(),
                SensitiveDataRedactor.redact(progress.message()));
    }

    private void report(
            CreateOperation operation,
            OperationPhase phase,
            long completed,
            long total,
            String message) {
        OperationProgress progress = new OperationProgress(
                operation.operationId,
                operation.request.worldId(),
                Optional.of(operation.backupId),
                BackupOperation.CREATE,
                phase,
                completed,
                total,
                message);
        operation.progress.set(progress);
        for (ProgressListener listener : operation.listeners) {
            safeNotify(listener, progress);
        }
    }

    private static OperationProgress preparationProgress(
            CreateBackupRequest request,
            BackupId backupId,
            OperationId operationId,
            OperationPhase phase,
            long completed,
            long total,
            String message) {
        return new OperationProgress(
                operationId,
                request.worldId(),
                Optional.of(backupId),
                BackupOperation.CREATE,
                phase,
                completed,
                total,
                message);
    }

    private DestinationPlan selectDestinations(CreateBackupRequest request) {
        return new DestinationPlan(destinationSelector.select(request));
    }

    private Instant completionTime(BackupManifest manifest) {
        Instant now = clock.instant();
        return now.isBefore(manifest.createdAt()) ? manifest.createdAt() : now;
    }

    private static void validatePrepared(
            CreateBackupRequest request,
            BackupManifest manifest) {
        if (!manifest.worldId().equals(request.worldId())
                || !manifest.worldName().equals(request.worldName())
                || !manifest.label().equals(request.label())
                || manifest.trigger() != request.trigger()) {
            throw new IllegalArgumentException("Prepared capture does not match its create request");
        }
    }

    private static DestinationResult destinationOutcome(
            DestinationType expectedDestination,
            DestinationResult result,
            Throwable throwable) {
        if (throwable != null || result == null || result.destination() != expectedDestination) {
            return DestinationResult.failed(
                    expectedDestination,
                    "Destination failed before a trustworthy result was available");
        }
        return result;
    }

    private static boolean hasDurableDestination(List<DestinationResult> destinations) {
        return destinations.stream().anyMatch(result -> result.status() == DestinationStatus.SUCCESS
                || result.status() == DestinationStatus.PENDING_SYNC);
    }

    private static String completionMessage(BackupResult result) {
        return switch (result.status()) {
            case SUCCESS -> "Backup complete";
            case PARTIAL_SUCCESS -> "Backup complete with warnings";
            case FAILED -> "Backup destinations failed";
            case SKIPPED -> "Backup skipped";
        };
    }

    private static void safeNotify(
            ProgressListener listener,
            OperationProgress progress) {
        try {
            listener.onProgress(progress);
        } catch (RuntimeException ignored) {
            // Operation correctness never depends on an observer.
        }
    }

    private static void closeAfterFailure(
            PreparedBackup.Resources resources,
            Exception failure) {
        try {
            resources.close();
        } catch (IOException closeFailure) {
            failure.addSuppressed(closeFailure);
        }
    }

    private final class CreateOperation {
        private final CreateBackupRequest request;

        private final DestinationPlan plan;

        private final BackupId backupId;

        private final OperationId operationId;

        private final boolean coalescible;

        private final CopyOnWriteArrayList<ProgressListener> listeners = new CopyOnWriteArrayList<>();

        private final OperationFuture result;

        private final AtomicReference<CapturedBackup> capture;

        private final AtomicReference<WorldOperationGate.Permit> permit;

        private final AtomicReference<OperationProgress> progress = new AtomicReference<>();

        private final AtomicReference<Future<?>> worker = new AtomicReference<>();

        private final CopyOnWriteArrayList<CompletableFuture<?>> destinationTasks = new CopyOnWriteArrayList<>();

        private final AtomicBoolean workerStarted = new AtomicBoolean();

        private final AtomicBoolean cancelled = new AtomicBoolean();

        private final AtomicReference<CancellationState> cancellationState =
                new AtomicReference<>(CancellationState.CANCELLABLE);

        private final AtomicBoolean interruptRequested = new AtomicBoolean();

        private final AtomicBoolean terminal = new AtomicBoolean();

        private volatile boolean previousInventoryPresent;

        private volatile WorldLane lane;

        private CreateOperation(
                CreateBackupRequest request,
                DestinationPlan plan,
                BackupId backupId,
                OperationId operationId,
                boolean coalescible,
                CapturedBackup capture,
                boolean previousInventoryPresent,
                ProgressListener listener) {
            this.request = request;
            this.plan = plan;
            this.backupId = backupId;
            this.operationId = operationId;
            this.coalescible = coalescible;
            this.capture = new AtomicReference<>(capture);
            this.permit = new AtomicReference<>();
            this.previousInventoryPresent = previousInventoryPresent;
            this.listeners.add(listener);
            this.result = new OperationFuture(this);
        }

        private boolean isCompatibleWith(CreateOperation other) {
            return coalescible
                    && other.coalescible
                    && request.equals(other.request)
                    && plan.equals(other.plan);
        }
    }

    private final class OperationFuture extends CompletableFuture<BackupResult> {
        private final CreateOperation operation;

        private OperationFuture(CreateOperation operation) {
            this.operation = operation;
        }

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            synchronized (operation) {
                if (!operation.cancellationState.compareAndSet(
                        CancellationState.CANCELLABLE,
                        CancellationState.CANCELLATION_REQUESTED)) {
                    return false;
                }
                boolean cancelled = super.cancel(mayInterruptIfRunning);
                if (cancelled) {
                    SerializedBackupCoordinator.this.cancel(operation, mayInterruptIfRunning);
                } else {
                    operation.cancellationState.compareAndSet(
                            CancellationState.CANCELLATION_REQUESTED,
                            CancellationState.CANCELLABLE);
                }
                return cancelled;
            }
        }
    }

    private static final class WorldLane {
        private final Deque<CreateOperation> queue = new ArrayDeque<>();

        private CreateOperation active;
    }

    private record DestinationPlan(List<BackupBackend> backends) {
        private DestinationPlan {
            backends = List.copyOf(Objects.requireNonNull(backends, "backends"));
            Set<DestinationType> destinations = EnumSet.noneOf(DestinationType.class);
            for (BackupBackend backend : backends) {
                Objects.requireNonNull(backend, "backend");
                if (!destinations.add(backend.destinationType())) {
                    throw new IllegalArgumentException("Each destination may be selected only once");
                }
            }
        }
    }

    private enum CancellationState {
        CANCELLABLE,
        CANCELLATION_REQUESTED,
        COMMITTING
    }
}
