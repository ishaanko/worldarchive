package dev.ishaanko.worldarchive.storage.git;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A Git LFS pointer: the tree stores this small text in place of a file, and the file's bytes
 * live in the repository's LFS object store under their SHA-256. A blob is recognized as a
 * pointer by its content, never by mutable attributes.
 */
record GitLfsPointer(String sha256, long size) {
    /** Git LFS never treats a blob of this many bytes or more as a pointer. */
    static final int MAXIMUM_BYTES = 1_024;

    private static final String HEADER = "version https://git-lfs.github.com/spec/v1\n";

    private static final byte[] HEADER_BYTES = HEADER.getBytes(StandardCharsets.US_ASCII);

    /** How many bytes of a file tell whether it starts like a pointer. */
    static final int HEADER_LENGTH = HEADER_BYTES.length;

    private static final Pattern CANONICAL = Pattern.compile(
            "\\Aversion https://git-lfs\\.github\\.com/spec/v1\\n"
                    + "oid sha256:([0-9a-f]{64})\\n"
                    + "size (0|[1-9][0-9]{0,18})\\n?\\z");

    GitLfsPointer {
        Objects.requireNonNull(sha256, "sha256");
        if (size < 0) {
            throw new IllegalArgumentException("A Git LFS object size must not be negative");
        }
    }

    /** The pointer in a blob that starts like one; a blob that does not is ordinary content. */
    static Optional<GitLfsPointer> parse(byte[] blob) throws GitStorageException {
        if (blob.length >= MAXIMUM_BYTES || !startsWithHeader(blob)) {
            return Optional.empty();
        }
        Matcher matcher = CANONICAL.matcher(new String(blob, StandardCharsets.UTF_8));
        if (!matcher.matches()) {
            throw new GitStorageException("The snapshot holds a malformed Git LFS pointer");
        }
        try {
            return Optional.of(new GitLfsPointer(matcher.group(1), Long.parseLong(matcher.group(2))));
        } catch (NumberFormatException exception) {
            throw new GitStorageException("The snapshot holds a Git LFS pointer with an impossible size", exception);
        }
    }

    /**
     * Whether the bytes begin with the pointer header. A blob under {@link #MAXIMUM_BYTES} that
     * does is read as a pointer.
     */
    static boolean startsWithHeader(byte[] bytes) {
        return bytes.length >= HEADER_LENGTH && Arrays.equals(bytes, 0, HEADER_LENGTH, HEADER_BYTES, 0, HEADER_LENGTH);
    }

    /** The pointer text, byte for byte what Git LFS writes. */
    String text() {
        return HEADER + "oid sha256:" + sha256 + "\nsize " + size + "\n";
    }
}
