package dev.ishaankot.worldarchive.core;

/** Point-of-no-return tracking for a create operation's cancellation window. */
enum CancellationState {
    CANCELLABLE,
    CANCELLATION_REQUESTED,
    COMMITTING
}
