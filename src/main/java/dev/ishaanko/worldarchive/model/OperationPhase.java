package dev.ishaanko.worldarchive.model;

/**
 * The phase of an operation's progress. Phases usually follow this order, but not always: a
 * backup that waits for another backup of its world reports QUEUED after its capture.
 */
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
