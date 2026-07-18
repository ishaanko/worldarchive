package dev.ishaankot.worldarchive.storage.zip;

/** Conservative allocation and traversal limits for untrusted ZIP metadata. */
final class ZipLimits {
    static final int MAXIMUM_ARCHIVE_ENTRIES = 200_000;

    static final int MAXIMUM_INVENTORY_FILES = 200_000;

    static final int MAXIMUM_MANAGED_DIRECTORY_ENTRIES = 400_000;

    static final int MAXIMUM_PATH_UTF8_BYTES = 4_096;

    static final int MAXIMUM_SEGMENT_UTF8_BYTES = 255;

    static final int MAXIMUM_CHECKSUM_BYTES = 4_096;

    static final long MAXIMUM_ARCHIVE_BYTES = 16L * 1_024 * 1_024 * 1_024 * 1_024;

    static final long MAXIMUM_UNCOMPRESSED_BYTES = 64L * 1_024 * 1_024 * 1_024 * 1_024;

    private ZipLimits() {
    }
}
