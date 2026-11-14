package dev.ishaanko.worldarchive.storage.git;

import dev.ishaanko.worldarchive.support.Digests;
import java.io.Closeable;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Writes the stored region file of a bucketed region, the file a restore puts in the world: the
 * location table gives present chunks sectors in ascending chunk index from sector 2, each chunk
 * is its length field, payload, and zero bytes to the sector boundary, and there are no free
 * sectors. The file is a one-to-one function of the entries, so two entry sets with payloads are
 * equal exactly when their stored files are; a writer proves its buckets describe the captured
 * file by comparing {@link #fromBuckets} with {@link #fromSource}.
 */
final class RegionAssembler {
    private static final byte[] ZEROS = new byte[RegionEntry.SECTOR_BYTES];

    private RegionAssembler() {
    }

    /** Opens one bucket object at its start; called twice per bucket. */
    @FunctionalInterface
    interface BucketStreams {
        InputStream open(int number) throws IOException;
    }

    /** The size and SHA-256 of a stored region file, the {@code stored-*} fields of its index. */
    record StoredRegion(long size, String sha256) {
        StoredRegion {
            Objects.requireNonNull(sha256, "sha256");
        }
    }

    /** Supplies the payload of each entry, called once per entry in ascending chunk index. */
    @FunctionalInterface
    private interface Payloads<E extends Exception> {
        void copy(RegionEntry entry, OutputStream output) throws IOException, E;
    }

    /**
     * Writes the stored region file from bucket objects {@code numbers} (the buckets the tree
     * names). Two passes, so memory stays a few buffers per bucket: the first reads each bucket in
     * turn and skips its payloads to learn every entry's length for the location table; the
     * second opens all buckets at once and copies payloads in ascending chunk index, taking each
     * from its bucket's stream. A bucket that reads differently the second time is rejected. The
     * output is flushed, not closed; pass {@link OutputStream#nullOutputStream()} for the digest
     * alone.
     */
    static StoredRegion fromBuckets(List<Integer> numbers, BucketStreams buckets, OutputStream output)
            throws IOException, GitStorageException {
        Objects.requireNonNull(buckets, "buckets");
        List<Integer> sorted = numbers.stream().sorted().toList();
        if (sorted.stream().distinct().count() != sorted.size()) {
            throw new IllegalArgumentException("A region bucket can be named only once");
        }
        List<RegionEntry> entries = new ArrayList<>();
        for (int number : sorted) {
            try (RegionBucketReader reader = new RegionBucketReader(buckets.open(number), number)) {
                for (Optional<RegionEntry> entry = reader.next(); entry.isPresent(); entry = reader.next()) {
                    entries.add(entry.get());
                }
            }
        }
        entries.sort(Comparator.comparingInt(RegionEntry::index));
        RegionBucketReader[] readers = new RegionBucketReader[RegionEntry.BUCKETS];
        try (Closeable closeReaders = () -> closeAll(readers)) {
            for (int number : sorted) {
                readers[number] = new RegionBucketReader(buckets.open(number), number);
            }
            Payloads<GitStorageException> payloads = (entry, payload) -> {
                RegionBucketReader reader = readers[entry.bucket()];
                if (!reader.next().equals(Optional.of(entry))) {
                    throw changed(entry.bucket());
                }
                reader.copyPayload(payload);
            };
            StoredRegion stored = write(entries, payloads, output);
            for (int number : sorted) {
                if (readers[number].next().isPresent()) {
                    throw changed(number);
                }
            }
            return stored;
        }
    }

    /** Writes the stored region file straight from a parsed source file, read as {@link RegionLayout} reads it. */
    static StoredRegion fromSource(RegionLayout layout, SeekableByteChannel source, OutputStream output)
            throws IOException {
        Objects.requireNonNull(source, "source");
        Payloads<IOException> payloads = (entry, payload) -> layout.copyPayload(entry, source, payload);
        return write(layout.entries(), payloads, output);
    }

    private static <E extends Exception> StoredRegion write(List<RegionEntry> entries, Payloads<E> payloads,
            OutputStream output) throws IOException, E {
        ByteBuffer tables = ByteBuffer.allocate(RegionLayout.TABLES_BYTES);
        int sector = 2;
        for (RegionEntry entry : entries) {
            if (entry.present()) {
                tables.putInt(entry.index() * Integer.BYTES, sector << 8 | entry.sectors());
                sector += entry.sectors();
            }
            tables.putInt(RegionEntry.SECTOR_BYTES + entry.index() * Integer.BYTES, entry.timestamp());
        }
        MessageDigest digest = Digests.sha256();
        DataOutputStream data = new DataOutputStream(new DigestOutputStream(Objects.requireNonNull(output, "output"),
                digest));
        data.write(tables.array());
        for (RegionEntry entry : entries) {
            if (entry.present()) {
                data.writeInt(entry.length());
            }
            payloads.copy(entry, data);
            if (entry.present()) {
                data.write(ZEROS, 0, entry.sectors() * RegionEntry.SECTOR_BYTES - Integer.BYTES - entry.length());
            }
        }
        data.flush();
        return new StoredRegion((long) sector * RegionEntry.SECTOR_BYTES, Digests.hex(digest.digest()));
    }

    private static GitStorageException changed(int number) {
        return new GitStorageException("Region bucket " + number
                + " of the snapshot changed while it was being read; try again");
    }

    private static void closeAll(RegionBucketReader[] readers) throws IOException {
        IOException failure = null;
        for (RegionBucketReader reader : readers) {
            if (reader == null) {
                continue;
            }
            try {
                reader.close();
            } catch (IOException exception) {
                if (failure == null) {
                    failure = exception;
                } else {
                    failure.addSuppressed(exception);
                }
            }
        }
        if (failure != null) {
            throw failure;
        }
    }
}
