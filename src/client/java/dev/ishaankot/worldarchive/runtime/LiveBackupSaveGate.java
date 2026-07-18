package dev.ishaankot.worldarchive.runtime;

import java.util.Objects;
import java.util.Optional;

/** Pure pending-save state used to distinguish requested, exit, and unrelated vanilla saves. */
final class LiveBackupSaveGate<T> {
    private Entry<T> pending;

    synchronized boolean queueRequested(T value) {
        Objects.requireNonNull(value, "value");
        if (pending != null) {
            return false;
        }
        pending = new Entry<>(value, false, false);
        return true;
    }

    synchronized Optional<T> replaceWithExit(T value) {
        Objects.requireNonNull(value, "value");
        T displaced = pending == null ? null : pending.value();
        pending = new Entry<>(value, true, false);
        return Optional.ofNullable(displaced);
    }

    synchronized boolean armRequested(T value) {
        if (pending == null || pending.value() != value || pending.exit()) {
            return false;
        }
        pending = new Entry<>(value, false, true);
        return true;
    }

    synchronized Optional<T> consume(
            T value,
            boolean stopping,
            boolean flush,
            boolean force) {
        if (pending == null || pending.value() != value) {
            return Optional.empty();
        }
        boolean matchingExit = pending.exit() && stopping && flush && !force;
        boolean matchingRequest = !pending.exit() && pending.armed() && flush && force;
        if (!matchingExit && !matchingRequest) {
            return Optional.empty();
        }
        T consumed = pending.value();
        pending = null;
        return Optional.of(consumed);
    }

    synchronized Optional<T> clear(T value) {
        if (pending == null || pending.value() != value) {
            return Optional.empty();
        }
        T cleared = pending.value();
        pending = null;
        return Optional.of(cleared);
    }

    synchronized Optional<T> clear() {
        if (pending == null) {
            return Optional.empty();
        }
        T cleared = pending.value();
        pending = null;
        return Optional.of(cleared);
    }

    synchronized Optional<T> pending() {
        return pending == null ? Optional.empty() : Optional.of(pending.value());
    }

    synchronized boolean hasPending() {
        return pending != null;
    }

    private record Entry<T>(T value, boolean exit, boolean armed) {
        private Entry {
            Objects.requireNonNull(value, "value");
            if (exit && armed) {
                throw new IllegalArgumentException("Exit saves are never explicitly armed");
            }
        }
    }
}
