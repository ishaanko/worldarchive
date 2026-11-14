package dev.ishaanko.worldarchive.storage.git;

import java.io.BufferedInputStream;
import java.io.Closeable;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;

/**
 * Decodes one region bucket object as a stream, entry by entry, and rejects any bytes the format
 * does not allow. Memory is a small buffer, not an entry.
 *
 * <p>Use: construct (which checks the header), then call {@link #next()} until it is empty. After
 * each entry, {@link #copyPayload} copies its payload; when the caller does not, the next call to
 * {@code next()} skips it. Only an empty {@code next()} proves the whole object was valid.
 */
final class RegionBucketReader implements Closeable {
    static final byte[] MAGIC = {'W', 'A', 'R', 'B'};

    static final int VERSION = 1;

    private static final int ENTRY_HEADER_BYTES = Short.BYTES + 2 * Integer.BYTES;

    private static final int COPY_BUFFER_BYTES = 64 * 1_024;

    private final DataInputStream input;

    private final int number;

    private int previousIndex = -1;

    private int unreadPayload;

    private boolean finished;

    /** Reads and checks the header of bucket object {@code number}; the reader owns the stream. */
    RegionBucketReader(InputStream input, int number) throws IOException, GitStorageException {
        Objects.requireNonNull(input, "input");
        if (number < 0 || number >= RegionEntry.BUCKETS) {
            throw new IllegalArgumentException("A region bucket number must be between 0 and 15");
        }
        this.input = new DataInputStream(new BufferedInputStream(input));
        this.number = number;
        byte[] header = new byte[MAGIC.length + 2];
        readFully(header, "is too short");
        if (!Arrays.equals(header, 0, MAGIC.length, MAGIC, 0, MAGIC.length)) {
            throw rejected("does not start with the bucket marker");
        }
        if (header[MAGIC.length] != VERSION) {
            throw rejected("has a format version this WorldArchive does not know");
        }
        if (header[MAGIC.length + 1] != number) {
            throw rejected("names a different bucket number");
        }
    }

    /** The next entry, or empty at the end of a valid bucket. Skips the payload not yet copied. */
    Optional<RegionEntry> next() throws IOException, GitStorageException {
        if (finished) {
            return Optional.empty();
        }
        skipFully(unreadPayload);
        unreadPayload = 0;
        int first = input.read();
        if (first < 0) {
            finished = true;
            if (previousIndex < 0) {
                throw rejected("holds no chunk");
            }
            return Optional.empty();
        }
        byte[] rest = new byte[ENTRY_HEADER_BYTES - 1];
        readFully(rest, "ends inside an entry");
        int index = first << 8 | rest[0] & 0xFF;
        int timestamp = readInt(rest, 1);
        int length = readInt(rest, 5);
        RegionEntry entry = validEntry(index, timestamp, length);
        previousIndex = index;
        unreadPayload = length;
        return Optional.of(entry);
    }

    /** Copies the payload of the entry {@link #next()} returned last. */
    void copyPayload(OutputStream output) throws IOException, GitStorageException {
        byte[] buffer = new byte[Math.min(COPY_BUFFER_BYTES, Math.max(unreadPayload, 1))];
        while (unreadPayload > 0) {
            int read = input.read(buffer, 0, Math.min(buffer.length, unreadPayload));
            if (read < 0) {
                throw rejected("ends inside a chunk");
            }
            output.write(buffer, 0, read);
            unreadPayload -= read;
        }
    }

    @Override
    public void close() throws IOException {
        input.close();
    }

    private RegionEntry validEntry(int index, int timestamp, int length) throws GitStorageException {
        if (index >= RegionEntry.CHUNKS || RegionEntry.bucketOf(index) != number) {
            throw rejected("holds a chunk from outside its square");
        }
        if (index <= previousIndex) {
            throw rejected("holds chunks out of order");
        }
        if (length < 0 || length > RegionEntry.MAXIMUM_LENGTH) {
            throw rejected("holds a chunk larger than a region file allows");
        }
        if (length == 0 && timestamp == 0) {
            throw rejected("holds an empty chunk slot");
        }
        return new RegionEntry(index, timestamp, length);
    }

    private void readFully(byte[] bytes, String problem) throws IOException, GitStorageException {
        try {
            input.readFully(bytes);
        } catch (EOFException exception) {
            throw rejected(problem);
        }
    }

    private void skipFully(int count) throws IOException, GitStorageException {
        try {
            input.skipNBytes(count);
        } catch (EOFException exception) {
            throw rejected("ends inside a chunk");
        }
    }

    private static int readInt(byte[] bytes, int offset) {
        return (bytes[offset] & 0xFF) << 24 | (bytes[offset + 1] & 0xFF) << 16
                | (bytes[offset + 2] & 0xFF) << 8 | bytes[offset + 3] & 0xFF;
    }

    private GitStorageException rejected(String problem) {
        return new GitStorageException("Region bucket " + number + " of the snapshot " + problem
                + "; the snapshot is damaged");
    }
}
