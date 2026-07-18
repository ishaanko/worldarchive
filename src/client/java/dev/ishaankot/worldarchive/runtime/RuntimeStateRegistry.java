package dev.ishaankot.worldarchive.runtime;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicReference;

/** Atomically routes new work to the latest state while retaining older in-flight states. */
final class RuntimeStateRegistry<T> {
    private final AtomicReference<T> current = new AtomicReference<>();

    private final ConcurrentLinkedQueue<T> retained = new ConcurrentLinkedQueue<>();

    void install(T replacement) {
        retained.add(Objects.requireNonNull(replacement, "replacement"));
        current.set(replacement);
    }

    T currentOrNull() {
        return current.get();
    }

    List<T> retained() {
        return List.copyOf(retained);
    }
}
