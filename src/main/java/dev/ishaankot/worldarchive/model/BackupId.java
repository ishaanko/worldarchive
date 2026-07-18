package dev.ishaankot.worldarchive.model;

import java.util.Objects;
import java.util.UUID;

/** A collision-resistant identity for one logical backup. */
public record BackupId(UUID value) implements Comparable<BackupId> {
    public BackupId {
        Objects.requireNonNull(value, "value");
        if (value.getMostSignificantBits() == 0L && value.getLeastSignificantBits() == 0L) {
            throw new IllegalArgumentException("Backup ID must not be the nil UUID");
        }
    }

    public static BackupId create() {
        return new BackupId(UUID.randomUUID());
    }

    public static BackupId parse(String value) {
        Objects.requireNonNull(value, "value");
        return new BackupId(UUID.fromString(value));
    }

    @Override
    public int compareTo(BackupId other) {
        return value.compareTo(other.value);
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
