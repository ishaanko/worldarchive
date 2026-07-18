package dev.ishaankot.worldarchive.storage.git;

import dev.ishaankot.worldarchive.model.BackupManifest;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Canonical inventory framing shared by source capture and committed-tree verification. */
record GitInventory(
        List<GitInventoryEntry> files,
        long fileCount,
        long byteCount,
        String contentSha256,
        String inventorySha256) {
    static final int MAXIMUM_FILES = 500_000;

    static final long MAXIMUM_BYTES = 8L * 1_024 * 1_024 * 1_024 * 1_024;

    GitInventory {
        files = List.copyOf(Objects.requireNonNull(files, "files"));
        if (files.size() != fileCount || fileCount < 0 || byteCount < 0) {
            throw new IllegalArgumentException("Git inventory aggregate counts are invalid");
        }
        Objects.requireNonNull(contentSha256, "contentSha256");
        Objects.requireNonNull(inventorySha256, "inventorySha256");
    }

    static GitInventory create(List<GitInventoryEntry> entries) throws GitStorageException {
        if (entries.size() > MAXIMUM_FILES) {
            throw new GitStorageException("World contains too many files for a Git snapshot");
        }
        List<GitInventoryEntry> sorted = new ArrayList<>(entries);
        sorted.sort(GitInventoryEntry::compareTo);
        Set<String> collisionKeys = new HashSet<>();
        long bytes = 0;
        try {
            for (GitInventoryEntry entry : sorted) {
                if (!collisionKeys.add(GitPortablePath.collisionKey(entry.path()))) {
                    throw new GitStorageException("World contains paths that collide on another platform");
                }
                bytes = Math.addExact(bytes, entry.size());
                if (bytes > MAXIMUM_BYTES) {
                    throw new GitStorageException("World is too large for a Git snapshot");
                }
            }
        } catch (ArithmeticException exception) {
            throw new GitStorageException("World size overflowed Git snapshot accounting", exception);
        }
        return new GitInventory(
                sorted,
                sorted.size(),
                bytes,
                contentDigest(sorted),
                inventoryDigest(sorted));
    }

    void requireMatches(BackupManifest manifest) throws GitStorageException {
        if (fileCount != manifest.sourceFileCount()
                || byteCount != manifest.sourceByteCount()
                || !contentSha256.equals(manifest.contentSha256())
                || !inventorySha256.equals(manifest.inventorySha256())) {
            throw new GitStorageException("World contents do not match the prepared backup manifest");
        }
    }

    private static String contentDigest(List<GitInventoryEntry> entries) {
        MessageDigest digest = sha256();
        ByteBuffer number = ByteBuffer.allocate(Long.BYTES);
        for (GitInventoryEntry entry : entries) {
            putLong(digest, number, entry.size());
            digest.update(HexFormat.of().parseHex(entry.sha256()));
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static String inventoryDigest(List<GitInventoryEntry> entries) {
        MessageDigest digest = sha256();
        ByteBuffer number = ByteBuffer.allocate(Long.BYTES);
        for (GitInventoryEntry entry : entries) {
            byte[] path = entry.path().getBytes(StandardCharsets.UTF_8);
            putLong(digest, number, path.length);
            digest.update(path);
            putLong(digest, number, entry.size());
            digest.update(HexFormat.of().parseHex(entry.sha256()));
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static void putLong(MessageDigest digest, ByteBuffer number, long value) {
        number.clear();
        number.putLong(value);
        digest.update(number.array());
    }

    static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("The Java runtime does not provide SHA-256", exception);
        }
    }
}
