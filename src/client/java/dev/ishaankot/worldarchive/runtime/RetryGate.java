package dev.ishaankot.worldarchive.runtime;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

/** Single-flight retry throttle whose attempt tokens reject stale completions. */
final class RetryGate<K> {
    private final long retryDelayNanos;

    private Attempt<K> pending;

    private long nextAttemptNanos;

    private boolean throttled;

    RetryGate(Duration retryDelay) {
        Objects.requireNonNull(retryDelay, "retryDelay");
        if (retryDelay.isNegative()) {
            throw new IllegalArgumentException("retryDelay must not be negative");
        }
        retryDelayNanos = retryDelay.toNanos();
    }

    synchronized Optional<Attempt<K>> tryStart(K owner, long nowNanos) {
        Objects.requireNonNull(owner, "owner");
        if (pending != null || throttled && nowNanos < nextAttemptNanos) {
            return Optional.empty();
        }
        pending = new Attempt<>(owner);
        nextAttemptNanos = saturatingAdd(nowNanos, retryDelayNanos);
        throttled = true;
        return Optional.of(pending);
    }

    synchronized boolean complete(Attempt<K> attempt) {
        Objects.requireNonNull(attempt, "attempt");
        if (pending != attempt) {
            return false;
        }
        pending = null;
        return true;
    }

    synchronized void reset() {
        pending = null;
        nextAttemptNanos = 0L;
        throttled = false;
    }

    private static long saturatingAdd(long left, long right) {
        return left > Long.MAX_VALUE - right ? Long.MAX_VALUE : left + right;
    }

    static final class Attempt<K> {
        private final K owner;

        private Attempt(K owner) {
            this.owner = owner;
        }

        K owner() {
            return owner;
        }
    }
}
