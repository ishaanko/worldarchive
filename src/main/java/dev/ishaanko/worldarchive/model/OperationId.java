package dev.ishaanko.worldarchive.model;

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

    @Override
    public String toString() {
        return value.toString();
    }
}
