package dev.ishaanko.worldarchive.core;

/** Point-of-no-return tracking for a create operation's cancellation window. */
enum CancellationState {
    /** Nothing durable exists yet; a cancel stops the operation outright. */
    CANCELLABLE,
    /** A cancel was accepted; destination work stops and published artifacts roll back. */
    CANCELLATION_REQUESTED,
    /** Destinations are writing; a cancel is still accepted but must roll back. */
    COMMITTING,
    /** Finalization owns the outcome; a cancel is rejected from here on. */
    COMMITTED
}
