package dev.ishaanko.worldarchive.model;

/** Last known optional-remote synchronization state for a destination artifact. */
public enum SyncStatus {
    NOT_CONFIGURED,
    /** No code sets this now; it stays because backup lists written by older versions can hold it. */
    NOT_SYNCED,
    PENDING,
    SYNCED,
    FAILED
}
