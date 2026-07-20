package dev.ishaankot.worldarchive.runtime;

import dev.ishaankot.worldarchive.config.WorldConfig;
import dev.ishaankot.worldarchive.core.CaptureProgressListener;
import dev.ishaankot.worldarchive.core.CreateBackupRequest;
import dev.ishaankot.worldarchive.core.NoCatchUpSchedule;
import dev.ishaankot.worldarchive.core.PreparedBackup;
import dev.ishaankot.worldarchive.core.ProgressListener;
import dev.ishaankot.worldarchive.model.BackupResult;
import dev.ishaankot.worldarchive.model.BackupTrigger;
import dev.ishaankot.worldarchive.model.WorldId;
import dev.ishaankot.worldarchive.ui.BackupWorldContext;
import dev.ishaankot.worldarchive.ui.BackupWorldSelection;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.RejectedExecutionException;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

/** Owns integrated-server save gates, scheduling, and live-world lifecycle state. */
final class RuntimeLifecycle {
    private final WorldArchiveRuntime runtime;

    private final Clock clock;

    private final Object lock = new Object();

    private final LiveBackupSaveGate<MinecraftServer, PendingLiveBackup> saveGate =
            new LiveBackupSaveGate<>();

    private IntegratedServer activeServer;

    private BackupWorldContext liveWorld;

    private long liveWorldRevision;

    private IntegratedServer stoppingServer;

    private boolean clientStopping;

    private ScheduleState scheduleState;

    RuntimeLifecycle(WorldArchiveRuntime runtime, Clock clock) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    void register() {
        ServerLifecycleEvents.SERVER_STARTED.register(this::serverStarted);
        ServerLifecycleEvents.SERVER_STOPPING.register(this::serverStopping);
        ServerLifecycleEvents.AFTER_SAVE.register(this::afterSave);
        ServerLifecycleEvents.SERVER_STOPPED.register(this::serverStopped);
        ClientTickEvents.END_CLIENT_TICK.register(this::clientTick);
        ClientLifecycleEvents.CLIENT_STOPPING.register(this::clientStopping);
    }

    void ensureLiveWorldResolution() {
        IntegratedServer server;
        long revision;
        synchronized (lock) {
            if (runtime.isClosed() || activeServer == null || liveWorld != null) {
                return;
            }
            server = activeServer;
            revision = ++liveWorldRevision;
        }
        final BackupWorldSelection selection;
        try {
            selection = selectionFor(server);
        } catch (RuntimeException exception) {
            runtime.logFailure("Integrated world path could not be resolved", exception);
            return;
        }
        runtime.submit(() -> runtime.resolveWorldBlocking(selection))
                .whenComplete((resolved, throwable) -> completeWorldResolution(
                        server,
                        revision,
                        resolved,
                        throwable));
    }

    void reconcile(RuntimeState state) {
        synchronized (lock) {
            for (WorldConfig configured : state.config().worlds()) {
                runtime.worldPaths().configure(
                        configured.worldId(),
                        configured.path());
            }
            if (liveWorld != null
                    && !runtime.matchesKnownWorld(
                            liveWorld.worldId(),
                            liveWorld.worldDirectory(),
                            state.config())) {
                liveWorld = null;
                liveWorldRevision++;
            }
            resetSchedule(state, liveWorld);
        }
    }

    void close() {
        PendingLiveBackup abandoned;
        synchronized (lock) {
            abandoned = saveGate.clear().orElse(null);
            scheduleState = null;
            liveWorld = null;
            activeServer = null;
            stoppingServer = null;
            clientStopping = false;
            liveWorldRevision++;
        }
        if (abandoned != null) {
            abandoned.fail(new IllegalStateException(
                    "Minecraft stopped before the pending save completed"));
        }
    }

    BackupWorldContext liveWorld() {
        synchronized (lock) {
            return liveWorld;
        }
    }

    Optional<WorldId> activeWorldId() {
        return Optional.ofNullable(liveWorld()).map(BackupWorldContext::worldId);
    }

    IntegratedServer matchingServer(BackupWorldContext world) {
        synchronized (lock) {
            if (activeServer == null
                    || liveWorld == null
                    || !liveWorld.worldId().equals(world.worldId())
                    || !liveWorld.worldDirectory().equals(world.worldDirectory())) {
                return null;
            }
            return activeServer;
        }
    }

    boolean hasPending(WorldId worldId) {
        synchronized (lock) {
            PendingLiveBackup pending = saveGate.pending().orElse(null);
            return pending != null && pending.request().worldId().equals(worldId);
        }
    }

    CompletionStage<BackupResult> queueRequestedSave(
            RuntimeState state,
            IntegratedServer server,
            CreateBackupRequest request,
            ProgressListener progressListener) {
        if (runtime.isClosed()) {
            return WorldArchiveRuntime.failedStage("WorldArchive is shutting down");
        }
        PendingLiveBackup pending = new PendingLiveBackup(
                state,
                server,
                request,
                progressListener,
                new CompletableFuture<>());
        synchronized (lock) {
            if (activeServer != server || stoppingServer == server) {
                return WorldArchiveRuntime.failedStage("The integrated world is closing");
            }
            if (!saveGate.queueRequested(server, pending)) {
                return WorldArchiveRuntime.failedStage(
                        "Another world save is already pending");
            }
        }
        try {
            server.execute(() -> invokeRequestedSave(pending));
        } catch (RejectedExecutionException exception) {
            clearAndFailPending(
                    pending,
                    "The integrated server rejected the backup save");
        }
        return pending.result().minimalCompletionStage();
    }

    private void serverStarted(MinecraftServer server) {
        if (runtime.isClosed() || !(server instanceof IntegratedServer integrated)) {
            return;
        }
        synchronized (lock) {
            activeServer = integrated;
            stoppingServer = null;
            liveWorld = null;
            scheduleState = null;
            liveWorldRevision++;
        }
        ensureLiveWorldResolution();
    }

    private void serverStopping(MinecraftServer server) {
        if (runtime.isClosed() || !(server instanceof IntegratedServer integrated)) {
            return;
        }
        synchronized (lock) {
            if (activeServer != integrated) {
                return;
            }
            stoppingServer = integrated;
        }
        BackupWorldContext world = resolveStoppingWorld(integrated);
        if (world == null) {
            return;
        }
        installExitBackup(integrated, world);
    }

    private BackupWorldContext resolveStoppingWorld(IntegratedServer server) {
        BackupWorldContext world = liveWorldFor(server);
        Throwable failure = null;
        if (world == null) {
            try {
                world = runtime.resolveWorldBlocking(selectionFor(server)).orElse(null);
            } catch (IOException | RuntimeException exception) {
                failure = exception;
                runtime.logFailure(
                        "Exit backup world identity could not be resolved",
                        exception);
            }
        }
        if (world == null) {
            runtime.observeExitResult(
                    null,
                    failure == null
                            ? new IllegalStateException(
                                    "Exit backup world identity was unavailable")
                            : failure);
        }
        return world;
    }

    private void installExitBackup(
            IntegratedServer server,
            BackupWorldContext world) {
        RuntimeConfigurationGate.Permit permit = runtime.configurationGate().enterBackup();
        boolean transferred = false;
        try {
            RuntimeState state = runtime.states().currentOrNull();
            Optional<String> problem = exitBackupProblem(state);
            if (problem.isPresent()) {
                runtime.observeExitResult(
                        null,
                        new IllegalStateException(problem.orElseThrow()));
                return;
            }
            CreateBackupRequest request = WorldArchiveRuntime.request(
                    world,
                    Optional.empty(),
                    BackupTrigger.WORLD_EXIT);
            if (!state.enabledDestinations(request)) {
                return;
            }
            PendingLiveBackup exit = new PendingLiveBackup(
                    state,
                    server,
                    request,
                    ProgressListener.NO_OP,
                    new CompletableFuture<>());
            LiveBackupSaveGate.ExitInstall<PendingLiveBackup> installation;
            synchronized (lock) {
                if (activeServer != server || stoppingServer != server) {
                    return;
                }
                installation = saveGate.installExit(server, exit);
            }
            if (!installation.installed()) {
                runtime.observeExitResult(
                        null,
                        new IllegalStateException(
                                "Another integrated world is still completing its exit backup"));
                return;
            }
            exit.settled().whenComplete((ignored, throwable) -> permit.close());
            transferred = true;
            installation.displaced().ifPresent(displaced -> displaced.fail(
                    new IllegalStateException(
                            "World shutdown replaced the pending manual save")));
            runtime.trackExit(exit.result());
        } finally {
            if (!transferred) {
                permit.close();
            }
        }
    }

    private Optional<String> exitBackupProblem(RuntimeState state) {
        if (state == null || runtime.isClosed()) {
            return Optional.of("World-exit backup runtime was unavailable");
        }
        return runtime.storageIssue(state);
    }

    private void afterSave(MinecraftServer server, boolean flush, boolean force) {
        PendingLiveBackup requested;
        synchronized (lock) {
            requested = saveGate.observeRequestedSave(server, flush, force).orElse(null);
            if (requested == null) {
                saveGate.observeExitSave(server, flush, force);
            }
        }
        if (requested != null) {
            captureAndDispatch(requested);
        }
    }

    private void serverStopped(MinecraftServer server) {
        LiveBackupSaveGate.StopResult<PendingLiveBackup> stopped;
        boolean shutdownAfterServer;
        synchronized (lock) {
            if (activeServer != server
                    && stoppingServer != server
                    && !saveGate.ownedBy(server)) {
                return;
            }
            stopped = saveGate.stop(server);
            if (activeServer == server) {
                activeServer = null;
                liveWorld = null;
                scheduleState = null;
                liveWorldRevision++;
            }
            if (stoppingServer == server) {
                stoppingServer = null;
            }
            shutdownAfterServer = clientStopping
                    && activeServer == null
                    && stoppingServer == null;
        }
        completeStoppedServer(stopped);
        if (shutdownAfterServer) {
            runtime.shutdown();
        }
    }

    private void completeStoppedServer(
            LiveBackupSaveGate.StopResult<PendingLiveBackup> stopped) {
        switch (stopped.kind()) {
            case NONE -> { }
            case REQUEST_ABANDONED -> stopped.value().orElseThrow().fail(
                    new IllegalStateException(
                            "The server stopped before the pending save completed"));
            case EXIT_MISSING_SAVE -> stopped.value().orElseThrow().fail(
                    new IllegalStateException(
                            "The server stopped before its final save completed"));
            case EXIT_READY -> {
                runtime.enqueueWorldExitNotice(
                        BackgroundBackupWarnings.worldExitStartedNotice());
                captureAndDispatch(stopped.value().orElseThrow());
            }
            default -> throw new IllegalStateException(
                    "Unknown live-save stop outcome");
        }
    }

    private void clientStopping(Minecraft ignored) {
        boolean noIntegratedServer;
        synchronized (lock) {
            clientStopping = true;
            noIntegratedServer = activeServer == null && stoppingServer == null;
        }
        if (noIntegratedServer) {
            runtime.shutdown();
        }
    }

    private void clientTick(Minecraft ignored) {
        runtime.showRetainedBackgroundWarning();
        RuntimeState state = runtime.states().currentOrNull();
        if (runtime.isClosed()
                || state == null
                || !state.config().triggers().scheduledEnabled()) {
            return;
        }
        ScheduledBackup scheduled = pollScheduledBackup(state);
        if (scheduled == null) {
            return;
        }
        startScheduledBackup(state, scheduled)
                .whenComplete(runtime::observeScheduledResult);
    }

    private ScheduledBackup pollScheduledBackup(RuntimeState state) {
        synchronized (lock) {
            if (scheduleState == null
                    || scheduleState.state() != state
                    || liveWorld == null
                    || activeServer == null
                    || !scheduleState.worldId().equals(liveWorld.worldId())
                    || scheduleState.schedule().poll(clock.instant()).isEmpty()
                    || saveGate.hasPending()) {
                return null;
            }
            return new ScheduledBackup(liveWorld, activeServer);
        }
    }

    private CompletionStage<BackupResult> startScheduledBackup(
            RuntimeState state,
            ScheduledBackup scheduled) {
        return runtime.withBackupPermit(() -> {
            if (runtime.states().currentOrNull() != state
                    || runtime.busyAcrossStates(scheduled.world().worldId())) {
                return WorldArchiveRuntime.failedStage(
                        "Scheduled backup configuration changed before capture");
            }
            Optional<String> storageIssue = runtime.storageIssue(state);
            if (storageIssue.isPresent()) {
                return WorldArchiveRuntime.failedStage(storageIssue.orElseThrow());
            }
            CreateBackupRequest request = WorldArchiveRuntime.request(
                    scheduled.world(),
                    Optional.empty(),
                    BackupTrigger.SCHEDULED);
            if (!state.enabledDestinations(request)) {
                return WorldArchiveRuntime.failedStage(
                        "Scheduled backups are disabled for this world");
            }
            return queueRequestedSave(
                    state,
                    scheduled.server(),
                    request,
                    ProgressListener.NO_OP);
        });
    }

    private void invokeRequestedSave(PendingLiveBackup pending) {
        synchronized (lock) {
            if (saveGate.pending().orElse(null) != pending
                    || stoppingServer == pending.server()
                    || !saveGate.armRequested(pending.server(), pending)) {
                return;
            }
        }
        try {
            pending.server().saveEverything(false, true, true);
        } catch (RuntimeException exception) {
            clearAndFailPending(
                    pending,
                    "The integrated world could not be saved");
            runtime.logFailure("Requested world save failed", exception);
        } finally {
            synchronized (lock) {
                if (saveGate.clear(pending.server(), pending).isPresent()) {
                    pending.fail(new IllegalStateException(
                            "The requested save produced no matching capture event"));
                }
            }
        }
    }

    private void captureAndDispatch(PendingLiveBackup pending) {
        PreparedBackup prepared = prepareCapture(pending);
        if (prepared == null) {
            return;
        }
        CompletionStage<BackupResult> operation;
        try {
            operation = pending.state().coordinator().createPreparedBackup(
                    prepared,
                    pending.progressListener());
        } catch (RuntimeException | Error exception) {
            try {
                prepared.close();
            } catch (IOException closeFailure) {
                exception.addSuppressed(closeFailure);
            }
            pending.fail(WorldArchiveRuntime.safeFailure(
                    exception,
                    "Prepared backup could not be started"));
            if (exception instanceof Error error) {
                throw error;
            }
            return;
        }
        operation.whenComplete((result, throwable) -> completeBackup(
                pending,
                result,
                throwable));
    }

    private PreparedBackup prepareCapture(PendingLiveBackup pending) {
        try {
            CaptureProgressListener progress = (completed, total) -> pending.state()
                    .coordinator()
                    .currentOperation(pending.request().worldId())
                    .ifPresent(current -> WorldArchiveRuntime.notifyProgress(
                            pending.progressListener(),
                            current));
            return pending.state().coordinator().prepareCapture(
                    pending.request(),
                    progress);
        } catch (IOException | InterruptedException | RuntimeException | Error exception) {
            if (exception instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            pending.fail(WorldArchiveRuntime.safeFailure(
                    exception,
                    "World capture could not be prepared"));
            if (exception instanceof Error error) {
                throw error;
            }
            return null;
        }
    }

    private static void completeBackup(
            PendingLiveBackup pending,
            BackupResult result,
            Throwable throwable) {
        if (throwable != null) {
            pending.fail(throwable);
        } else if (result == null) {
            pending.fail(new IllegalStateException(
                    "Backup completed without a result"));
        } else {
            pending.succeed(result);
        }
    }

    private void completeWorldResolution(
            IntegratedServer server,
            long revision,
            Optional<BackupWorldContext> resolved,
            Throwable throwable) {
        if (throwable != null) {
            runtime.logFailure("Integrated world identity could not be resolved", throwable);
            return;
        }
        synchronized (lock) {
            if (runtime.isClosed()
                    || activeServer != server
                    || liveWorldRevision != revision
                    || resolved.isEmpty()) {
                return;
            }
            liveWorld = resolved.orElseThrow();
            resetSchedule(runtime.states().currentOrNull(), liveWorld);
        }
    }

    private void resetSchedule(RuntimeState state, BackupWorldContext world) {
        if (state == null || world == null || activeServer == null) {
            scheduleState = null;
            return;
        }
        scheduleState = new ScheduleState(
                state,
                world.worldId(),
                NoCatchUpSchedule.minutes(
                        state.config().triggers().scheduleIntervalMinutes(),
                        clock.instant()));
    }

    private BackupWorldContext liveWorldFor(IntegratedServer server) {
        synchronized (lock) {
            return activeServer == server ? liveWorld : null;
        }
    }

    private void clearAndFailPending(PendingLiveBackup pending, String message) {
        synchronized (lock) {
            saveGate.clear(pending.server(), pending);
        }
        pending.fail(new IllegalStateException(message));
    }

    private static BackupWorldSelection selectionFor(IntegratedServer server) {
        Path worldDirectory = server.getWorldPath(LevelResource.ROOT)
                .toAbsolutePath()
                .normalize();
        Path worldsDirectory = worldDirectory.getParent();
        Path fileName = worldDirectory.getFileName();
        if (worldsDirectory == null || fileName == null) {
            throw new IllegalStateException(
                    "Integrated world path has no storage parent");
        }
        return new BackupWorldSelection(
                worldDirectory,
                worldsDirectory,
                fileName.toString(),
                server.getWorldData().getLevelName());
    }

    private record ScheduleState(
            RuntimeState state,
            WorldId worldId,
            NoCatchUpSchedule schedule) {
        private ScheduleState {
            Objects.requireNonNull(state, "state");
            Objects.requireNonNull(worldId, "worldId");
            Objects.requireNonNull(schedule, "schedule");
        }
    }

    private record ScheduledBackup(
            BackupWorldContext world,
            IntegratedServer server) {
        private ScheduledBackup {
            Objects.requireNonNull(world, "world");
            Objects.requireNonNull(server, "server");
        }
    }

    private static final class PendingLiveBackup {
        private final RuntimeState state;

        private final IntegratedServer server;

        private final CreateBackupRequest request;

        private final ProgressListener progressListener;

        private final CompletableFuture<BackupResult> result;

        private final CompletableFuture<Void> settled = new CompletableFuture<>();

        private PendingLiveBackup(
                RuntimeState state,
                IntegratedServer server,
                CreateBackupRequest request,
                ProgressListener progressListener,
                CompletableFuture<BackupResult> result) {
            this.state = Objects.requireNonNull(state, "state");
            this.server = Objects.requireNonNull(server, "server");
            this.request = Objects.requireNonNull(request, "request");
            this.progressListener = Objects.requireNonNull(
                    progressListener,
                    "progressListener");
            this.result = Objects.requireNonNull(result, "result");
        }

        private RuntimeState state() {
            return state;
        }

        private IntegratedServer server() {
            return server;
        }

        private CreateBackupRequest request() {
            return request;
        }

        private ProgressListener progressListener() {
            return progressListener;
        }

        private CompletableFuture<BackupResult> result() {
            return result;
        }

        private CompletableFuture<Void> settled() {
            return settled;
        }

        private void succeed(BackupResult value) {
            result.complete(value);
            settled.complete(null);
        }

        private void fail(Throwable throwable) {
            result.completeExceptionally(throwable);
            settled.complete(null);
        }
    }
}
