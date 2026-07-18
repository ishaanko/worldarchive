package dev.ishaankot.worldarchive.storage.git;

import java.util.Objects;
import java.util.regex.Pattern;

/** One portable regular file and its actual (post-LFS-materialization) content identity. */
record GitInventoryEntry(String path, long size, String sha256) implements Comparable<GitInventoryEntry> {
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");

    GitInventoryEntry {
        path = GitPortablePath.validate(Objects.requireNonNull(path, "path"));
        if (size < 0) {
            throw new IllegalArgumentException("Git inventory size must not be negative");
        }
        Objects.requireNonNull(sha256, "sha256");
        if (!SHA256.matcher(sha256).matches()) {
            throw new IllegalArgumentException("Git inventory checksum must be lowercase SHA-256");
        }
    }

    @Override
    public int compareTo(GitInventoryEntry other) {
        return path.compareTo(other.path);
    }
}
