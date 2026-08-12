package dev.ishaanko.worldarchive.core;

/** User-visible operation kinds reported by the service layer. */
public enum BackupOperation {
    CREATE,
    VERIFY,
    SYNC,
    DELETE,
    RESTORE
}
