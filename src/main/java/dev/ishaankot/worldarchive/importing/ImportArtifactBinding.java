package dev.ishaankot.worldarchive.importing;

import dev.ishaankot.worldarchive.model.BackupId;
import dev.ishaankot.worldarchive.model.WorldId;
import java.util.Objects;
import java.util.regex.Pattern;

/** Immutable locator and content identity for one artifact in an external source. */
public record ImportArtifactBinding(
        WorldId worldId,
        BackupId backupId,
        String locator,
        String fingerprint) {
    private static final Pattern FINGERPRINT = Pattern.compile("(?:[0-9a-f]{40}|[0-9a-f]{64})");

    public ImportArtifactBinding {
        Objects.requireNonNull(worldId, "worldId");
        Objects.requireNonNull(backupId, "backupId");
        locator = requireText(locator, "locator", 2_048);
        fingerprint = Objects.requireNonNull(fingerprint, "fingerprint");
        if (!FINGERPRINT.matcher(fingerprint).matches()) {
            throw new IllegalArgumentException("Import artifact fingerprint is invalid");
        }
    }

    private static String requireText(String value, String name, int maximumLength) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()
                || value.length() > maximumLength
                || value.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(name + " is blank, too long, or contains control characters");
        }
        return value;
    }
}
