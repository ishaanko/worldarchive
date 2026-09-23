package dev.ishaanko.worldarchive.settings;

import dev.ishaanko.worldarchive.config.UnreadableConfigurationException;
import dev.ishaanko.worldarchive.config.WorldArchiveConfig;
import dev.ishaanko.worldarchive.config.WorldArchiveConfigStore;
import dev.ishaanko.worldarchive.config.WorldIdentityStore;
import dev.ishaanko.worldarchive.model.SafeText;
import dev.ishaanko.worldarchive.model.WorldId;
import dev.ishaanko.worldarchive.support.AsyncTasks;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The one owner of WorldArchive's settings for a game folder. It loads the settings file, keeps
 * the world list in step with the saves folder, and applies every change as an update of what
 * the file holds at that moment, so a change from another game window or a hand edit is kept.
 *
 * <p>When the file exists but cannot be read, every change is refused and nothing is written
 * over the file; {@link #unreadable()} says why. {@link #load} can run again once the player
 * fixed the file, and {@link #reset} starts over while keeping the old file under a new name.</p>
 *
 * <p>All file work runs on the executor, which must run one task at a time, and every method
 * returns at once. Listeners and guards run on that executor too.</p>
 */
public final class SettingsService {
    private static final Logger LOGGER = LoggerFactory.getLogger("WorldArchive");

    private static final int MESSAGE_LIMIT = 512;

    private final WorldArchiveConfigStore store;

    private final Path savesDirectory;

    private final WorldIdentityStore identities;

    private final Executor executor;

    private final Clock clock;

    private final Set<Path> knownWorlds = ConcurrentHashMap.newKeySet();

    private final List<Consumer<WorldArchiveConfig>> listeners = new CopyOnWriteArrayList<>();

    private final List<Function<WorldArchiveConfig, Runnable>> guards = new CopyOnWriteArrayList<>();

    private final AtomicReference<PendingValidation> pendingValidation = new AtomicReference<>();

    private volatile WorldArchiveConfig current = WorldArchiveConfig.defaults();

    private volatile UnreadableConfigurationException unreadable;

    private volatile List<WorldNotice> notices = List.of();

    public SettingsService(
            WorldArchiveConfigStore store,
            Path savesDirectory,
            WorldIdentityStore identities,
            Executor executor,
            Clock clock) {
        this.store = Objects.requireNonNull(store, "store");
        this.savesDirectory = Objects.requireNonNull(savesDirectory, "savesDirectory").toAbsolutePath().normalize();
        this.identities = Objects.requireNonNull(identities, "identities");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * The settings as last loaded or saved, with the default folders filled in. Before the first
     * load, and while the file cannot be read, these are product defaults that nothing saves.
     */
    public WorldArchiveConfig current() {
        return current;
    }

    /** Why the settings file cannot be read; empty while it can. */
    public Optional<UnreadableConfigurationException> unreadable() {
        return Optional.ofNullable(unreadable);
    }

    /** What the last scan of the saves folder found that the player should know. */
    public List<WorldNotice> notices() {
        return notices;
    }

    /** Receives the settings after each load and each saved change. */
    public void addConfigurationListener(Consumer<WorldArchiveConfig> listener) {
        listeners.add(Objects.requireNonNull(listener, "listener"));
    }

    /**
     * Sees each changed configuration before it is written and may refuse it by throwing. The
     * returned release runs after the change was written and published, or failed.
     */
    public void addConfigurationGuard(Function<WorldArchiveConfig, Runnable> guard) {
        guards.add(Objects.requireNonNull(guard, "guard"));
    }

    /**
     * Reads the settings file and brings the world list in step with the saves folder. Fails
     * with {@link UnreadableConfigurationException} when the file cannot be read. A failure to
     * save the world list is only logged: the settings were read and are used.
     */
    public CompletionStage<WorldArchiveConfig> load() {
        return AsyncTasks.supplyChecked(executor, this::loadNow);
    }

    /** Applies a change to the settings as the file holds them now; see {@link WorldArchiveConfigStore#update}. */
    public CompletionStage<WorldArchiveConfig> update(UnaryOperator<WorldArchiveConfig> change) {
        Objects.requireNonNull(change, "change");
        return AsyncTasks.supplyChecked(executor, () -> updateNow(change));
    }

    /** The settings screen's save: its edits, plus worlds created since the last scan, in one update. */
    public CompletionStage<WorldArchiveConfig> saveSettings(UnaryOperator<WorldArchiveConfig> edits) {
        Objects.requireNonNull(edits, "edits");
        return AsyncTasks.supplyChecked(executor, () -> refresh(edits));
    }

    /**
     * Adds a world the game opened, or moves its settings to the folder it is in now. The folder
     * counts as a world at once, so no destination can be saved inside it from then on.
     */
    public CompletionStage<WorldArchiveConfig> registerWorld(WorldId worldId, Path worldDirectory) {
        DiscoveredWorld world = new DiscoveredWorld(worldDirectory, worldId);
        remember(List.of(world.path()));
        return update(config -> config.withWorlds(
                WorldConfigReconciler.reconcile(config.worlds(), List.of(world)).worlds()));
    }

    /**
     * Gives each listed world the remote its imported backups came from, when the world has no
     * remote yet. A world that already has one keeps it, so importing from a copy on a USB drive
     * does not redirect later uploads.
     */
    public CompletionStage<WorldArchiveConfig> connectWorldRemotes(Map<WorldId, String> connections) {
        Map<WorldId, String> requested = Map.copyOf(connections);
        if (requested.isEmpty()) {
            return CompletableFuture.completedFuture(current);
        }
        return update(config -> config.withWorlds(config.worlds().stream()
                .map(world -> world.remoteUrl().isEmpty() && requested.containsKey(world.worldId())
                        ? world.withRemoteUrl(Optional.of(requested.get(world.worldId())))
                        : world)
                .toList()));
    }

    /**
     * Starts over with product defaults and the worlds in the saves folder. The unreadable settings
     * file is kept as {@code worldarchive.json.unreadable-<UTC time>}. A file that reads again, as
     * after another game window fixed it, is loaded instead, and nothing is reset.
     *
     * @return where the unreadable file was kept; empty when nothing was reset
     */
    public CompletionStage<Optional<Path>> reset() {
        return AsyncTasks.supplyChecked(executor, this::resetNow);
    }

    /**
     * Checks a draft against every known world folder. Only the newest draft waiting is checked:
     * one handed in while an older one waits replaces it, and the older result is cancelled. The
     * draft must not change afterwards, so hand in a {@link SettingsDraft#copy}.
     */
    public CompletableFuture<SettingsValidation> validate(SettingsDraft draft) {
        PendingValidation next = new PendingValidation(
                Objects.requireNonNull(draft, "draft"),
                new CompletableFuture<>());
        PendingValidation replaced = pendingValidation.getAndSet(next);
        if (replaced != null) {
            replaced.result().cancel(false);
        }
        try {
            executor.execute(this::validatePending);
        } catch (RejectedExecutionException exception) {
            pendingValidation.compareAndSet(next, null);
            next.result().completeExceptionally(exception);
        }
        return next.result();
    }

    private void validatePending() {
        PendingValidation pending = pendingValidation.getAndSet(null);
        if (pending == null) {
            return;
        }
        try {
            pending.result().complete(pending.draft().validate(List.copyOf(knownWorlds)));
        } catch (Throwable failure) {
            pending.result().completeExceptionally(failure);
        }
    }

    private WorldArchiveConfig loadNow() throws IOException {
        WorldArchiveConfig loaded;
        try {
            loaded = store.load();
        } catch (UnreadableConfigurationException exception) {
            unreadable = exception;
            throw exception;
        }
        unreadable = null;
        current = loaded;
        try {
            refresh(UnaryOperator.identity());
        } catch (IOException | RuntimeException exception) {
            LOGGER.warn("WorldArchive could not save its list of worlds: {}",
                    SafeText.from(exception, "the list could not be saved", MESSAGE_LIMIT));
        }
        if (current == loaded) {
            // The refresh saved nothing new, so it published nothing; a retried load must still reach listeners.
            publish(loaded);
        }
        return current;
    }

    /** Applies edits and brings the world list in step with the saves folder, as one update. */
    private WorldArchiveConfig refresh(UnaryOperator<WorldArchiveConfig> edits) throws IOException {
        requireReadable();
        Scan scan = scan(current);
        List<WorldNotice> reconciled = new ArrayList<>();
        try {
            return updateNow(config -> {
                WorldArchiveConfig edited = edits.apply(config);
                WorldReconciliation reconciliation = WorldConfigReconciler.reconcile(edited.worlds(), scan.worlds());
                reconciled.addAll(reconciliation.notices());
                return edited.withWorlds(reconciliation.worlds());
            });
        } finally {
            notices = Stream.concat(scan.notices().stream(), reconciled.stream()).toList();
        }
    }

    private WorldArchiveConfig updateNow(UnaryOperator<WorldArchiveConfig> change) throws IOException {
        requireReadable();
        List<Runnable> releases = new ArrayList<>();
        try {
            WorldArchiveConfig saved = store.update(fresh -> {
                WorldArchiveConfig changed = change.apply(fresh);
                if (!changed.equals(fresh)) {
                    releases.addAll(acquireGuards(changed));
                }
                return changed;
            }, List.copyOf(knownWorlds));
            if (!saved.equals(current)) {
                createNewFolders(current, saved);
                current = saved;
                publish(saved);
            }
            return saved;
        } catch (UnreadableConfigurationException exception) {
            unreadable = exception;
            throw exception;
        } finally {
            release(releases);
        }
    }

    /** Nothing is written while the file cannot be read; only {@link #reset} replaces it. */
    private void requireReadable() throws UnreadableConfigurationException {
        UnreadableConfigurationException failure = unreadable;
        if (failure != null) {
            throw new UnreadableConfigurationException(failure.file(), failure.getCause());
        }
    }

    private Optional<Path> resetNow() throws IOException {
        Scan scan = scan(WorldArchiveConfig.defaults());
        List<WorldNotice> reconciled = new ArrayList<>();
        List<Runnable> releases = new ArrayList<>();
        try {
            Optional<WorldArchiveConfigStore.Reset> reset = store.reset(defaults -> {
                WorldReconciliation reconciliation = WorldConfigReconciler.reconcile(List.of(), scan.worlds());
                reconciled.addAll(reconciliation.notices());
                WorldArchiveConfig fresh = defaults.withWorlds(reconciliation.worlds());
                releases.addAll(acquireGuards(fresh));
                return fresh;
            }, List.copyOf(knownWorlds), clock.instant());
            if (reset.isEmpty()) {
                loadNow();
                return Optional.empty();
            }
            unreadable = null;
            current = reset.get().config();
            notices = Stream.concat(scan.notices().stream(), reconciled.stream()).toList();
            publish(reset.get().config());
            return Optional.of(reset.get().keptCopy());
        } finally {
            release(releases);
        }
    }

    /**
     * Reads the identity of each world folder in the saves folder. A folder that copies a world
     * still found where {@code reference} expects it gets its own identity first.
     */
    private Scan scan(WorldArchiveConfig reference) throws IOException {
        List<Path> folders = WorldFolderDiscovery.discover(savesDirectory);
        remember(folders);
        List<DiscoveredWorld> found = new ArrayList<>(folders.size());
        List<WorldNotice> scanNotices = new ArrayList<>();
        for (Path folder : folders) {
            try {
                found.add(new DiscoveredWorld(folder, identities.loadOrCreate(folder)));
            } catch (IOException exception) {
                scanNotices.add(new WorldNotice.IdentityUnreadable(folder, reason(exception)));
            }
        }
        for (WorldConfigReconciler.Copy copy : WorldConfigReconciler.copies(reference.worlds(), found)) {
            int index = found.indexOf(new DiscoveredWorld(copy.folder(), copy.copiedId()));
            try {
                WorldId own = identities.giveCopyItsOwnIdentity(copy.folder(), copy.copiedId()).worldId();
                found.set(index, new DiscoveredWorld(copy.folder(), own));
                scanNotices.add(new WorldNotice.CopyGotOwnIdentity(copy.folder(), copy.original()));
            } catch (IOException exception) {
                found.remove(index);
                scanNotices.add(new WorldNotice.IdentityUnreadable(copy.folder(), reason(exception)));
            }
        }
        return new Scan(found, scanNotices);
    }

    /**
     * Creates each backup folder that the new settings name for the first time. Backups never
     * create a folder the player chose, because a missing one means its drive is away; a folder
     * that cannot be created now is only logged, and the settings show it as unavailable.
     */
    private static void createNewFolders(WorldArchiveConfig before, WorldArchiveConfig after) {
        Set<Path> added = new LinkedHashSet<>(after.destinations());
        added.removeAll(before.destinations());
        for (Path folder : added) {
            try {
                Files.createDirectories(folder);
            } catch (IOException | SecurityException failure) {
                LOGGER.warn("WorldArchive could not create the backup folder {}: {}", folder,
                        SafeText.from(failure, "no reason was given", MESSAGE_LIMIT));
            }
        }
    }

    private void remember(Collection<Path> worldFolders) {
        worldFolders.forEach(folder -> knownWorlds.add(folder.toAbsolutePath().normalize()));
    }

    private List<Runnable> acquireGuards(WorldArchiveConfig config) {
        List<Runnable> releases = new ArrayList<>(guards.size());
        try {
            for (Function<WorldArchiveConfig, Runnable> guard : guards) {
                releases.add(Objects.requireNonNull(guard.apply(config), "configuration guard release"));
            }
            return releases;
        } catch (RuntimeException | Error exception) {
            release(releases);
            throw exception;
        }
    }

    private static void release(List<Runnable> releases) {
        for (int index = releases.size() - 1; index >= 0; index--) {
            releases.get(index).run();
        }
    }

    private void publish(WorldArchiveConfig config) {
        for (Consumer<WorldArchiveConfig> listener : listeners) {
            try {
                listener.accept(config);
            } catch (RuntimeException exception) {
                LOGGER.warn("WorldArchive configuration listener failed: {}",
                        SafeText.from(exception, "Configuration listener failed", MESSAGE_LIMIT));
            }
        }
    }

    private static String reason(IOException exception) {
        return SafeText.from(exception, "the identity file could not be read", MESSAGE_LIMIT);
    }

    /** The world folders a scan found, with the identity each carries, and what to tell the player. */
    private record Scan(List<DiscoveredWorld> worlds, List<WorldNotice> notices) {
    }

    /** A draft waiting to be checked, and where its result goes. */
    private record PendingValidation(SettingsDraft draft, CompletableFuture<SettingsValidation> result) {
    }
}
