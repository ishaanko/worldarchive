package dev.ishaanko.worldarchive.core;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.function.Predicate;

/** Per-world FIFO queue of pending operations plus the one currently active. */
final class WorldLane<T> {
    final Deque<T> queue = new ArrayDeque<>();

    T active;

    /** Returns the active or queued entry matching the predicate, or null. */
    synchronized T find(Predicate<T> matches) {
        if (active != null && matches.test(active)) {
            return active;
        }
        for (T queued : queue) {
            if (matches.test(queued)) {
                return queued;
            }
        }
        return null;
    }
}
