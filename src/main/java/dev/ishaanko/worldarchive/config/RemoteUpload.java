package dev.ishaanko.worldarchive.config;

/**
 * When a Git backup of a world with a remote uploads on its own. A backup that does not upload is
 * a complete local backup, and Sync uploads it later.
 */
public enum RemoteUpload {
    /** Every backup uploads as soon as it is written. */
    EVERY_BACKUP,
    /** A backup uploads only when nothing of its world was uploaded yet on the same local day. */
    ONCE_A_DAY,
    /** Backups upload only when the player syncs them. */
    ONLY_WITH_SYNC
}
