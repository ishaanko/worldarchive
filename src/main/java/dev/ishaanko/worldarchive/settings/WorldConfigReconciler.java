package dev.ishaanko.worldarchive.settings;

import dev.ishaanko.worldarchive.config.WorldConfig;
import dev.ishaanko.worldarchive.model.WorldId;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Matches world folders found on disk to stored world settings by the identity each folder
 * carries, so a world keeps its settings when its folder is renamed or moved. It reads nothing
 * from disk; the settings service scans the folders and calls it.
 */
public final class WorldConfigReconciler {
    private WorldConfigReconciler() {
    }

    /**
     * Folders that carry the identity of another folder found where the settings expect that
     * identity: copies made in a file manager. The caller gives each its own identity before
     * {@link #reconcile}, so a copy never takes over the original's settings or backup history.
     */
    public static List<Copy> copies(List<WorldConfig> stored, List<DiscoveredWorld> found) {
        Map<WorldId, WorldConfig> storedById = byId(stored);
        List<Copy> copies = new ArrayList<>();
        for (Map.Entry<WorldId, List<Path>> group : groupById(found).entrySet()) {
            WorldConfig known = storedById.get(group.getKey());
            List<Path> folders = group.getValue();
            if (folders.size() > 1 && known != null && folders.contains(known.path())) {
                folders.stream()
                        .filter(folder -> !folder.equals(known.path()))
                        .forEach(folder -> copies.add(new Copy(folder, group.getKey(), known.path())));
            }
        }
        return List.copyOf(copies);
    }

    /**
     * The world settings after a scan. A folder with a known identity keeps that world's
     * settings, at its new path when it moved; a new identity gets default settings. Stored
     * worlds whose folder was not found stay. When several folders carry one identity, only the
     * folder the settings expect keeps it; when none of them is that folder, none is added and
     * the player is told.
     */
    public static WorldReconciliation reconcile(List<WorldConfig> stored, List<DiscoveredWorld> found) {
        Map<WorldId, WorldConfig> storedById = byId(stored);
        Set<Path> storedPaths = stored.stream().map(WorldConfig::path).collect(Collectors.toSet());
        List<WorldConfig> worlds = new ArrayList<>();
        List<WorldNotice> notices = new ArrayList<>();
        for (Map.Entry<WorldId, List<Path>> group : groupById(found).entrySet()) {
            WorldConfig known = storedById.get(group.getKey());
            Optional<Path> owner = owner(group.getValue(), known);
            if (owner.isEmpty()) {
                notices.add(new WorldNotice.SharedIdentity(group.getValue()));
            } else if (known != null) {
                worlds.add(known.withPath(owner.get()));
            } else {
                worlds.add(WorldConfig.defaults(group.getKey(), owner.get()));
                if (storedPaths.contains(owner.get())) {
                    notices.add(new WorldNotice.IdentityReplaced(owner.get()));
                }
            }
        }
        keepUnmatched(stored, worlds);
        return new WorldReconciliation(worlds, notices);
    }

    /** The folder that keeps an identity: the only one, or the one the settings expect. */
    private static Optional<Path> owner(List<Path> folders, WorldConfig known) {
        if (folders.size() == 1) {
            return Optional.of(folders.getFirst());
        }
        return known != null && folders.contains(known.path()) ? Optional.of(known.path()) : Optional.empty();
    }

    /** Adds the stored worlds whose identity and folder no scanned folder took. */
    private static void keepUnmatched(List<WorldConfig> stored, List<WorldConfig> worlds) {
        Set<WorldId> usedIds = new HashSet<>();
        Set<Path> usedPaths = new HashSet<>();
        worlds.forEach(world -> {
            usedIds.add(world.worldId());
            usedPaths.add(world.path());
        });
        for (WorldConfig world : stored) {
            if (!usedIds.contains(world.worldId()) && !usedPaths.contains(world.path())) {
                worlds.add(world);
            }
        }
    }

    private static Map<WorldId, WorldConfig> byId(List<WorldConfig> stored) {
        return stored.stream().collect(Collectors.toMap(WorldConfig::worldId, Function.identity()));
    }

    private static Map<WorldId, List<Path>> groupById(List<DiscoveredWorld> found) {
        Map<WorldId, List<Path>> groups = new LinkedHashMap<>();
        for (DiscoveredWorld world : found) {
            groups.computeIfAbsent(world.worldId(), ignored -> new ArrayList<>()).add(world.path());
        }
        return groups;
    }

    /** A folder copied from {@code original}, still carrying its identity {@code copiedId}. */
    public record Copy(Path folder, WorldId copiedId, Path original) {
        public Copy {
            Objects.requireNonNull(folder, "folder");
            Objects.requireNonNull(copiedId, "copiedId");
            Objects.requireNonNull(original, "original");
        }
    }
}
