package dev.ishaankot.worldarchive.core;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Thread-safe store of short-lived, one-time confirmation entries keyed by {@code K}.
 *
 * <p>Supports both a token-keyed usage (the key itself is the confirmation token, minted by
 * {@link #putUnique}) and an entity-keyed usage with eviction (the key is a domain entity such
 * as a world, and issuing a new entry for that key replaces any previous one via {@link #put}).
 * Either way, {@link #claim} and {@link #claimMatching} remove an entry at most once.
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
     * Generates keys via {@code keyGenerator} and builds a value for each candidate until one
     * is inserted without colliding with an existing entry.
     */
    public Issued<K, V> putUnique(Supplier<K> keyGenerator, Function<K, V> valueFactory) {
        K key;
        V value;
        do {
            key = Objects.requireNonNull(keyGenerator.get(), "generated key");
            value = Objects.requireNonNull(valueFactory.apply(key), "generated value");
        } while (entries.putIfAbsent(key, value) != null);
        return new Issued<>(key, value);
    }

    /** Removes and returns the entry at {@code key}, if any, regardless of its expiry. */
    public Optional<V> claim(K key) {
        return Optional.ofNullable(entries.remove(key));
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

    /** A freshly minted key/value pair returned by {@link #putUnique}. */
    public record Issued<K, V>(K key, V value) {
        public Issued {
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(value, "value");
        }
    }
}
