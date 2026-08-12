package dev.ishaanko.worldarchive.core;

import dev.ishaanko.worldarchive.model.WorldId;

/** Injectable per-world lane shared by capture and maintenance operations. */
@FunctionalInterface
public interface WorldOperationGate {
    Permit enter(WorldId worldId) throws InterruptedException;

    /** One acquired, transferable world lane permit. */
    @FunctionalInterface
    interface Permit extends AutoCloseable {
        @Override
        void close();
    }
}
