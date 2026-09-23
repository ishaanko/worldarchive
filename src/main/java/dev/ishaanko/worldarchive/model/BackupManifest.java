package dev.ishaanko.worldarchive.model;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.ZoneOffset;
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
        String inventorySha256,
        Optional<GameVersionStamp> gameVersion) {
    public static final int CURRENT_FORMAT_VERSION = 1;

    /** The longest label in UTF-16 units; the Create screen's label box stops here too. */
    public static final int MAXIMUM_LABEL_LENGTH = 128;

    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");

    public BackupManifest {
        if (formatVersion != CURRENT_FORMAT_VERSION) {
            throw new IllegalArgumentException("Unsupported manifest format version: " + formatVersion);
        }
        Objects.requireNonNull(backupId, "backupId");
        Objects.requireNonNull(worldId, "worldId");
        worldName = SafeText.require(worldName, "worldName", 255);
        label = Objects.requireNonNull(label, "label")
                .map(value -> SafeText.require(value, "label", MAXIMUM_LABEL_LENGTH));
        createdAt = requirePortableCreatedAt(createdAt);
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
        Objects.requireNonNull(gameVersion, "gameVersion");
    }

    /** A manifest in the current format, as a capture writes it. */
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
            String inventorySha256,
            Optional<GameVersionStamp> gameVersion) {
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
                inventorySha256,
                gameVersion);
    }

    private static String requireSha256(String value, String name) {
        Objects.requireNonNull(value, name);
        if (!SHA256.matcher(value).matches()) {
            throw new IllegalArgumentException(name + " must be 64 lowercase hexadecimal characters");
        }
        return value;
    }

    private static Instant requirePortableCreatedAt(Instant value) {
        Objects.requireNonNull(value, "createdAt");
        try {
            int year = value.atZone(ZoneOffset.UTC).getYear();
            if (year < 0 || year > 9_999) {
                throw new IllegalArgumentException(
                        "createdAt must use a four-digit UTC year");
            }
            return value;
        } catch (DateTimeException exception) {
            throw new IllegalArgumentException(
                    "createdAt is outside the portable date range",
                    exception);
        }
    }
}
