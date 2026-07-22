package dev.ishaankot.worldarchive.model;

import java.util.Objects;
import java.util.UUID;
import java.nio.charset.StandardCharsets;

/** Opaque identity for a durable external import source. */
public record ImportSourceId(UUID value) implements Comparable<ImportSourceId> {
    public ImportSourceId {
        Objects.requireNonNull(value, "value");
    }

    public static ImportSourceId create() {
        return new ImportSourceId(UUID.randomUUID());
    }

    /** Stable identity used to make importing the same external source idempotent. */
    public static ImportSourceId derived(String sourceIdentity) {
        String identity = Objects.requireNonNull(sourceIdentity, "sourceIdentity");
        if (identity.isBlank()) {
            throw new IllegalArgumentException("sourceIdentity must not be blank");
        }
        return new ImportSourceId(UUID.nameUUIDFromBytes(
                ("WorldArchive import source\0" + identity).getBytes(StandardCharsets.UTF_8)));
    }

    public static ImportSourceId parse(String value) {
        Objects.requireNonNull(value, "value");
        return new ImportSourceId(UUID.fromString(value));
    }

    @Override
    public int compareTo(ImportSourceId other) {
        return value.compareTo(Objects.requireNonNull(other, "other").value);
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
