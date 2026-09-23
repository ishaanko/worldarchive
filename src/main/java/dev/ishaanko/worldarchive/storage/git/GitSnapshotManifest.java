package dev.ishaanko.worldarchive.storage.git;

import dev.ishaanko.worldarchive.model.BackupManifest;
import dev.ishaanko.worldarchive.support.Digests;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * The metadata stored in every snapshot tree: the backup manifest, the LFS patterns the snapshot
 * was written with, and a source identity that the commit message repeats, which binds the
 * commit to exactly this manifest.
 */
record GitSnapshotManifest(
        int storageFormatVersion,
        BackupManifest manifest,
        List<String> lfsPatterns,
        String sourceIdentity) {
    static final int CURRENT_STORAGE_FORMAT_VERSION = 1;

    private static final int MAXIMUM_PATTERNS = 128;

    private static final int MAXIMUM_PATTERN_LENGTH = 256;

    GitSnapshotManifest {
        if (storageFormatVersion != CURRENT_STORAGE_FORMAT_VERSION) {
            throw new IllegalArgumentException("Unsupported Git snapshot metadata version");
        }
        Objects.requireNonNull(manifest, "manifest");
        lfsPatterns = validatePatterns(lfsPatterns);
        Objects.requireNonNull(sourceIdentity, "sourceIdentity");
        if (!sourceIdentity.equals(computeSourceIdentity(manifest))) {
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

    /** The commit message that binds a snapshot commit to its manifest. */
    String commitMessage() {
        return "WorldArchive snapshot\n\n"
                + "world-id: " + manifest.worldId() + "\n"
                + "backup-id: " + manifest.backupId() + "\n"
                + "source-identity: " + sourceIdentity + "\n";
    }

    /** SHA-256 of the manifest's canonical text; stored snapshots depend on this exact text. */
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
                + manifest.inventorySha256() + "\n"
                + manifest.gameVersion()
                        .map(stamp -> "gameVersion:" + stamp.name() + "\n" + stamp.dataVersion() + "\n")
                        .orElse("");
        return Digests.hex(Digests.sha256().digest(canonical.getBytes(StandardCharsets.UTF_8)));
    }

    /** Between 1 and 128 distinct patterns, each safe to write into a Git attributes file. */
    static List<String> validatePatterns(List<String> patterns) {
        Objects.requireNonNull(patterns, "lfsPatterns");
        if (patterns.isEmpty() || patterns.size() > MAXIMUM_PATTERNS) {
            throw new IllegalArgumentException("Git LFS needs between 1 and " + MAXIMUM_PATTERNS + " patterns");
        }
        Set<String> unique = new LinkedHashSet<>();
        for (String pattern : patterns) {
            if (!isSafePattern(Objects.requireNonNull(pattern, "lfsPattern")) || !unique.add(pattern)) {
                throw new IllegalArgumentException("Git LFS pattern is unsafe or repeated: " + pattern);
            }
        }
        return List.copyOf(unique);
    }

    private static boolean isSafePattern(String pattern) {
        return !pattern.isBlank()
                && pattern.length() <= MAXIMUM_PATTERN_LENGTH
                && !pattern.startsWith("!")
                && !pattern.startsWith("-")
                && !pattern.contains("\\")
                && !pattern.contains("..")
                && pattern.chars().noneMatch(character -> Character.isWhitespace(character)
                        || Character.isISOControl(character));
    }
}
