package dev.ishaanko.worldarchive.core;

import dev.ishaanko.worldarchive.model.WorldId;

/**
 * Lets one operation at a time work on a world. The coordinator uses one gate so that only one
 * capture copies a world at a time, and a second gate, shared with restore, delete and cleanup,
 * for the storage work of a world. Neither is the save gate of the open world, which pauses
 * autosave.
 */
@FunctionalInterface
public interface WorldOperationGate {
    /** Waits until the world is free; interrupting the waiting thread gives up. */
    Permit enter(WorldId worldId) throws InterruptedException;

    /** The right to work on one world until it is closed; closing it twice does nothing. */
    @FunctionalInterface
    interface Permit extends AutoCloseable {
        @Override
        void close();
    }
}
