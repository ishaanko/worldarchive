package dev.ishaanko.worldarchive.model;

/** User-visible operation kinds reported by the service layer. */
public enum BackupOperation {
    CREATE,
    VERIFY,
    SYNC,
    DELETE,
    RESTORE
}
