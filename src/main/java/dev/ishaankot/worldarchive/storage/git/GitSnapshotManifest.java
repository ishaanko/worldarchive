package dev.ishaankot.worldarchive.storage.git;

import dev.ishaankot.worldarchive.model.BackupManifest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/** Snapshot-local metadata binding source identity and LFS behavior to one commit. */
record GitSnapshotManifest(
        int storageFormatVersion,
        BackupManifest manifest,
        List<String> lfsPatterns,
        String sourceIdentity) {
    static final int CURRENT_STORAGE_FORMAT_VERSION = 1;

    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");

    GitSnapshotManifest {
        if (storageFormatVersion != CURRENT_STORAGE_FORMAT_VERSION) {
            throw new IllegalArgumentException("Unsupported Git snapshot metadata version");
        }
        Objects.requireNonNull(manifest, "manifest");
        lfsPatterns = validatePatterns(lfsPatterns);
        Objects.requireNonNull(sourceIdentity, "sourceIdentity");
        if (!SHA256.matcher(sourceIdentity).matches()
                || !sourceIdentity.equals(computeSourceIdentity(manifest))) {
            throw new IllegalArgumentException("Git snapshot source identity is invalid");
        }
    }

    static GitSnapshotManifest create(BackupManifest manifest, List<String> lfsPatterns) {
        return new GitSnapshotManifest(
                CURRENT_STORAGE_FORMAT_VERSION,
                manifest,
                lfsPatterns,
                computeSourceIdentity(manifest));
    }

    static String computeSourceIdentity(BackupManifest manifest) {
        Objects.requireNonNull(manifest, "manifest");
        String canonical = "worldarchive-source-v1\n"
                + manifest.formatVersion() + "\n"
                + manifest.backupId() + "\n"
                + manifest.worldId() + "\n"
                + manifest.worldName() + "\n"
                + manifest.label().map(value -> "present:" + value).orElse("absent") + "\n"
                + manifest.createdAt() + "\n"
                + manifest.trigger() + "\n"
                + manifest.sourceFileCount() + "\n"
                + manifest.sourceByteCount() + "\n"
                + manifest.changedFileCount() + "\n"
                + manifest.contentSha256() + "\n"
                + manifest.inventorySha256() + "\n";
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("The Java runtime does not provide SHA-256", exception);
        }
    }

    static List<String> validatePatterns(List<String> patterns) {
        Objects.requireNonNull(patterns, "lfsPatterns");
        if (patterns.isEmpty() || patterns.size() > 128) {
            throw new IllegalArgumentException("Git snapshot has an invalid LFS pattern count");
        }
        Set<String> unique = new LinkedHashSet<>();
        for (String pattern : patterns) {
            Objects.requireNonNull(pattern, "lfsPattern");
            if (pattern.isBlank()
                    || pattern.length() > 256
                    || pattern.startsWith("!")
                    || pattern.startsWith("-")
                    || pattern.contains("\\")
                    || pattern.contains("..")
                    || pattern.chars().anyMatch(Character::isWhitespace)
                    || pattern.chars().anyMatch(Character::isISOControl)
                    || !unique.add(pattern)) {
                throw new IllegalArgumentException("Git snapshot has an unsafe LFS pattern");
            }
        }
        return List.copyOf(unique);
    }
}
