package dev.ishaanko.worldarchive.model;

/** Terminal aggregate status of a logical backup. */
public enum BackupStatus {
    SUCCESS,
    PARTIAL_SUCCESS,
    FAILED,
    SKIPPED
}
