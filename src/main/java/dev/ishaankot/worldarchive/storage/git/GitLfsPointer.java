package dev.ishaankot.worldarchive.storage.git;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Canonical Git LFS pointer discovered independently of mutable attributes. */
record GitLfsPointer(String path, String sha256, long size) {
    private static final String HEADER = "version https://git-lfs.github.com/spec/v1\n";

    private static final Pattern CANONICAL = Pattern.compile(
            "\\Aversion https://git-lfs\\.github\\.com/spec/v1\\n"
                    + "oid sha256:([0-9a-f]{64})\\n"
                    + "size (0|[1-9][0-9]*)\\n?\\z");

    GitLfsPointer {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(sha256, "sha256");
        if (size < 0) {
            throw new IllegalArgumentException("Git LFS object size must not be negative");
        }
    }

    static Optional<GitLfsPointer> parse(
            GitTreeEntry entry,
            String contents,
            boolean truncated) throws GitStorageException {
        if (!contents.startsWith(HEADER)) {
            return Optional.empty();
        }
        if (truncated) {
            throw new GitStorageException("Git snapshot contains an oversized LFS pointer");
        }
        Matcher matcher = CANONICAL.matcher(contents);
        if (!matcher.matches()) {
            throw new GitStorageException("Git snapshot contains a malformed LFS pointer");
        }
        try {
            return Optional.of(new GitLfsPointer(
                    entry.path(),
                    matcher.group(1),
                    Long.parseLong(matcher.group(2))));
        } catch (NumberFormatException exception) {
            throw new GitStorageException("Git LFS pointer size is out of range", exception);
        }
    }

    Path objectPath(Path repository) {
        return repository.resolve("lfs")
                .resolve("objects")
                .resolve(sha256.substring(0, 2))
                .resolve(sha256.substring(2, 4))
                .resolve(sha256);
    }
}
