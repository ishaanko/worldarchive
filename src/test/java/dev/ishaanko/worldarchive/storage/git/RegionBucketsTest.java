package dev.ishaanko.worldarchive.storage.git;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.ishaanko.worldarchive.storage.git.RegionAssembler.StoredRegion;
import dev.ishaanko.worldarchive.support.Digests;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Region files survive the trip through buckets chunk for chunk, and a damaged bucket is rejected. */
class RegionBucketsTest {
    private static final int SECTOR = RegionEntry.SECTOR_BYTES;

    private static final byte[] EXTERNAL_MARKER = {(byte) 130};

    @TempDir
    Path directory;

    @Test
    void roundTripsChunksThroughBuckets() throws Exception {
        byte[] source = scattered(new RegionFileBuilder(40), 0).timestamp(600, 1_234).bytes();
        RegionLayout layout = parse(source);
        Map<Integer, byte[]> buckets = encode(layout, source);

        assertEquals(List.of(0, 1, 5, 11, 15), List.copyOf(buckets.keySet()));
        ByteArrayOutputStream assembled = new ByteArrayOutputStream();
        StoredRegion stored = RegionAssembler.fromBuckets(List.copyOf(buckets.keySet()), streams(buckets), assembled);
        byte[] storedFile = assembled.toByteArray();

        RegionLayout reparsed = parse(storedFile);
        assertEquals(layout.entries(), reparsed.entries());
        assertTrue(layout.entries().contains(new RegionEntry(600, 1_234, 0)));
        assertTrue(layout.entries().contains(new RegionEntry(1_023, 5, 1)));
        assertEquals(payloads(source), payloads(storedFile));
        assertEquals(new StoredRegion(storedFile.length, Digests.hex(Digests.sha256().digest(storedFile))), stored);
        assertCompacted(storedFile);
        assertEquals(stored, fromSource(source, OutputStream.nullOutputStream()));
    }

    @Test
    void sameChunksAtOtherSectorsGiveTheSameBytes() throws Exception {
        byte[] first = scattered(new RegionFileBuilder(40), 0).bytes();
        byte[] second = scattered(new RegionFileBuilder(60), 15).bytes();

        Map<Integer, byte[]> firstBuckets = encode(parse(first), first);
        Map<Integer, byte[]> secondBuckets = encode(parse(second), second);

        assertEquals(firstBuckets.keySet(), secondBuckets.keySet());
        firstBuckets.forEach((number, bytes) -> assertArrayEquals(bytes, secondBuckets.get(number)));
        ByteArrayOutputStream firstStored = new ByteArrayOutputStream();
        ByteArrayOutputStream secondStored = new ByteArrayOutputStream();
        fromSource(first, firstStored);
        fromSource(second, secondStored);
        assertArrayEquals(firstStored.toByteArray(), secondStored.toByteArray());
    }

    @Test
    void changingOneChunkChangesOnlyItsBucket() throws Exception {
        byte[] original = scattered(new RegionFileBuilder(40), 0).bytes();
        byte[] changed = scattered(new RegionFileBuilder(40), 0)
                .chunk(300, 25, 4, RegionFileBuilder.payload(9_000, 99)).bytes();

        Map<Integer, byte[]> before = encode(parse(original), original);
        Map<Integer, byte[]> after = encode(parse(changed), changed);

        List<Integer> differing = before.keySet().stream()
                .filter(number -> !Arrays.equals(before.get(number), after.get(number))).toList();
        assertEquals(List.of(RegionEntry.bucketOf(300)), differing);
    }

    @Test
    void assemblesAnEmptyRegion() throws Exception {
        byte[] source = new RegionFileBuilder(2).timestamp(40, 3).bytes();
        RegionLayout layout = parse(source);
        Map<Integer, byte[]> buckets = encode(layout, source);
        ByteArrayOutputStream assembled = new ByteArrayOutputStream();

        RegionAssembler.fromBuckets(List.copyOf(buckets.keySet()), streams(buckets), assembled);

        assertArrayEquals(source, assembled.toByteArray());
        ByteArrayOutputStream none = new ByteArrayOutputStream();
        assertEquals(RegionLayout.TABLES_BYTES, RegionAssembler.fromBuckets(List.of(), streams(buckets), none).size());
        assertArrayEquals(new byte[RegionLayout.TABLES_BYTES], none.toByteArray());
    }

    @Test
    void rejectsABucketThatChangesBetweenPasses() throws Exception {
        byte[] source = scattered(new RegionFileBuilder(40), 0).bytes();
        Map<Integer, byte[]> buckets = encode(parse(source), source);
        byte[] other = bucket(0, entry(0, 1, new byte[] {2, 3}));
        AtomicInteger opens = new AtomicInteger();

        assertThrows(GitStorageException.class, () -> RegionAssembler.fromBuckets(List.copyOf(buckets.keySet()),
                number -> new ByteArrayInputStream(number == 0 && opens.getAndIncrement() > 0
                        ? other : buckets.get(number)),
                OutputStream.nullOutputStream()));
    }

    @Test
    void decodesAValidBucket() throws Exception {
        byte[] bytes = bucket(5, entry(268, 4, new byte[] {2, 9, 9}), entry(301, 8, new byte[0]));

        assertEquals(List.of(new RegionEntry(268, 4, 3), new RegionEntry(301, 8, 0)), decode(bytes, 5));
    }

    @Test
    void rejectsEveryDamagedBucket() throws IOException {
        byte[] good = entry(268, 4, new byte[] {2, 9, 9});
        byte[] valid = bucket(5, good);
        List<byte[]> damaged = List.of(
                Arrays.copyOf(valid, 4),
                replaced(valid, 0, (byte) 'X'),
                replaced(valid, 4, (byte) 2),
                replaced(valid, 5, (byte) 6),
                bucket(5),
                bucket(5, entry(301, 4, new byte[] {1}), good),
                bucket(5, good, good),
                bucket(5, entry(0, 4, new byte[] {1})),
                bucket(5, entry(4_000, 4, new byte[] {1})),
                Arrays.copyOf(valid, valid.length - 1),
                Arrays.copyOf(valid, 6 + 5),
                concat(valid, new byte[] {0, 1}),
                bucket(5, entry(268, 0, new byte[0])),
                bucket(5, header(268, 4, RegionEntry.MAXIMUM_LENGTH + 1)),
                bucket(5, header(268, 4, -1)));

        for (byte[] bytes : damaged) {
            assertThrows(GitStorageException.class, () -> decode(bytes, 5), HexFormat.of().formatHex(bytes));
        }
    }

    /**
     * Six chunks over five buckets, at sectors that depend on {@code shift}: below one sector,
     * exactly one sector, several sectors, an allocation larger than needed, and an external-chunk
     * marker, with noise in every tail and free sector.
     */
    private static RegionFileBuilder scattered(RegionFileBuilder builder, int shift) {
        return builder
                .chunk(1_023, 4 + shift, 1, EXTERNAL_MARKER).timestamp(1_023, 5)
                .chunk(0, 30 + shift, 1, RegionFileBuilder.payload(100, 1)).timestamp(0, 6)
                .chunk(9, 2 + shift, 1, RegionFileBuilder.payload(SECTOR - 4, 2))
                .chunk(37, 10 + shift, 3, RegionFileBuilder.payload(3 * SECTOR - 4 - 1_000, 3)).timestamp(37, -7)
                .chunk(300, 25 + shift, 4, RegionFileBuilder.payload(9_000, 4))
                .chunk(40, 35 + shift, 2, RegionFileBuilder.payload(SECTOR + 1, 5));
    }

    /** Requires sectors in chunk order from sector 2, no free sector, and zero bytes in every tail. */
    private static void assertCompacted(byte[] file) {
        ByteBuffer buffer = ByteBuffer.wrap(file);
        int nextSector = 2;
        for (int index = 0; index < RegionEntry.CHUNKS; index++) {
            int location = buffer.getInt(index * Integer.BYTES);
            if (location == 0) {
                continue;
            }
            assertEquals(nextSector, location >>> 8);
            int start = nextSector * SECTOR;
            int end = start + (location & 0xFF) * SECTOR;
            int length = buffer.getInt(start);
            assertEquals((4 + length + SECTOR - 1) / SECTOR, location & 0xFF);
            assertTrue(IntStream.range(start + 4 + length, end).allMatch(i -> file[i] == 0));
            nextSector += location & 0xFF;
        }
        assertEquals(nextSector * SECTOR, file.length);
    }

    /** Each present chunk's payload, read straight from the file bytes. */
    private static Map<Integer, String> payloads(byte[] file) {
        ByteBuffer buffer = ByteBuffer.wrap(file);
        Map<Integer, String> payloads = new TreeMap<>();
        for (int index = 0; index < RegionEntry.CHUNKS; index++) {
            int location = buffer.getInt(index * Integer.BYTES);
            if (location != 0) {
                int start = (location >>> 8) * SECTOR;
                int length = buffer.getInt(start);
                payloads.put(index, HexFormat.of().formatHex(file, start + 4, start + 4 + length));
            }
        }
        return payloads;
    }

    private RegionLayout parse(byte[] file) throws IOException {
        Path path = Files.write(Files.createTempFile(directory, "r", ".mca"), file);
        try (FileChannel channel = FileChannel.open(path)) {
            return RegionLayout.parse(channel).orElseThrow();
        }
    }

    private Map<Integer, byte[]> encode(RegionLayout layout, byte[] file) throws IOException {
        Path path = Files.write(Files.createTempFile(directory, "r", ".mca"), file);
        Map<Integer, byte[]> buckets = new TreeMap<>();
        try (FileChannel channel = FileChannel.open(path)) {
            for (int number : layout.bucketNumbers()) {
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                layout.writeBucket(number, channel, bytes);
                buckets.put(number, bytes.toByteArray());
            }
        }
        return buckets;
    }

    private static RegionAssembler.BucketStreams streams(Map<Integer, byte[]> buckets) {
        return number -> new ByteArrayInputStream(buckets.get(number));
    }

    private StoredRegion fromSource(byte[] file, OutputStream output) throws IOException {
        Path path = Files.write(Files.createTempFile(directory, "r", ".mca"), file);
        try (FileChannel channel = FileChannel.open(path)) {
            return RegionAssembler.fromSource(RegionLayout.parse(channel).orElseThrow(), channel, output);
        }
    }

    private static List<RegionEntry> decode(byte[] bytes, int number) throws IOException, GitStorageException {
        try (RegionBucketReader reader = new RegionBucketReader(new ByteArrayInputStream(bytes), number)) {
            List<RegionEntry> entries = new ArrayList<>();
            for (Optional<RegionEntry> entry = reader.next(); entry.isPresent(); entry = reader.next()) {
                entries.add(entry.get());
                reader.copyPayload(OutputStream.nullOutputStream());
            }
            return entries;
        }
    }

    private static byte[] bucket(int number, byte[]... entries) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        bytes.write(new byte[] {'W', 'A', 'R', 'B', 1, (byte) number});
        for (byte[] entry : entries) {
            bytes.write(entry);
        }
        return bytes.toByteArray();
    }

    private static byte[] entry(int index, int timestamp, byte[] payload) throws IOException {
        return concat(header(index, timestamp, payload.length), payload);
    }

    private static byte[] header(int index, int timestamp, int length) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream data = new DataOutputStream(bytes);
        data.writeShort(index);
        data.writeInt(timestamp);
        data.writeInt(length);
        return bytes.toByteArray();
    }

    private static byte[] replaced(byte[] bytes, int offset, byte value) {
        byte[] copy = bytes.clone();
        copy[offset] = value;
        return copy;
    }

    private static byte[] concat(byte[] first, byte[] second) {
        byte[] joined = Arrays.copyOf(first, first.length + second.length);
        System.arraycopy(second, 0, joined, first.length, second.length);
        return joined;
    }
}
