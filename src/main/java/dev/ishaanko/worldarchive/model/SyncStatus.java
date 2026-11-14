package dev.ishaanko.worldarchive.model;

/** Last known optional-remote synchronization state for a destination artifact. */
public enum SyncStatus {
    NOT_CONFIGURED,
    /** A complete local copy that the upload setting kept on this computer; Sync uploads it. */
    NOT_SYNCED,
    PENDING,
    SYNCED,
    FAILED
}
