package dev.ishaankot.worldarchive.command.model;

/** Result of resolving a full or abbreviated backup ID. */
public enum BackupIdResolutionStatus {
    EXACT,
    UNIQUE_PREFIX,
    AMBIGUOUS,
    NOT_FOUND,
    INVALID
}
