package dev.ishaanko.worldarchive.storage.git;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.TreeMap;

/**
 * Builds region file bytes for tests: every byte not set is stale noise, as in a real file. Public
 * for the end-to-end tests, which also read region files back with {@link #chunks}.
 */
public final class RegionFileBuilder {
    private final ByteBuffer file;

    /** A file of {@code sectors} sectors with empty tables and noise everywhere else. */
    public RegionFileBuilder(int sectors) {
        byte[] bytes = new byte[sectors * RegionEntry.SECTOR_BYTES];
        new Random(sectors).nextBytes(bytes);
        Arrays.fill(bytes, 0, Math.min(bytes.length, RegionLayout.TABLES_BYTES), (byte) 0);
        file = ByteBuffer.wrap(bytes);
    }

    /** Places a chunk: its location entry, length field, and payload; the rest of its sectors stays noise. */
    public RegionFileBuilder chunk(int index, int sector, int count, byte[] payload) {
        location(index, sector << 8 | count);
        file.putInt(sector * RegionEntry.SECTOR_BYTES, payload.length);
        file.put(sector * RegionEntry.SECTOR_BYTES + Integer.BYTES, payload);
        return this;
    }

    RegionFileBuilder location(int index, int value) {
        file.putInt(index * Integer.BYTES, value);
        return this;
    }

    RegionFileBuilder lengthField(int sector, int value) {
        file.putInt(sector * RegionEntry.SECTOR_BYTES, value);
        return this;
    }

    public RegionFileBuilder timestamp(int index, int value) {
        file.putInt(RegionEntry.SECTOR_BYTES + index * Integer.BYTES, value);
        return this;
    }

    public byte[] bytes() {
        return file.array().clone();
    }

    /** A payload: a compression-type byte, then deterministic data of {@code length - 1} bytes. */
    public static byte[] payload(int length, int seed) {
        byte[] payload = new byte[length];
        new Random(seed).nextBytes(payload);
        payload[0] = 2;
        return payload;
    }

    /**
     * A region file as the game leaves it: {@code chunks} chunks of 100 to about 9,000 bytes spread
     * over every bucket, a third of them with a spare sector of stale bytes, a free sector after
     * every fourth, and a cleared chunk (slot 1023) whose timestamp stays. Chunk {@code k} has
     * payload seed {@code seeds[k]} when given, else {@code k}, so changing one seed changes one chunk.
     */
    public static byte[] scattered(int chunks, int... seeds) {
        RegionFileBuilder builder = new RegionFileBuilder(3 + chunks * 5);
        int sector = 3;
        for (int k = 0; k < chunks; k++) {
            int index = k * 37 % 1_023;
            int length = 100 + k * 911 % 9_000;
            int count = (Integer.BYTES + length + RegionEntry.SECTOR_BYTES - 1) / RegionEntry.SECTOR_BYTES
                    + (k % 3 == 0 ? 1 : 0);
            builder.chunk(index, sector, count, payload(length, k < seeds.length ? seeds[k] : k))
                    .timestamp(index, 1_700_000_000 + k);
            sector += count + (k % 4 == 0 ? 1 : 0);
        }
        return builder.timestamp(1_023, 1_650_000_000).bytes();
    }

    /**
     * Every chunk slot a region file keeps, by index: its timestamp and payload in hex, or its
     * timestamp alone for a cleared chunk. The file must pass the strict parse.
     */
    public static Map<Integer, String> chunks(Path file) throws IOException {
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            Optional<RegionLayout> layout = RegionLayout.parse(channel);
            if (layout.isEmpty()) {
                throw new AssertionError(file + " does not pass the strict region parse");
            }
            Map<Integer, String> chunks = new TreeMap<>();
            for (RegionEntry entry : layout.get().entries()) {
                ByteArrayOutputStream payload = new ByteArrayOutputStream();
                layout.get().copyPayload(entry, channel, payload);
                chunks.put(entry.index(), entry.timestamp() + " " + HexFormat.of().formatHex(payload.toByteArray()));
            }
            return chunks;
        }
    }
}
