package dev.ishaankot.worldarchive.settings;

import dev.ishaankot.worldarchive.config.WorldConfig;
import dev.ishaankot.worldarchive.model.WorldId;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Preserves world enablement while merging newly discovered stable identities. */
public final class WorldConfigReconciler {
    private WorldConfigReconciler() {
    }

    public static WorldReconciliation reconcile(
            List<WorldConfig> existing,
            List<DiscoveredWorld> discovered) {
        Objects.requireNonNull(existing, "existing");
        Objects.requireNonNull(discovered, "discovered");
        Map<WorldId, WorldConfig> existingById = new HashMap<>();
        Map<Path, WorldConfig> existingByPath = new HashMap<>();
        for (WorldConfig world : existing) {
            existingById.put(world.worldId(), world);
            existingByPath.put(world.path().toAbsolutePath().normalize(), world);
        }

        List<WorldConfig> result = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        Set<WorldId> usedIds = new HashSet<>();
        Set<Path> usedPaths = new HashSet<>();
        for (DiscoveredWorld candidate : discovered) {
            Path path = candidate.path();
            if (candidate.error().isPresent()) {
                errors.add(candidate.error().orElseThrow());
                continue;
            }
            WorldId worldId = candidate.worldId().orElseThrow();
            if (usedPaths.contains(path)) {
                errors.add("Two WorldArchive world identities use the same save folder");
                continue;
            }
            if (usedIds.contains(worldId)) {
                errors.add("Two save folders share one WorldArchive world identity");
                continue;
            }
            WorldConfig stored = existingById.getOrDefault(worldId, existingByPath.get(path));
            boolean enabled = stored == null || stored.enabled();
            result.add(new WorldConfig(
                    worldId,
                    enabled,
                    path,
                    stored == null ? Optional.empty() : stored.remoteUrl()));
            usedIds.add(worldId);
            usedPaths.add(path);
        }

        for (WorldConfig stored : existing) {
            Path path = stored.path().toAbsolutePath().normalize();
            if (!usedIds.contains(stored.worldId()) && !usedPaths.contains(path)) {
                result.add(stored);
                usedIds.add(stored.worldId());
                usedPaths.add(path);
            }
        }
        return new WorldReconciliation(result, errors);
    }
}
