package dev.ishaanko.worldarchive.core;

/**
 * Point-of-no-return tracking for a create operation's cancellation window. A backup can be
 * cancelled while it captures the world and while its destinations write; once the result is
 * being recorded in the catalog it is committed.
 */
enum CancellationState {
    CANCELLABLE,
    CANCELLATION_REQUESTED,
    COMMITTING
}
