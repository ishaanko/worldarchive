package dev.ishaanko.worldarchive.storage.git;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Random;

/** Builds region file bytes for tests: every byte not set is stale noise, as in a real file. */
final class RegionFileBuilder {
    private final ByteBuffer file;

    /** A file of {@code sectors} sectors with empty tables and noise everywhere else. */
    RegionFileBuilder(int sectors) {
        byte[] bytes = new byte[sectors * RegionEntry.SECTOR_BYTES];
        new Random(sectors).nextBytes(bytes);
        Arrays.fill(bytes, 0, Math.min(bytes.length, RegionLayout.TABLES_BYTES), (byte) 0);
        file = ByteBuffer.wrap(bytes);
    }

    /** Places a chunk: its location entry, length field, and payload; the rest of its sectors stays noise. */
    RegionFileBuilder chunk(int index, int sector, int count, byte[] payload) {
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

    RegionFileBuilder timestamp(int index, int value) {
        file.putInt(RegionEntry.SECTOR_BYTES + index * Integer.BYTES, value);
        return this;
    }

    byte[] bytes() {
        return file.array().clone();
    }

    /** A payload: a compression-type byte, then deterministic data of {@code length - 1} bytes. */
    static byte[] payload(int length, int seed) {
        byte[] payload = new byte[length];
        new Random(seed).nextBytes(payload);
        payload[0] = 2;
        return payload;
    }
}
