package dev.ishaankot.worldarchive.model;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/** Immutable, portable description of the source captured by a backup. */
public record BackupManifest(
        int formatVersion,
        BackupId backupId,
        WorldId worldId,
        String worldName,
        Optional<String> label,
        Instant createdAt,
        BackupTrigger trigger,
        long sourceFileCount,
        long sourceByteCount,
        long changedFileCount,
        String contentSha256,
        String inventorySha256) {
    public static final int CURRENT_FORMAT_VERSION = 1;

    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");

    public BackupManifest {
        if (formatVersion != CURRENT_FORMAT_VERSION) {
            throw new IllegalArgumentException("Unsupported manifest format version: " + formatVersion);
        }
        Objects.requireNonNull(backupId, "backupId");
        Objects.requireNonNull(worldId, "worldId");
        worldName = requireText(worldName, "worldName", 255);
        label = Objects.requireNonNull(label, "label")
                .map(SensitiveDataRedactor::redact)
                .map(value -> requireText(value, "label", 128));
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(trigger, "trigger");
        if (sourceFileCount < 0) {
            throw new IllegalArgumentException("sourceFileCount must not be negative");
        }
        if (sourceByteCount < 0) {
            throw new IllegalArgumentException("sourceByteCount must not be negative");
        }
        if (changedFileCount < 0) {
            throw new IllegalArgumentException("changedFileCount must not be negative");
        }
        contentSha256 = requireSha256(contentSha256, "contentSha256");
        inventorySha256 = requireSha256(inventorySha256, "inventorySha256");
    }

    /** Compatibility constructor for the initial single-digest manifest contract. */
    public BackupManifest(
            int formatVersion,
            BackupId backupId,
            WorldId worldId,
            String worldName,
            Instant createdAt,
            BackupTrigger trigger,
            long sourceFileCount,
            long sourceByteCount,
            String sourceSha256) {
        this(
                formatVersion,
                backupId,
                worldId,
                worldName,
                Optional.empty(),
                createdAt,
                trigger,
                sourceFileCount,
                sourceByteCount,
                sourceFileCount,
                sourceSha256,
                sourceSha256);
    }

    public static BackupManifest create(
            BackupId backupId,
            WorldId worldId,
            String worldName,
            Instant createdAt,
            BackupTrigger trigger,
            long sourceFileCount,
            long sourceByteCount,
            String sourceSha256) {
        return new BackupManifest(
                CURRENT_FORMAT_VERSION,
                backupId,
                worldId,
                worldName,
                createdAt,
                trigger,
                sourceFileCount,
                sourceByteCount,
                sourceSha256);
    }

    public static BackupManifest create(
            BackupId backupId,
            WorldId worldId,
            String worldName,
            Optional<String> label,
            Instant createdAt,
            BackupTrigger trigger,
            long sourceFileCount,
            long sourceByteCount,
            long changedFileCount,
            String contentSha256,
            String inventorySha256) {
        return new BackupManifest(
                CURRENT_FORMAT_VERSION,
                backupId,
                worldId,
                worldName,
                label,
                createdAt,
                trigger,
                sourceFileCount,
                sourceByteCount,
                changedFileCount,
                contentSha256,
                inventorySha256);
    }

    /** Compatibility alias for callers that previously consumed the single source digest. */
    public String sourceSha256() {
        return contentSha256;
    }

    private static String requireText(String value, String name, int maximumLength) {
        Objects.requireNonNull(value, name);
        if (value.isBlank() || value.length() > maximumLength) {
            throw new IllegalArgumentException(name + " must contain between 1 and " + maximumLength + " characters");
        }
        if (value.chars().anyMatch(character -> Character.isISOControl(character))) {
            throw new IllegalArgumentException(name + " must not contain control characters");
        }
        return value;
    }

    private static String requireSha256(String value, String name) {
        Objects.requireNonNull(value, name);
        if (!SHA256.matcher(value).matches()) {
            throw new IllegalArgumentException(name + " must be 64 lowercase hexadecimal characters");
        }
        return value;
    }
}
