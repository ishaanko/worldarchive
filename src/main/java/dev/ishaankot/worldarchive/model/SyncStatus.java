package dev.ishaankot.worldarchive.model;

/** Last known optional-remote synchronization state for a destination artifact. */
public enum SyncStatus {
    NOT_CONFIGURED,
    NOT_SYNCED,
    PENDING,
    SYNCED,
    FAILED
}
