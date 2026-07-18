package dev.ishaankot.worldarchive.core;

import dev.ishaankot.worldarchive.model.WorldId;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;

/** Fair in-process implementation of the shared per-world operation gate. */
public final class LockingWorldOperationGate implements WorldOperationGate {
    private final ConcurrentMap<WorldId, Semaphore> lanes = new ConcurrentHashMap<>();

    @Override
    public Permit enter(WorldId worldId) throws InterruptedException {
        Semaphore lane = lanes.computeIfAbsent(
                Objects.requireNonNull(worldId, "worldId"), ignored -> new Semaphore(1, true));
        lane.acquire();
        AtomicBoolean released = new AtomicBoolean();
        return () -> {
            if (released.compareAndSet(false, true)) {
                lane.release();
            }
        };
    }
}
