package dev.ishaankot.worldarchive.storage.zip;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

/** SHA-256 helpers with an unambiguous, deterministic inventory framing. */
final class ZipDigests {
    static final int COPY_BUFFER_BYTES = 64 * 1024;

    private ZipDigests() {
    }

    static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("The Java runtime does not provide SHA-256", exception);
        }
    }

    static String sha256(Path path) throws IOException {
        MessageDigest digest = sha256();
        try (InputStream input = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
            byte[] buffer = new byte[COPY_BUFFER_BYTES];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read > 0) {
                    digest.update(buffer, 0, read);
                }
            }
        }
        return hex(digest.digest());
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
        return HexFormat.of().formatHex(digest);
    }
}
