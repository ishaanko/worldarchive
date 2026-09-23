package dev.ishaanko.worldarchive.core;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Thread-safe store of short-lived, one-time confirmations, one per key: a new entry for a key
 * replaces the previous one, and {@link #claimMatching} removes an entry at most once. The cleanup
 * preview keeps its plans here, one per world.
 */
public final class ConfirmationLedger<K, V> {
    private final ConcurrentMap<K, V> entries = new ConcurrentHashMap<>();

    private final Function<V, Instant> expiryOf;

    public ConfirmationLedger(Function<V, Instant> expiryOf) {
        this.expiryOf = Objects.requireNonNull(expiryOf, "expiryOf");
    }

    /** Removes every entry whose expiry is not after {@code now}. */
    public void expireStaleEntries(Instant now) {
        Objects.requireNonNull(now, "now");
        entries.forEach((key, value) -> {
            if (!now.isBefore(expiryOf.apply(value))) {
                entries.remove(key, value);
            }
        });
    }

    /** Inserts or replaces the entry at {@code key}, evicting any previous entry there. */
    public void put(K key, V value) {
        entries.put(Objects.requireNonNull(key, "key"), Objects.requireNonNull(value, "value"));
    }

    /**
     * Expires stale entries as of {@code now}, then removes and returns the first remaining
     * entry whose value satisfies {@code matcher}.
     */
    public Optional<V> claimMatching(Instant now, Predicate<V> matcher) {
        expireStaleEntries(now);
        for (Map.Entry<K, V> entry : entries.entrySet()) {
            V value = entry.getValue();
            if (matcher.test(value) && entries.remove(entry.getKey(), value)) {
                return Optional.of(value);
            }
        }
        return Optional.empty();
    }
}
