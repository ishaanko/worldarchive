package dev.ishaanko.worldarchive.ui.model;

import dev.ishaanko.worldarchive.model.SafeText;
import java.nio.file.Path;
import java.util.Objects;

/**
 * A world as the game lists it, before WorldArchive knows its identity: its folder in the saves
 * folder, the saves folder, the folder name, and the name the player gave the world. The paths are
 * the ones the game uses; links in them are not resolved.
 */
public record BackupWorldSelection(
        Path worldDirectory,
        Path worldsDirectory,
        String storageName,
        String displayName) {
    /** The longest folder or world name, in UTF-16 units. */
    public static final int MAXIMUM_NAME_LENGTH = 255;

    public BackupWorldSelection {
        worldDirectory = Objects.requireNonNull(worldDirectory, "worldDirectory").toAbsolutePath().normalize();
        worldsDirectory = Objects.requireNonNull(worldsDirectory, "worldsDirectory").toAbsolutePath().normalize();
        SafeText.require(storageName, "storageName", MAXIMUM_NAME_LENGTH);
        SafeText.require(displayName, "displayName", MAXIMUM_NAME_LENGTH);
        if (!worldsDirectory.equals(worldDirectory.getParent())) {
            throw new IllegalArgumentException("The selected world must be a direct child of the worlds directory");
        }
        Path fileName = worldDirectory.getFileName();
        if (fileName == null || !storageName.equals(fileName.toString())) {
            throw new IllegalArgumentException("The storage name does not match the selected world directory");
        }
    }
}
