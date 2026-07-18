package dev.ishaankot.worldarchive.core;

import dev.ishaankot.worldarchive.model.BackupTrigger;
import dev.ishaankot.worldarchive.model.SensitiveDataRedactor;
import dev.ishaankot.worldarchive.model.WorldId;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

/** A request to capture the current durable state of a world. */
public record CreateBackupRequest(
        WorldId worldId,
        Path worldDirectory,
        String worldName,
        Optional<String> label,
        BackupTrigger trigger) {
    public CreateBackupRequest {
        Objects.requireNonNull(worldId, "worldId");
        worldDirectory = Objects.requireNonNull(worldDirectory, "worldDirectory")
                .toAbsolutePath()
                .normalize();
        worldName = requireText(worldName, "worldName", 255);
        label = Objects.requireNonNull(label, "label")
                .map(SensitiveDataRedactor::redact)
                .map(value -> requireText(value, "label", 128));
        Objects.requireNonNull(trigger, "trigger");
    }

    /** Compatibility constructor for unlabeled backup requests. */
    public CreateBackupRequest(
            WorldId worldId,
            Path worldDirectory,
            String worldName,
            BackupTrigger trigger) {
        this(worldId, worldDirectory, worldName, Optional.empty(), trigger);
    }

    private static String requireText(String value, String name, int maximumLength) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()
                || value.length() > maximumLength
                || value.chars().anyMatch(character -> Character.isISOControl(character))) {
            throw new IllegalArgumentException(name + " is blank, too long, or contains control characters");
        }
        return value;
    }
}
