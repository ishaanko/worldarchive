package dev.ishaanko.worldarchive.config;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;

/** Who chose a backup folder, which decides what a missing one means. */
public enum FolderOrigin {
    /** WorldArchive's default folder in the game directory, created when a backup first needs it. */
    DEFAULT,
    /**
     * A folder the player chose, often on another drive. When it is missing, its drive is away,
     * so a backup there fails and nothing creates the folder in its place.
     */
    CHOSEN;

    /**
     * Whether a listing of {@code folder} sees every backup it may hold: the folder exists, or it
     * is a default folder that no backup created yet. A missing chosen folder, or a link whose
     * target is gone, may hold backups on a drive that is away.
     */
    public boolean listable(Path folder) {
        return Files.isDirectory(folder) || this == DEFAULT && Files.notExists(folder, LinkOption.NOFOLLOW_LINKS);
    }
}
