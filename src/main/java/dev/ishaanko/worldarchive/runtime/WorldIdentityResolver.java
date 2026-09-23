package dev.ishaanko.worldarchive.runtime;

import dev.ishaanko.worldarchive.config.WorldArchiveConfig;
import dev.ishaanko.worldarchive.config.WorldConfig;
import dev.ishaanko.worldarchive.config.WorldIdentityStore;
import dev.ishaanko.worldarchive.model.SafeText;
import dev.ishaanko.worldarchive.model.WorldId;
import dev.ishaanko.worldarchive.settings.WorldFolderDiscovery;
import dev.ishaanko.worldarchive.ui.model.BackupWorldContext;
import dev.ishaanko.worldarchive.ui.model.BackupWorldSelection;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Finds the WorldArchive identity of a world the game lists or opens, and keeps one folder per
 * identity for the game session. A world is keyed by its real folder: a saves folder or a world
 * folder that is a link or junction is followed, the way the settings list worlds; links inside a
 * world stay refused by the capture. A folder that copies a world still found in its own folder is
 * refused, and the settings scan then gives the copy an identity of its own.
 *
 * <p>The claims start from the settings on each change ({@link #configure}), and the settings
 * always win. Resolving reads the disk on the caller's thread, so call it from a worker; the
 * checks that the render thread makes read memory only.</p>
 */
public final class WorldIdentityResolver {
    private static final int MESSAGE_LIMIT = 300;

    private final Path savesDirectory;

    private final WorldIdentityStore identities;

    private final Listener listener;

    // Guarded by this. Each world's folder and each folder's world, the two kept in step.
    private final Map<WorldId, Path> pathsByWorld = new HashMap<>();

    private final Map<Path, WorldId> worldsByPath = new HashMap<>();

    private Map<WorldId, Path> configured = Map.of();

    private WorldArchiveConfig settings;

    /** Changes with the settings and with every claim, so a storage check knows when it is out of date. */
    private long version;

    private Optional<String> storageIssue = Optional.empty();

    /**
     * @param savesDirectory the saves folder as the game names it
     * @param listener learns what the settings should record
     */
    public WorldIdentityResolver(Path savesDirectory, WorldIdentityStore identities, Listener listener) {
        this.savesDirectory = Objects.requireNonNull(savesDirectory, "savesDirectory").toAbsolutePath().normalize();
        this.identities = Objects.requireNonNull(identities, "identities");
        this.listener = Objects.requireNonNull(listener, "listener");
    }

    /** What the settings should learn about the worlds the resolver meets. */
    public interface Listener {
        /** A world was found in a folder the settings do not list for it. */
        void found(WorldId worldId, Path worldDirectory);

        /** A folder copies a world that is still in its own folder; a settings scan gives the copy its own identity. */
        void copyFound(Path copy);
    }

    /** The outcome of {@link #resolve}: the world, or why WorldArchive does not back it up. */
    public sealed interface Resolution {
        /** The world and its identity. */
        record Resolved(BackupWorldContext world) implements Resolution {
            public Resolved {
                Objects.requireNonNull(world, "world");
            }
        }

        /** A reason the player can read; asking again gives the same answer until the settings change. */
        record Refused(String reason) implements Resolution {
            public Refused {
                Objects.requireNonNull(reason, "reason");
            }
        }
    }

    /** True when the game keeps the world directly in the saves folder, where WorldArchive backs worlds up. */
    public boolean inSaves(BackupWorldSelection selection) {
        return WorldFolderDiscovery.isDirectChild(savesDirectory, selection.worldDirectory());
    }

    /**
     * Finds the identity of the selected world, creating one for a world that has none, and
     * claims its real folder for it.
     *
     * @throws IOException when the world's identity file cannot be read or written
     */
    public Resolution resolve(BackupWorldSelection selection) throws IOException {
        if (!inSaves(selection)) {
            return new Resolution.Refused("WorldArchive backs up only the worlds in the saves folder.");
        }
        Optional<Path> world = WorldFolderDiscovery.realWorld(selection.worldDirectory());
        if (world.isEmpty()) {
            return new Resolution.Refused("This folder has no level.dat, so it is not a world WorldArchive can back up.");
        }
        WorldId worldId = identities.loadOrCreate(world.get());
        if (!register(worldId, world.get())) {
            listener.copyFound(world.get());
            return new Resolution.Refused("This world is a copy of another world. WorldArchive gives the copy its"
                    + " own backups in a moment; try again then.");
        }
        return new Resolution.Resolved(new BackupWorldContext(
                worldId, world.get(), selection.worldsDirectory(), selection.storageName(), selection.displayName()));
    }

    /**
     * Claims {@code worldDirectory}, which carries {@code worldId}, for that identity. The claim is
     * refused while another folder still carries the identity: this folder is then a copy. A claim
     * whose folder is gone, or carries another identity now, is stale and released.
     */
    public boolean register(WorldId worldId, Path worldDirectory) {
        Path world = Objects.requireNonNull(worldDirectory, "worldDirectory").toAbsolutePath().normalize();
        boolean unlisted;
        boolean moved;
        synchronized (this) {
            Path claimed = pathsByWorld.get(Objects.requireNonNull(worldId, "worldId"));
            if (claimed != null && !claimed.equals(world) && carries(claimed, worldId)) {
                return false;
            }
            long before = version;
            claim(worldId, world);
            moved = version != before;
            unlisted = !world.equals(configured.get(worldId));
        }
        if (moved) {
            checkStorage();
        }
        if (unlisted) {
            listener.found(worldId, world);
        }
        return true;
    }

    /** Starts the claims from the settings, which win over every earlier claim, and checks the backup folders. */
    public void configure(WorldArchiveConfig config) {
        synchronized (this) {
            settings = config;
            configured = config.worlds().stream().collect(Collectors.toUnmodifiableMap(
                    WorldConfig::worldId, world -> world.path().toAbsolutePath().normalize()));
            configured.forEach(this::claim);
            version++;
        }
        checkStorage();
    }

    /** True when the world's folder is the one claimed for its identity. */
    public synchronized boolean isRegistered(WorldId worldId, Path worldDirectory) {
        Path world = worldDirectory.toAbsolutePath().normalize();
        return world.equals(pathsByWorld.get(worldId)) && worldId.equals(worldsByPath.get(world));
    }

    /**
     * Why backups must stop because a backup folder is inside a known world; empty when none is.
     * The answer comes from the last check, which runs when the settings or the known worlds change.
     */
    public synchronized Optional<String> storageIssue() {
        return storageIssue;
    }

    /** Checks the backup folders of the settings against every known world; the disk is read outside the lock. */
    private void checkStorage() {
        WorldArchiveConfig checked;
        List<Path> worlds;
        long checkedAt;
        synchronized (this) {
            if (settings == null) {
                return;
            }
            checked = settings;
            worlds = List.copyOf(worldsByPath.keySet());
            checkedAt = version;
        }
        Optional<String> issue;
        try {
            checked.validateDestinations(worlds);
            issue = Optional.empty();
        } catch (IOException failure) {
            issue = Optional.of("Backups are paused: " + SafeText.from(failure, "a backup folder is inside a world",
                    MESSAGE_LIMIT) + ". Choose another backup folder in WorldArchive settings.");
        }
        synchronized (this) {
            if (version == checkedAt) {
                storageIssue = issue;
            }
        }
    }

    private void claim(WorldId worldId, Path world) {
        Path previousPath = pathsByWorld.put(worldId, world);
        if (previousPath != null && !previousPath.equals(world)) {
            worldsByPath.remove(previousPath, worldId);
        }
        WorldId previousWorld = worldsByPath.put(world, worldId);
        if (previousWorld != null && !previousWorld.equals(worldId)) {
            pathsByWorld.remove(previousWorld, world);
        }
        if (!world.equals(previousPath)) {
            version++;
        }
    }

    /** True unless the folder is gone or carries another identity; a folder that cannot be read still counts. */
    private boolean carries(Path folder, WorldId worldId) {
        if (!Files.isDirectory(folder)) {
            return false;
        }
        try {
            return identities.loadExisting(folder)
                    .map(identity -> identity.worldId().equals(worldId))
                    .orElse(false);
        } catch (IOException exception) {
            return true;
        }
    }
}
