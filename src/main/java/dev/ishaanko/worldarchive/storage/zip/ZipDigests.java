package dev.ishaanko.worldarchive.storage.zip;

import dev.ishaanko.worldarchive.core.Digests;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;

/** SHA-256 helpers with an unambiguous, deterministic inventory framing. */
final class ZipDigests {
    static final int COPY_BUFFER_BYTES = 64 * 1024;

    private ZipDigests() {
    }

    static MessageDigest sha256() {
        return Digests.sha256();
    }

    static String sha256(Path path) throws IOException {
        return Digests.sha256(path);
    }

    static String inventorySha256(List<ZipInventoryEntry> entries) {
        MessageDigest digest = sha256();
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
        return hex(digest.digest());
    }

    static String contentSha256(List<ZipInventoryEntry> entries) {
        MessageDigest digest = sha256();
        ByteBuffer number = ByteBuffer.allocate(Long.BYTES);
        for (ZipInventoryEntry entry : entries) {
            number.clear();
            number.putLong(entry.size());
            digest.update(number.array());
            digest.update(HexFormat.of().parseHex(entry.sha256()));
        }
        return hex(digest.digest());
    }

    static String hex(byte[] digest) {
        return Digests.hex(digest);
    }
}
