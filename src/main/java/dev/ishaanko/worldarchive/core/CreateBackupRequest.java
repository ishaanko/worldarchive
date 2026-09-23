package dev.ishaanko.worldarchive.core;

import dev.ishaanko.worldarchive.model.BackupManifest;
import dev.ishaanko.worldarchive.model.BackupTrigger;
import dev.ishaanko.worldarchive.model.SafeText;
import dev.ishaanko.worldarchive.model.WorldId;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

/** A request to capture the current durable state of a world; the label is kept exactly as typed. */
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
        worldName = SafeText.require(worldName, "worldName", 255);
        label = Objects.requireNonNull(label, "label")
                .map(value -> SafeText.require(value, "label", BackupManifest.MAXIMUM_LABEL_LENGTH));
        Objects.requireNonNull(trigger, "trigger");
    }
}
