package dev.ishaanko.worldarchive.core;

/** Monotonic phases used for concise operation progress. */
public enum OperationPhase {
    QUEUED,
    PREPARING,
    READING,
    WRITING,
    VERIFYING,
    PUBLISHING,
    COMPLETE,
    FAILED
}
