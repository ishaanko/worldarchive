package dev.ishaanko.worldarchive.runtime;

import dev.ishaanko.worldarchive.core.CaptureKind;
import dev.ishaanko.worldarchive.core.CreateBackupRequest;
import dev.ishaanko.worldarchive.core.PreparedBackup;
import dev.ishaanko.worldarchive.model.BackupResult;
import dev.ishaanko.worldarchive.model.BackupTrigger;
import dev.ishaanko.worldarchive.model.ProgressListener;
import dev.ishaanko.worldarchive.model.SafeText;
import dev.ishaanko.worldarchive.model.WorldId;
import dev.ishaanko.worldarchive.ui.model.BackupWorldContext;
import dev.ishaanko.worldarchive.ui.model.BackupWorldSelection;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Backs up the world the integrated server runs. A manual or scheduled backup of the open world
 * asks the server to save on its own thread, then copies the world on a worker while autosave is
 * paused. A world-exit backup copies the world on a worker after the server's final save, when
 * nothing writes to it any more. The Fabric adapter reports the server's events here through
 * {@link LiveServer}; every event method returns at once, and a backup problem is reported to the
 * player instead of thrown.
 *
 * <p>A scheduled backup is skipped without a word when the player turned it off, when a backup of
 * the world is running, and when the server has not ticked since the last backup of the world
 * in this session, which is the case while the game is paused.</p>
 */
public final class LiveWorldBackups {
    private static final Logger LOGGER = LoggerFactory.getLogger("WorldArchive");

    private static final long FIRST_RETRY_NANOS = Duration.ofMillis(250).toNanos();

    private static final long LAST_RETRY_NANOS = Duration.ofSeconds(30).toNanos();

    private static final int NAME_LIMIT = 120;

    private final ServiceGraph graph;

    private final WorldIdentityResolver resolver;

    private final BackgroundReports reports;

    private final LongSupplier nanoTime;

    private final Object lock = new Object();

    /** The last background warning of each world, for its backup browser. */
    private final Map<WorldId, Notice> warnings = new ConcurrentHashMap<>();

    private final AtomicInteger exitBackups = new AtomicInteger();

    private final LiveBackup.Owner owner = new LiveBackup.Owner() {
        @Override
        public void dropSave(LiveBackup backup) {
            dropPending(backup);
        }

        @Override
        public void keptAt(LiveBackup backup, int tick) {
            backedUpAt(backup.server, backup.worldId(), tick);
        }
    };

    // Guarded by lock: the open world, the save waiting for the server thread, and the autosave
    // pauses of servers whose world is being copied.
    private Session session;

    private Pending pending;

    private final Map<LiveServer, Pause> pauses = new HashMap<>();

    private boolean closed;

    /** @param nanoTime a monotonic clock in nanoseconds, such as {@link System#nanoTime} */
    public LiveWorldBackups(
            ServiceGraph graph,
            WorldIdentityResolver resolver,
            BackgroundReports reports,
            LongSupplier nanoTime) {
        this.graph = Objects.requireNonNull(graph, "graph");
        this.resolver = Objects.requireNonNull(resolver, "resolver");
        this.reports = Objects.requireNonNull(reports, "reports");
        this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
    }

    /** Whether a world is the one the server runs, and whether its identity is known yet. */
    public enum OpenWorld {
        NOT_OPEN,
        LOADING,
        READY
    }

    /** The server finished starting a world; server thread. */
    public void started(LiveServer server) {
        BackupWorldSelection selection = selection(server);
        if (!resolver.inSaves(selection)) {
            return;
        }
        Path world = realPath(selection.worldDirectory());
        Pending stale;
        synchronized (lock) {
            if (closed) {
                return;
            }
            // A world that closed without the server reporting its stop leaves its save behind.
            stale = pending;
            pending = null;
            session = new Session(server, selection, world, nanoTime.getAsLong());
        }
        abandon(stale, "The previous world closed before its backup could start");
        resolve();
    }

    /** The server begins to stop, before its final save; server thread. */
    public void stopping(LiveServer server) {
        Optional<RuntimeState> state = graph.current();
        Pending displaced;
        synchronized (lock) {
            if (session == null || !session.server.equals(server)) {
                return;
            }
            session.stopping = true;
            displaced = pending;
            // Without settings, as when the file cannot be read, the exit still reports that no backup was made.
            boolean exitBackup = !closed && state
                    .map(current -> TriggerCheck.anyDestinationTakes(current.config(), BackupTrigger.WORLD_EXIT))
                    .orElse(true);
            pending = exitBackup ? new ExitSave(server, false) : null;
        }
        abandon(displaced, "The world closed before the backup could save it");
    }

    /** The server saved its levels; server thread. The final save of a stopping server allows its world-exit backup. */
    public void saved(LiveServer server, boolean flush, boolean force) {
        synchronized (lock) {
            if (pending instanceof ExitSave exit && exit.server().equals(server) && flush && !force) {
                pending = new ExitSave(server, true);
            }
        }
    }

    /** The server stopped; server thread. Starts the world-exit backup when the final save happened. */
    public void stopped(LiveServer server) {
        ExitSave exit = null;
        Session ended = null;
        synchronized (lock) {
            if (pending instanceof ExitSave candidate && candidate.server().equals(server)) {
                exit = candidate;
                pending = null;
            }
            if (session != null && session.server.equals(server)) {
                ended = session;
                session = null;
            }
        }
        if (exit == null || ended == null) {
            return;
        }
        if (exit.saved()) {
            startExitBackup(ended);
        } else {
            reports.exitNotMade(BackgroundNotices.saveMissing());
        }
    }

    /** Fails the save waiting on {@code server} after an event handler of that server failed. */
    public void abandon(LiveServer server, Throwable failure) {
        Pending taken = null;
        synchronized (lock) {
            if (pending != null && pending.server().equals(server)) {
                taken = pending;
                pending = null;
            }
        }
        abandon(taken, "WorldArchive failed while the world changed state: "
                + SafeText.from(failure, "no reason was given", 200));
    }

    /** Runs every client tick: resolves the open world's identity, then starts a scheduled backup that is due. */
    public void tick() {
        resolve();
        graph.current().ifPresent(this::startScheduledBackup);
    }

    /** The settings changed: the open world is resolved again when its claim changed, and refusals are forgotten. */
    public void reconcile() {
        synchronized (lock) {
            Session current = session;
            if (current == null) {
                return;
            }
            current.generation++;
            current.resolving = false;
            current.refused = false;
            current.retryDelay = FIRST_RETRY_NANOS;
            current.nextAttempt = nanoTime.getAsLong();
            if (current.world != null
                    && !resolver.isRegistered(current.world.worldId(), current.world.worldDirectory())) {
                current.world = null;
            }
        }
    }

    /** Takes no more work; a save that waits for the server fails, and backups that run finish. */
    public void close() {
        Pending taken;
        synchronized (lock) {
            closed = true;
            taken = pending;
            pending = null;
        }
        abandon(taken, "Minecraft is closing");
    }

    /** The open world, once its identity is known. */
    public Optional<BackupWorldContext> liveWorld() {
        synchronized (lock) {
            return session == null || session.stopping ? Optional.empty() : Optional.ofNullable(session.world);
        }
    }

    /** Whether {@code world} is the world the server runs; compared by the real folder, so a link cannot hide it. */
    public OpenWorld openWorld(BackupWorldContext world) {
        synchronized (lock) {
            if (session == null || !session.realWorld.equals(world.worldDirectory())) {
                return OpenWorld.NOT_OPEN;
            }
            return session.world != null && !session.stopping && session.world.worldId().equals(world.worldId())
                    ? OpenWorld.READY
                    : OpenWorld.LOADING;
        }
    }

    /** True while a backup of the world waits for the server to save it. */
    public boolean hasPending(WorldId worldId) {
        synchronized (lock) {
            return pending instanceof RequestedSave requested && requested.backup().worldId().equals(worldId);
        }
    }

    /** The last warning of the world's world-exit or scheduled backups; empty after a success. */
    public Optional<Notice> warning(WorldId worldId) {
        return Optional.ofNullable(warnings.get(worldId));
    }

    /** True while a world-exit backup has not settled. */
    public boolean exitBackupsRunning() {
        return exitBackups.get() > 0;
    }

    /**
     * Backs up the open world: the server saves it on its thread, then a worker copies it while
     * autosave is paused. Cancelling the returned stage asks the backup to stop, and returns true
     * only when the backup then ends as cancelled.
     *
     * @param permit a work permit, which the backup releases once it and its destinations stopped
     */
    public CompletableFuture<BackupResult> backUpOpenWorld(
            RuntimeState state,
            CreateBackupRequest request,
            ProgressListener listener,
            ConfigurationGate.Permit permit) {
        LiveBackup backup = null;
        String refusal;
        synchronized (lock) {
            refusal = requestRefusal(request.worldId());
            if (refusal == null) {
                backup = new LiveBackup(session.server, state, request, permit, owner);
                pending = new RequestedSave(session.server, backup);
            }
        }
        if (refusal != null) {
            permit.close();
            return CompletableFuture.failedFuture(new IllegalStateException(refusal));
        }
        LiveBackup requested = backup;
        try {
            requested.server.execute(() -> saveAndCapture(requested, listener));
        } catch (RejectedExecutionException closing) {
            dropPending(requested);
            requested.fail(new IllegalStateException("The world closed before the backup could save it"));
        }
        return requested.view();
    }

    private String requestRefusal(WorldId worldId) {
        if (closed || session == null || session.stopping || session.world == null
                || !session.world.worldId().equals(worldId)) {
            return "The world is closing. Try again from the world list.";
        }
        if (pauses.containsKey(session.server)) {
            return "A backup is still copying this world. Try again in a moment.";
        }
        if (pending != null) {
            return "Another backup of this world is starting. Try again in a moment.";
        }
        return null;
    }

    /** Saves the world and starts the copy; server thread. */
    private void saveAndCapture(LiveBackup backup, ProgressListener listener) {
        LiveServer server = backup.server;
        if (!server.isServerThread()) {
            // A stopped server runs a task at once on the caller's thread; the world must not be saved from there.
            dropPending(backup);
            backup.fail(new IllegalStateException("The world closed before the backup could save it"));
            return;
        }
        boolean autoSave = server.isAutoSave();
        synchronized (lock) {
            if (!(pending instanceof RequestedSave requested) || requested.backup() != backup) {
                // Cancelled, or replaced by the world-exit save; whoever took it failed the backup.
                return;
            }
            pending = null;
            pauses.computeIfAbsent(server, ignored -> new Pause(autoSave)).captures++;
        }
        backup.savedAt(server.tickCount());
        try {
            server.saveAll();
        } catch (RuntimeException failure) {
            resumeSaving(server);
            LOGGER.warn("The world could not be saved for a backup: {}", SafeText.from(failure, "no reason", 300));
            backup.fail(new IllegalStateException("The world could not be saved, so no backup was made. "
                    + SafeText.from(failure, "", 300)));
            return;
        }
        server.setAutoSave(false);
        AtomicBoolean paused = new AtomicBoolean(true);
        Runnable resume = () -> {
            if (paused.getAndSet(false)) {
                resumeSaving(server);
            }
        };
        submit(backup, () -> {
            try {
                capture(backup, CaptureKind.OPEN_WORLD, listener, resume);
            } finally {
                resume.run();
            }
        });
    }

    private void startScheduledBackup(RuntimeState state) {
        ScheduledRun run;
        synchronized (lock) {
            run = dueScheduledRun(state);
        }
        if (run == null || run.lastBackupTick() != LiveBackup.NO_TICK && run.server().tickCount() == run.lastBackupTick()) {
            // Nothing ran on the server since the last backup of this world, so nothing changed.
            return;
        }
        WorldId worldId = run.world().worldId();
        if (graph.busy(worldId)) {
            return;
        }
        CreateBackupRequest request;
        switch (TriggerCheck.evaluate(state, resolver.storageIssue(), run.world(),
                BackupTrigger.SCHEDULED, Optional.empty())) {
            case TriggerCheck.Decision.Proceed proceed -> request = proceed.request();
            case TriggerCheck.Decision.Refused refused -> {
                if (!refused.reason().silentFor(BackupTrigger.SCHEDULED)) {
                    warn(worldId, BackgroundNotices.scheduledNotMade(refused.message()));
                }
                return;
            }
        }
        ConfigurationGate.Permit permit;
        try {
            permit = graph.gate().enterWork();
        } catch (IllegalStateException settingsBeingSaved) {
            return;
        }
        backUpOpenWorld(state, request, ProgressListener.NO_OP, permit).whenComplete((result, failure) -> {
            Optional<Notice> warning = BackgroundNotices.scheduledOutcome(result, failure);
            if (warning.isPresent()) {
                warn(worldId, warning.get());
            } else if (failure == null) {
                warnings.remove(worldId);
            }
        });
    }

    /**
     * The scheduled backup to start now, or null. A due run is used up whether it starts or not, so
     * missed runs are never replayed; the first run is due one interval after the world opened.
     */
    private ScheduledRun dueScheduledRun(RuntimeState state) {
        Session current = session;
        if (closed || current == null || current.world == null || current.stopping) {
            return null;
        }
        if (!state.config().triggers().scheduledEnabled()) {
            current.scheduleInterval = 0;
            return null;
        }
        long interval = TimeUnit.MINUTES.toNanos(state.config().triggers().scheduleIntervalMinutes());
        long now = nanoTime.getAsLong();
        if (current.scheduleInterval != interval) {
            current.scheduleInterval = interval;
            current.nextScheduled = now + interval;
            return null;
        }
        if (now - current.nextScheduled < 0) {
            return null;
        }
        current.nextScheduled = now + interval;
        if (pending != null || pauses.containsKey(current.server)) {
            return null;
        }
        return new ScheduledRun(current.server, current.world, current.lastBackupTick);
    }

    private void startExitBackup(Session ended) {
        ConfigurationGate.Permit permit;
        try {
            permit = graph.gate().enterWork();
        } catch (IllegalStateException refused) {
            reports.exitNotMade(BackgroundNotices.exitNotMade(refused.getMessage()));
            return;
        }
        exitBackups.incrementAndGet();
        try {
            graph.executor().execute(() -> runExitBackup(ended, permit));
        } catch (RejectedExecutionException shuttingDown) {
            exitBackups.decrementAndGet();
            permit.close();
            reports.exitNotMade(BackgroundNotices.exitNotMade("WorldArchive is shutting down"));
        }
    }

    /** Checks and starts the world-exit backup of a world that just closed; worker thread. */
    private void runExitBackup(Session ended, ConfigurationGate.Permit permit) {
        LiveBackup backup = null;
        try {
            backup = exitBackup(ended, permit);
        } catch (IOException | RuntimeException failure) {
            reports.exitNotMade(BackgroundNotices.exitNotMade(SafeText.from(
                    failure, "WorldArchive could not read the world's identity", 200)));
        } finally {
            if (backup == null) {
                permit.close();
                exitBackups.decrementAndGet();
            }
        }
        if (backup != null) {
            LiveBackup started = backup;
            BackgroundReports.ExitReport report = reports.exitStarted(started::cancel);
            started.result.whenComplete((result, failure) -> {
                exitBackups.decrementAndGet();
                Notice outcome = BackgroundNotices.exitOutcome(result, failure);
                report.finished(outcome);
                if (outcome.severity() == Notice.Severity.SUCCESS) {
                    warnings.remove(started.worldId());
                } else {
                    warnings.put(started.worldId(), outcome);
                }
            });
            // The server stopped, so nothing writes to the world any more: copy it on this worker.
            try {
                capture(started, CaptureKind.CLOSED_WORLD, report, () -> { });
            } catch (RuntimeException | Error unexpected) {
                started.fail(unexpected);
                throw unexpected;
            }
        }
    }

    /** The backup to make of a world that just closed; null when there is none, which was reported. */
    private LiveBackup exitBackup(Session ended, ConfigurationGate.Permit permit) throws IOException {
        BackupWorldContext world = ended.world;
        if (world == null) {
            switch (resolver.resolve(ended.selection)) {
                case WorldIdentityResolver.Resolution.Resolved resolved -> world = resolved.world();
                case WorldIdentityResolver.Resolution.Refused refused -> {
                    reports.exitNotMade(BackgroundNotices.exitNotMade(refused.reason()));
                    return null;
                }
            }
        }
        Optional<RuntimeState> current = graph.current();
        if (current.isEmpty()) {
            reports.exitNotMade(BackgroundNotices.exitNotMade(
                    "WorldArchive settings could not be loaded. Open WorldArchive settings to see why"));
            return null;
        }
        RuntimeState state = current.get();
        switch (TriggerCheck.evaluate(state, resolver.storageIssue(), world,
                BackupTrigger.WORLD_EXIT, Optional.empty())) {
            case TriggerCheck.Decision.Proceed proceed -> {
                return new LiveBackup(ended.server, state, proceed.request(), permit, owner);
            }
            case TriggerCheck.Decision.Refused refused -> {
                if (!refused.reason().silentFor(BackupTrigger.WORLD_EXIT)) {
                    reports.exitNotMade(BackgroundNotices.exitNotMade(refused.message()));
                }
                return null;
            }
        }
    }

    /**
     * Copies the world on the calling worker, runs {@code afterCopy}, then hands the copy to the
     * destinations. A cancel during the copy interrupts it; one that arrives before it skips it.
     */
    private void capture(LiveBackup backup, CaptureKind kind, ProgressListener listener, Runnable afterCopy) {
        if (!backup.beginCapture()) {
            afterCopy.run();
            backup.fail(LiveBackup.cancellation());
            return;
        }
        PreparedBackup prepared = null;
        Exception failure = null;
        try {
            prepared = backup.state.coordinator().prepareCapture(backup.request, kind, listener);
        } catch (IOException | InterruptedException | RuntimeException exception) {
            failure = exception;
        }
        boolean proceed = backup.endCapture();
        afterCopy.run();
        if (failure instanceof InterruptedException && proceed) {
            // Not our cancel: the executor is stopping, so keep the interrupt for it.
            Thread.currentThread().interrupt();
        }
        if (!proceed) {
            closeQuietly(prepared);
            backup.fail(LiveBackup.cancellation());
            return;
        }
        if (failure != null) {
            backup.fail(failure);
            return;
        }
        CompletableFuture<BackupResult> operation =
                backup.state.coordinator().createPreparedBackup(prepared, listener).toCompletableFuture();
        backup.dispatched(operation);
        operation.whenComplete(backup::settle);
    }

    private void submit(LiveBackup backup, Runnable work) {
        try {
            graph.executor().execute(() -> {
                try {
                    work.run();
                } catch (RuntimeException | Error unexpected) {
                    backup.fail(unexpected);
                    throw unexpected;
                }
            });
        } catch (RejectedExecutionException shuttingDown) {
            resumeSaving(backup.server);
            backup.fail(new IllegalStateException("WorldArchive is shutting down"));
        }
    }

    /**
     * Ends a capture's autosave pause; the last capture of a server restores the saving the player
     * had, on the server thread. The pause stays until then, so a backup requested meanwhile is
     * refused instead of reading the paused autosave as the player's own.
     */
    private void resumeSaving(LiveServer server) {
        synchronized (lock) {
            Pause pause = pauses.get(server);
            if (pause == null || --pause.captures > 0) {
                return;
            }
        }
        try {
            server.execute(() -> restoreAutoSave(server));
        } catch (RejectedExecutionException stopped) {
            synchronized (lock) {
                pauses.remove(server);
            }
            LOGGER.debug("The server stopped before autosave could be restored");
        }
    }

    /** Ends the autosave pause of a server whose captures all ended; server thread. */
    private void restoreAutoSave(LiveServer server) {
        boolean restore;
        boolean running;
        synchronized (lock) {
            Pause pause = pauses.get(server);
            if (pause == null || pause.captures > 0) {
                return;
            }
            pauses.remove(server);
            restore = pause.autoSaveBefore;
            running = session != null && session.server.equals(server) && !session.stopping;
        }
        // A stopping server turns saving on for its final save by itself; never touch it then.
        if (server.isServerThread() && running) {
            server.setAutoSave(restore);
        }
    }

    private void backedUpAt(LiveServer server, WorldId worldId, int tick) {
        synchronized (lock) {
            if (session != null && session.server.equals(server) && session.world != null
                    && session.world.worldId().equals(worldId)) {
                session.lastBackupTick = tick;
            }
        }
    }

    private void warn(WorldId worldId, Notice notice) {
        warnings.put(worldId, notice);
        reports.scheduledWarning(notice);
    }

    /** Starts resolving the open world's identity on a worker, unless that runs, failed recently or was refused. */
    private void resolve() {
        Session current;
        long generation;
        synchronized (lock) {
            current = session;
            if (closed || current == null || current.world != null || current.resolving || current.refused
                    || nanoTime.getAsLong() - current.nextAttempt < 0) {
                return;
            }
            current.resolving = true;
            generation = current.generation;
        }
        try {
            graph.executor().execute(() -> resolved(current, generation));
        } catch (RejectedExecutionException shuttingDown) {
            synchronized (lock) {
                current.resolving = false;
            }
        }
    }

    private void resolved(Session resolving, long generation) {
        WorldIdentityResolver.Resolution resolution = null;
        Exception failure = null;
        try {
            resolution = resolver.resolve(resolving.selection);
        } catch (IOException | RuntimeException exception) {
            failure = exception;
        }
        String log = null;
        synchronized (lock) {
            if (session != resolving || resolving.generation != generation) {
                return;
            }
            resolving.resolving = false;
            if (resolution instanceof WorldIdentityResolver.Resolution.Resolved resolved) {
                resolving.world = resolved.world();
            } else if (resolution instanceof WorldIdentityResolver.Resolution.Refused refused) {
                resolving.refused = true;
                log = refused.reason();
            } else {
                resolving.nextAttempt = nanoTime.getAsLong() + resolving.retryDelay;
                resolving.retryDelay = Math.min(resolving.retryDelay * 2, LAST_RETRY_NANOS);
                if (!resolving.failureLogged) {
                    resolving.failureLogged = true;
                    log = SafeText.from(failure, "the identity could not be read", 300) + "; trying again";
                }
            }
        }
        if (log != null) {
            LOGGER.warn("WorldArchive does not back up the open world yet: {}", log);
        }
    }

    private void dropPending(LiveBackup backup) {
        synchronized (lock) {
            if (pending instanceof RequestedSave requested && requested.backup() == backup) {
                pending = null;
            }
        }
    }

    /** Ends a save that no longer waits for its server: a requested backup fails, a world-exit save reports it. */
    private void abandon(Pending taken, String reason) {
        if (taken instanceof RequestedSave requested) {
            requested.backup().fail(new IllegalStateException(reason));
        } else if (taken instanceof ExitSave) {
            reports.exitNotMade(BackgroundNotices.saveMissing());
        }
    }

    /** The world as the server names it; a level name with nothing readable falls back to the folder name. */
    private static BackupWorldSelection selection(LiveServer server) {
        Path world = server.worldDirectory().toAbsolutePath().normalize();
        Path saves = world.getParent();
        Path folder = world.getFileName();
        if (saves == null || folder == null) {
            throw new IllegalStateException("The open world has no saves folder");
        }
        String levelName = server.levelName() == null ? "" : SafeText.clean(server.levelName(), NAME_LIMIT);
        return new BackupWorldSelection(
                world, saves, folder.toString(), levelName.isEmpty() ? folder.toString() : levelName);
    }

    private static Path realPath(Path world) {
        try {
            return world.toRealPath();
        } catch (IOException missing) {
            return world;
        }
    }

    private static void closeQuietly(PreparedBackup prepared) {
        if (prepared == null) {
            return;
        }
        try {
            prepared.close();
        } catch (IOException exception) {
            LOGGER.warn("A cancelled world copy could not be removed; the next start removes it: {}",
                    SafeText.from(exception, "no reason", 300));
        }
    }

    /** The world the server runs, from its start until it stopped. Guarded by the lock. */
    private static final class Session {
        private final LiveServer server;

        private final BackupWorldSelection selection;

        /** The real folder of the open world; decides whether a world in a list is the open one. */
        private final Path realWorld;

        private BackupWorldContext world;

        private boolean stopping;

        private boolean resolving;

        private boolean refused;

        private boolean failureLogged;

        private long generation;

        private long nextAttempt;

        private long retryDelay = FIRST_RETRY_NANOS;

        private long scheduleInterval;

        private long nextScheduled;

        private int lastBackupTick = LiveBackup.NO_TICK;

        private Session(LiveServer server, BackupWorldSelection selection, Path realWorld, long now) {
            this.server = server;
            this.selection = selection;
            this.realWorld = realWorld;
            this.nextAttempt = now;
        }
    }

    /** The captures that pause one server's autosave, and the saving the player had before. */
    private static final class Pause {
        private final boolean autoSaveBefore;

        private int captures;

        private Pause(boolean autoSaveBefore) {
            this.autoSaveBefore = autoSaveBefore;
        }
    }

    /** A save the server still has to run: one a backup asked for, or the final save of a stopping server. */
    private sealed interface Pending permits RequestedSave, ExitSave {
        LiveServer server();
    }

    /** A manual or scheduled backup waiting for the server thread to save the world. */
    private record RequestedSave(LiveServer server, LiveBackup backup) implements Pending {
    }

    /** A stopping server's world-exit backup, waiting for the final save; {@code saved} once it happened. */
    private record ExitSave(LiveServer server, boolean saved) implements Pending {
    }

    /** A scheduled backup that is due, and the tick of the world's last backup in this session. */
    private record ScheduledRun(LiveServer server, BackupWorldContext world, int lastBackupTick) {
    }
}
