package dev.ishaankot.worldarchive.core;

import java.util.Objects;
import java.util.UUID;

/** Identity for one observable service operation. */
public record OperationId(UUID value) {
    public OperationId {
        Objects.requireNonNull(value, "value");
        if (value.getMostSignificantBits() == 0L && value.getLeastSignificantBits() == 0L) {
            throw new IllegalArgumentException("Operation ID must not be the nil UUID");
        }
    }

    public static OperationId create() {
        return new OperationId(UUID.randomUUID());
    }

    public static OperationId parse(String value) {
        Objects.requireNonNull(value, "value");
        return new OperationId(UUID.fromString(value));
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
