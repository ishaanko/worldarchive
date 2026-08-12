package dev.ishaanko.worldarchive.core;

import java.util.ArrayDeque;
import java.util.Deque;

/** Per-world FIFO queue of pending operations plus the one currently active. */
final class WorldLane<T> {
    final Deque<T> queue = new ArrayDeque<>();

    T active;
}
