package dev.ishaankot.worldarchive.settings;

import dev.ishaankot.worldarchive.model.SensitiveDataRedactor;
import dev.ishaankot.worldarchive.model.WorldId;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

/** One off-thread world identity discovery outcome. */
public record DiscoveredWorld(Path path, Optional<WorldId> worldId, Optional<String> error) {
    public DiscoveredWorld {
        path = Objects.requireNonNull(path, "path").toAbsolutePath().normalize();
        worldId = Objects.requireNonNull(worldId, "worldId");
        error = Objects.requireNonNull(error, "error").map(DiscoveredWorld::safeError);
        if (worldId.isPresent() == error.isPresent()) {
            throw new IllegalArgumentException("A discovered world must contain either an identity or an error");
        }
    }

    public static DiscoveredWorld success(Path path, WorldId worldId) {
        return new DiscoveredWorld(path, Optional.of(worldId), Optional.empty());
    }

    public static DiscoveredWorld failure(Path path, String error) {
        return new DiscoveredWorld(path, Optional.empty(), Optional.of(error));
    }

    private static String safeError(String error) {
        String redacted = SensitiveDataRedactor.redact(Objects.requireNonNull(error, "error")).strip();
        if (redacted.isEmpty()
                || redacted.length() > 512
                || redacted.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("World discovery error is invalid");
        }
        return redacted;
    }
}
