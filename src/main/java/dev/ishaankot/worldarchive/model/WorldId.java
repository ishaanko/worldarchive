package dev.ishaankot.worldarchive.model;

import java.util.Objects;
import java.util.UUID;

/** A stable, collision-resistant identity for a Minecraft world. */
public record WorldId(UUID value) implements Comparable<WorldId> {
    public static final int DISPLAY_CODE_LENGTH = 6;

    public WorldId {
        Objects.requireNonNull(value, "value");
        if (value.getMostSignificantBits() == 0L && value.getLeastSignificantBits() == 0L) {
            throw new IllegalArgumentException("World ID must not be the nil UUID");
        }
    }

    public static WorldId create() {
        return new WorldId(UUID.randomUUID());
    }

    public static WorldId parse(String value) {
        Objects.requireNonNull(value, "value");
        return new WorldId(UUID.fromString(value));
    }

    /** Short, user-facing code; the full UUID remains the durable storage identity. */
    public String displayCode() {
        return toString().substring(0, DISPLAY_CODE_LENGTH);
    }

    @Override
    public int compareTo(WorldId other) {
        return value.compareTo(other.value);
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
