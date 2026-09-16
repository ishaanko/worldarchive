package dev.ishaanko.worldarchive.storage.zip;

import dev.ishaanko.worldarchive.core.Digests;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;

/** Deterministic SHA-256 framing for ZIP inventories; plain hashing lives in {@link Digests}. */
final class ZipDigests {
    private ZipDigests() {
    }

    static String inventorySha256(List<ZipInventoryEntry> entries) {
        MessageDigest digest = Digests.sha256();
        ByteBuffer number = ByteBuffer.allocate(Long.BYTES);
        for (ZipInventoryEntry entry : entries) {
            byte[] path = entry.path().getBytes(StandardCharsets.UTF_8);
            number.clear();
            number.putLong(path.length);
            digest.update(number.array());
            digest.update(path);
            number.clear();
            number.putLong(entry.size());
            digest.update(number.array());
            digest.update(HexFormat.of().parseHex(entry.sha256()));
        }
        return Digests.hex(digest.digest());
    }

    static String contentSha256(List<ZipInventoryEntry> entries) {
        MessageDigest digest = Digests.sha256();
        ByteBuffer number = ByteBuffer.allocate(Long.BYTES);
        for (ZipInventoryEntry entry : entries) {
            number.clear();
            number.putLong(entry.size());
            digest.update(number.array());
            digest.update(HexFormat.of().parseHex(entry.sha256()));
        }
        return Digests.hex(digest.digest());
    }
}
