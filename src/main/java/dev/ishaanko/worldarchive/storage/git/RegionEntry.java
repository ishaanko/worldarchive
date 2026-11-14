package dev.ishaanko.worldarchive.storage.git;

/**
 * One chunk slot of a region file as a bucket keeps it: a present chunk ({@code length >= 1}, the
 * bytes after the chunk's length field), or an absent chunk whose nonzero timestamp is kept
 * ({@code length == 0}). The payload bytes are not held here; they stay in the source file or the
 * bucket stream.
 */
record RegionEntry(int index, int timestamp, int length) {
    /** Chunk slots in a region file, 32 by 32. */
    static final int CHUNKS = 1_024;

    /** Buckets in a region file, squares of 8 by 8 chunks. */
    static final int BUCKETS = 16;

    static final int SECTOR_BYTES = 4_096;

    /** The largest payload one location entry can describe: 255 sectors less the length field. */
    static final int MAXIMUM_LENGTH = 255 * SECTOR_BYTES - Integer.BYTES;

    RegionEntry {
        if (index < 0 || index >= CHUNKS) {
            throw new IllegalArgumentException("A chunk index must be between 0 and 1023");
        }
        if (length < 0 || length > MAXIMUM_LENGTH) {
            throw new IllegalArgumentException("A chunk payload must be between 0 and " + MAXIMUM_LENGTH + " bytes");
        }
        if (length == 0 && timestamp == 0) {
            throw new IllegalArgumentException("An absent chunk is kept only for a nonzero timestamp");
        }
    }

    /** The bucket of chunk {@code index}: {@code ((index / 32) / 8) * 4 + ((index % 32) / 8)}. */
    static int bucketOf(int index) {
        return index / 32 / 8 * 4 + index % 32 / 8;
    }

    int bucket() {
        return bucketOf(index);
    }

    boolean present() {
        return length > 0;
    }

    /** Sectors this chunk takes in a stored region file: its length field and payload, rounded up. */
    int sectors() {
        return (Integer.BYTES + length + SECTOR_BYTES - 1) / SECTOR_BYTES;
    }
}
