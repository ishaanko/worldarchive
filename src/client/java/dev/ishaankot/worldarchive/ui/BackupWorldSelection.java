package dev.ishaankot.worldarchive.ui;

import java.nio.file.Path;
import java.util.Objects;

/** A validated Select World entry before WorldArchive resolves its persistent world identity. */
public record BackupWorldSelection(
        Path worldDirectory,
        Path worldsDirectory,
        String storageName,
        String displayName) {
    public BackupWorldSelection {
        worldDirectory = normalize(worldDirectory, "worldDirectory");
        worldsDirectory = normalize(worldsDirectory, "worldsDirectory");
        storageName = requireText(storageName, "storageName");
        displayName = requireText(displayName, "displayName");
        if (!worldsDirectory.equals(worldDirectory.getParent())) {
            throw new IllegalArgumentException("The selected world must be a direct child of the worlds directory");
        }
        Path fileName = worldDirectory.getFileName();
        if (fileName == null || !storageName.equals(fileName.toString())) {
            throw new IllegalArgumentException("The storage name does not match the selected world directory");
        }
    }

    private static Path normalize(Path path, String name) {
        return Objects.requireNonNull(path, name).toAbsolutePath().normalize();
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()
                || value.length() > 255
                || value.chars().anyMatch(character -> Character.isISOControl(character))) {
            throw new IllegalArgumentException(name + " is blank, too long, or contains control characters");
        }
        return value;
    }
}
