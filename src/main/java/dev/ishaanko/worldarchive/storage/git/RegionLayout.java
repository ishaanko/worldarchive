package dev.ishaanko.worldarchive.storage.git;

import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.channels.SeekableByteChannel;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The strict parse of one region file ({@code r.<x>.<z>.mca}) and the bucket objects made from it.
 *
 * <p>The source is read with positional reads on a {@link SeekableByteChannel}: {@link #parse}
 * reads the 8 KiB of tables and each chunk's length field, and {@link #writeBucket} reads the
 * payloads of one bucket's chunks. Neither reads the whole file, so neither can hash it; a caller
 * that must prove the file still has a recorded SHA-256 hashes it in its own sequential pass.
 * The channel must be the same unchanged file for every call on one layout. Memory is the tables
 * plus a 64 KiB copy buffer.
 *
 * <p>A writer does: {@code parse}; when that is empty, store the file whole; otherwise
 * {@code writeBucket} for each of {@link #bucketNumbers()}, and
 * {@link RegionAssembler#fromBuckets} over the written buckets for the index's stored size and
 * digest.
 */
final class RegionLayout {
    static final int TABLES_BYTES = 2 * RegionEntry.SECTOR_BYTES;

    static final long MAXIMUM_SOURCE_BYTES = 256L * 1_024 * 1_024;

    private static final int COPY_BUFFER_BYTES = 64 * 1_024;

    private static final Pattern NAME = Pattern.compile("r\\.(-?[0-9]{1,11})\\.(-?[0-9]{1,11})\\.mca");

    private final List<RegionEntry> entries;

    private final long[] payloadOffsets;

    private RegionLayout(List<RegionEntry> entries, long[] payloadOffsets) {
        this.entries = List.copyOf(entries);
        this.payloadOffsets = payloadOffsets;
    }

    /** Whether a file name is {@code r.<int>.<int>.mca}, with each int written as the game writes it. */
    static boolean isRegionFileName(String fileName) {
        Matcher matcher = NAME.matcher(Objects.requireNonNull(fileName, "fileName"));
        return matcher.matches() && isCanonicalInt(matcher.group(1)) && isCanonicalInt(matcher.group(2));
    }

    /**
     * The layout of a region file that passes every strict-parse rule, or empty when one fails.
     * Empty is not an error: the caller stores that file whole. An I/O failure, or a file that
     * ends before its own size, is an {@link IOException}.
     */
    static Optional<RegionLayout> parse(SeekableByteChannel source) throws IOException {
        long size = source.size();
        if (size < TABLES_BYTES || size % RegionEntry.SECTOR_BYTES != 0 || size > MAXIMUM_SOURCE_BYTES) {
            return Optional.empty();
        }
        ByteBuffer tables = ByteBuffer.allocate(TABLES_BYTES);
        readFully(source, 0, tables);
        IntBuffer ints = tables.flip().asIntBuffer();
        int[] locations = new int[RegionEntry.CHUNKS];
        int[] timestamps = new int[RegionEntry.CHUNKS];
        ints.get(locations).get(timestamps);
        if (!extentsFit(locations, size / RegionEntry.SECTOR_BYTES)) {
            return Optional.empty();
        }
        return entries(source, locations, timestamps);
    }

    /** Every entry, in ascending chunk index. */
    List<RegionEntry> entries() {
        return entries;
    }

    /** The bucket numbers that have at least one entry, ascending. Only these buckets exist. */
    List<Integer> bucketNumbers() {
        return entries.stream().map(RegionEntry::bucket).distinct().sorted().toList();
    }

    /** The entries of one bucket, in ascending chunk index; empty when the bucket does not exist. */
    List<RegionEntry> bucket(int number) {
        return entries.stream().filter(entry -> entry.bucket() == number).toList();
    }

    /**
     * Writes bucket object {@code number}: {@code WARB}, version, number, then each entry's index,
     * timestamp, length, and payload read from {@code source}. The same entries always give the
     * same bytes. The stream is flushed, not closed.
     */
    void writeBucket(int number, SeekableByteChannel source, OutputStream output) throws IOException {
        List<RegionEntry> bucket = bucket(number);
        if (bucket.isEmpty()) {
            throw new IllegalArgumentException("Region bucket " + number + " has no entry and does not exist");
        }
        DataOutputStream data = new DataOutputStream(output);
        data.write(RegionBucketReader.MAGIC);
        data.writeByte(RegionBucketReader.VERSION);
        data.writeByte(number);
        for (RegionEntry entry : bucket) {
            data.writeShort(entry.index());
            data.writeInt(entry.timestamp());
            data.writeInt(entry.length());
            copyPayload(entry, source, data);
        }
        data.flush();
    }

    /** Copies a present entry's payload from {@code source}; an absent entry copies nothing. */
    void copyPayload(RegionEntry entry, SeekableByteChannel source, OutputStream output) throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate(Math.min(COPY_BUFFER_BYTES, Math.max(entry.length(), 1)));
        long position = payloadOffsets[entry.index()];
        long remaining = entry.length();
        while (remaining > 0) {
            buffer.clear().limit((int) Math.min(buffer.capacity(), remaining));
            readFully(source, position, buffer);
            output.write(buffer.array(), 0, buffer.position());
            position += buffer.position();
            remaining -= buffer.position();
        }
    }

    private static boolean isCanonicalInt(String text) {
        try {
            return Integer.toString(Integer.parseInt(text)).equals(text);
        } catch (NumberFormatException exception) {
            return false;
        }
    }

    /** Whether every nonzero location starts at sector 2 or later, has a sector, fits, and overlaps none. */
    private static boolean extentsFit(int[] locations, long sectorCount) {
        long[] extents = new long[RegionEntry.CHUNKS];
        int used = 0;
        for (int location : locations) {
            if (location == 0) {
                continue;
            }
            int sector = location >>> 8;
            int count = location & 0xFF;
            if (sector < 2 || count == 0 || sector + (long) count > sectorCount) {
                return false;
            }
            extents[used++] = (long) sector << 8 | count;
        }
        Arrays.sort(extents, 0, used);
        long previousEnd = 0;
        for (int i = 0; i < used; i++) {
            long sector = extents[i] >>> 8;
            if (sector < previousEnd) {
                return false;
            }
            previousEnd = sector + (extents[i] & 0xFF);
        }
        return true;
    }

    private static Optional<RegionLayout> entries(SeekableByteChannel source, int[] locations, int[] timestamps)
            throws IOException {
        List<RegionEntry> entries = new ArrayList<>();
        long[] payloadOffsets = new long[RegionEntry.CHUNKS];
        ByteBuffer lengthField = ByteBuffer.allocate(Integer.BYTES);
        for (int index = 0; index < RegionEntry.CHUNKS; index++) {
            int location = locations[index];
            if (location == 0) {
                if (timestamps[index] != 0) {
                    entries.add(new RegionEntry(index, timestamps[index], 0));
                }
                continue;
            }
            long offset = (long) (location >>> 8) * RegionEntry.SECTOR_BYTES;
            readFully(source, offset, lengthField.clear());
            int length = lengthField.getInt(0);
            if (length < 1 || Integer.BYTES + (long) length > (long) (location & 0xFF) * RegionEntry.SECTOR_BYTES) {
                return Optional.empty();
            }
            entries.add(new RegionEntry(index, timestamps[index], length));
            payloadOffsets[index] = offset + Integer.BYTES;
        }
        return Optional.of(new RegionLayout(entries, payloadOffsets));
    }

    private static void readFully(SeekableByteChannel source, long position, ByteBuffer buffer) throws IOException {
        source.position(position);
        while (buffer.hasRemaining()) {
            if (source.read(buffer) < 0) {
                throw new IOException("The region file ended early; it changed while it was being read");
            }
        }
    }
}
