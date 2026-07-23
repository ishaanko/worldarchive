package dev.ishaankot.worldarchive.runtime;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Atomically routes new work to the latest state while retaining older in-flight states. */
final class RuntimeStateRegistry<T> {
    private T current;

    private final List<T> retained = new ArrayList<>();

    synchronized void install(T replacement) {
        T installed = Objects.requireNonNull(replacement, "replacement");
        retained.add(installed);
        current = installed;
    }

    synchronized T currentOrNull() {
        return current;
    }

    synchronized List<T> retained() {
        return List.copyOf(retained);
    }

    synchronized List<T> removeRetired() {
        List<T> removed = new ArrayList<>();
        java.util.Iterator<T> states = retained.iterator();
        while (states.hasNext()) {
            T state = states.next();
            if (state != current) {
                removed.add(state);
                states.remove();
            }
        }
        return List.copyOf(removed);
    }
}
