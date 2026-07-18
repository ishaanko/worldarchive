package dev.ishaankot.worldarchive.ui;

import dev.ishaankot.worldarchive.model.WorldId;
import java.nio.file.Path;
import java.util.Objects;

/** Persistent identity and filesystem context for one backup browser. */
public record BackupWorldContext(
        WorldId worldId,
        Path worldDirectory,
        Path worldsDirectory,
        String storageName,
        String displayName) {
    public BackupWorldContext {
        Objects.requireNonNull(worldId, "worldId");
        BackupWorldSelection selection = new BackupWorldSelection(
                worldDirectory,
                worldsDirectory,
                storageName,
                displayName);
        worldDirectory = selection.worldDirectory();
        worldsDirectory = selection.worldsDirectory();
        storageName = selection.storageName();
        displayName = selection.displayName();
    }

    public BackupWorldContext(WorldId worldId, BackupWorldSelection selection) {
        this(
                worldId,
                Objects.requireNonNull(selection, "selection").worldDirectory(),
                selection.worldsDirectory(),
                selection.storageName(),
                selection.displayName());
    }

    /** Guards UI restore requests from ever resolving to the selected live world directory. */
    public boolean isDifferentRestoreDirectory(String restoredWorldName) {
        Objects.requireNonNull(restoredWorldName, "restoredWorldName");
        return !worldDirectory.equals(worldsDirectory.resolve(restoredWorldName).toAbsolutePath().normalize());
    }

    public boolean matches(BackupWorldSelection selection) {
        Objects.requireNonNull(selection, "selection");
        return worldDirectory.equals(selection.worldDirectory())
                && worldsDirectory.equals(selection.worldsDirectory())
                && storageName.equals(selection.storageName());
    }
}
