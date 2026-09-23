package dev.ishaanko.worldarchive.ui.model;

import dev.ishaanko.worldarchive.model.SafeText;
import dev.ishaanko.worldarchive.model.WorldId;
import java.nio.file.Path;
import java.util.Objects;

/**
 * A world whose backups the screens show. {@code worldDirectory} is the world's real folder, with
 * any link above or at it resolved: the settings and the capture key the world by it.
 * {@code worldsDirectory} is the saves folder a restore creates the new world in, and
 * {@code storageName} the world's folder name as the game lists it; a world reached through a
 * link keeps the name of the link.
 */
public record BackupWorldContext(
        WorldId worldId,
        Path worldDirectory,
        Path worldsDirectory,
        String storageName,
        String displayName) {
    public BackupWorldContext {
        Objects.requireNonNull(worldId, "worldId");
        worldDirectory = Objects.requireNonNull(worldDirectory, "worldDirectory").toAbsolutePath().normalize();
        worldsDirectory = Objects.requireNonNull(worldsDirectory, "worldsDirectory").toAbsolutePath().normalize();
        SafeText.require(storageName, "storageName", BackupWorldSelection.MAXIMUM_NAME_LENGTH);
        SafeText.require(displayName, "displayName", BackupWorldSelection.MAXIMUM_NAME_LENGTH);
    }

    /** True when {@code selection} names this world the way the game lists it. */
    public boolean matches(BackupWorldSelection selection) {
        Objects.requireNonNull(selection, "selection");
        return worldsDirectory.equals(selection.worldsDirectory()) && storageName.equals(selection.storageName());
    }
}
