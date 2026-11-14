package dev.ishaanko.worldarchive.storage.git;

import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The index of a bucketed region file, stored at {@code .worldarchive/regions/<path>/index}:
 * {@code source-*} describe the captured file, {@code stored-*} the stored region file a restore
 * writes. The text is exactly five lines, each ending in a newline.
 */
record RegionIndex(String sourceSha256, long sourceSize, String storedSha256, long storedSize) {
    /** An index of this many bytes or more is rejected. */
    static final int MAXIMUM_BYTES = 1_024;

    private static final String HEADER = "worldarchive-region-v1";

    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");

    private static final Pattern CANONICAL = Pattern.compile(
            "\\A" + HEADER + "\\n"
                    + "source-sha256 ([0-9a-f]{64})\\n"
                    + "source-size (0|[1-9][0-9]{0,18})\\n"
                    + "stored-sha256 ([0-9a-f]{64})\\n"
                    + "stored-size (0|[1-9][0-9]{0,18})\\n\\z");

    RegionIndex {
        requireSha256(sourceSha256, "sourceSha256");
        requireSha256(storedSha256, "storedSha256");
        if (sourceSize < 0 || storedSize < 0) {
            throw new IllegalArgumentException("A region file size must not be negative");
        }
    }

    /** The index text in UTF-8, byte for byte what {@link #decode} accepts. */
    byte[] encode() {
        return (HEADER + "\n"
                + "source-sha256 " + sourceSha256 + "\n"
                + "source-size " + sourceSize + "\n"
                + "stored-sha256 " + storedSha256 + "\n"
                + "stored-size " + storedSize + "\n").getBytes(StandardCharsets.UTF_8);
    }

    /** The index in {@code bytes}, which must be exactly the text {@link #encode} writes. */
    static RegionIndex decode(byte[] bytes) throws GitStorageException {
        if (bytes.length >= MAXIMUM_BYTES) {
            throw malformed();
        }
        Matcher matcher = CANONICAL.matcher(new String(bytes, StandardCharsets.UTF_8));
        if (!matcher.matches()) {
            throw malformed();
        }
        try {
            return new RegionIndex(matcher.group(1), Long.parseLong(matcher.group(2)),
                    matcher.group(3), Long.parseLong(matcher.group(4)));
        } catch (NumberFormatException exception) {
            throw new GitStorageException("The snapshot holds a region index with an impossible size", exception);
        }
    }

    private static void requireSha256(String value, String name) {
        if (!SHA256.matcher(Objects.requireNonNull(value, name)).matches()) {
            throw new IllegalArgumentException(name + " must be 64 lowercase hex digits");
        }
    }

    private static GitStorageException malformed() {
        return new GitStorageException("The snapshot holds a malformed region index; the snapshot is damaged");
    }
}
