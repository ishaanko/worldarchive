package dev.ishaanko.worldarchive.core;

import dev.ishaanko.worldarchive.support.Digests;
import dev.ishaanko.worldarchive.support.PortablePath;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The regular files of one captured world tree, sorted by portable path, with the size and
 * SHA-256 of each. Every destination derives the same two digests from it and every stored
 * manifest holds them, so their framing must never change: {@link #contentSha256()} covers
 * each file's size and digest in path order, and {@link #inventorySha256()} covers each path,
 * size and digest.
 */
public record WorldInventory(List<Entry> files) {
    public static final int MAXIMUM_FILES = 500_000;

    public static final long MAXIMUM_BYTES = 8L * 1_024 * 1_024 * 1_024 * 1_024;

    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");

    /** Requires strictly sorted entries whose paths do not collide on any supported system. */
    public WorldInventory {
        files = List.copyOf(Objects.requireNonNull(files, "files"));
        if (files.size() > MAXIMUM_FILES) {
            throw new IllegalArgumentException("World contains more than " + MAXIMUM_FILES + " files");
        }
        requireSortedWithoutCollisions(files);
        long bytes = 0;
        for (Entry file : files) {
            bytes += file.size();
            if (bytes > MAXIMUM_BYTES) {
                throw new IllegalArgumentException("World is too large to capture");
            }
        }
    }

    /** Sorts the entries by path, then validates them as the canonical constructor does. */
    public static WorldInventory create(List<Entry> entries) {
        List<Entry> sorted = new ArrayList<>(Objects.requireNonNull(entries, "entries"));
        sorted.sort(null);
        return new WorldInventory(sorted);
    }

    public long fileCount() {
        return files.size();
    }

    public long byteCount() {
        return files.stream().mapToLong(Entry::size).sum();
    }

    /** SHA-256 over each file's size (8 bytes, big-endian) and raw digest, in path order. */
    public String contentSha256() {
        MessageDigest digest = Digests.sha256();
        ByteBuffer number = ByteBuffer.allocate(Long.BYTES);
        for (Entry entry : files) {
            putLong(digest, number, entry.size);
            digest.update(HexFormat.of().parseHex(entry.sha256));
        }
        return Digests.hex(digest.digest());
    }

    /**
     * SHA-256 over each file's UTF-8 path length (8 bytes), path bytes, size (8 bytes) and raw
     * digest, in path order.
     */
    public String inventorySha256() {
        MessageDigest digest = Digests.sha256();
        ByteBuffer number = ByteBuffer.allocate(Long.BYTES);
        for (Entry entry : files) {
            byte[] path = entry.path.getBytes(StandardCharsets.UTF_8);
            putLong(digest, number, path.length);
            digest.update(path);
            putLong(digest, number, entry.size);
            digest.update(HexFormat.of().parseHex(entry.sha256));
        }
        return Digests.hex(digest.digest());
    }

    /** Counts additions, modifications, and deletions since the supplied inventory. */
    public long changedFilesSince(WorldInventory previous) {
        Objects.requireNonNull(previous, "previous");
        Map<String, Entry> earlier = new HashMap<>();
        for (Entry entry : previous.files) {
            earlier.put(entry.path, entry);
        }
        long changed = 0;
        for (Entry entry : files) {
            Entry old = earlier.remove(entry.path);
            if (!entry.equals(old)) {
                changed++;
            }
        }
        return changed + earlier.size();
    }

    private static void requireSortedWithoutCollisions(List<Entry> files) {
        String previous = null;
        Set<String> fileKeys = new HashSet<>();
        Set<String> directoryKeys = new HashSet<>();
        for (Entry file : files) {
            Objects.requireNonNull(file, "file");
            if (previous != null && previous.compareTo(file.path) >= 0) {
                throw new IllegalArgumentException("World inventory paths must be strictly sorted");
            }
            String key = PortablePath.collisionKey(file.path);
            if (!fileKeys.add(key) || directoryKeys.contains(key)) {
                throw new IllegalArgumentException(
                        "Path \"" + file.path + "\" collides with another path on Windows or macOS");
            }
            int slash = file.path.indexOf('/');
            while (slash >= 0) {
                String directoryKey = PortablePath.collisionKey(file.path.substring(0, slash));
                if (fileKeys.contains(directoryKey)) {
                    throw new IllegalArgumentException(
                            "Path \"" + file.path + "\" needs a folder where a file of the same name is");
                }
                directoryKeys.add(directoryKey);
                slash = file.path.indexOf('/', slash + 1);
            }
            previous = file.path;
        }
    }

    private static void putLong(MessageDigest digest, ByteBuffer number, long value) {
        number.clear();
        number.putLong(value);
        digest.update(number.array());
    }

    /** One portable regular file in a captured tree. */
    public record Entry(String path, long size, String sha256) implements Comparable<Entry> {
        public Entry {
            path = PortablePath.validate(Objects.requireNonNull(path, "path"));
            if (size < 0 || size > MAXIMUM_BYTES) {
                throw new IllegalArgumentException("Size of \"" + path + "\" is out of range");
            }
            Objects.requireNonNull(sha256, "sha256");
            if (!SHA256.matcher(sha256).matches()) {
                throw new IllegalArgumentException("SHA-256 of \"" + path + "\" is not 64 lowercase hex digits");
            }
        }

        @Override
        public int compareTo(Entry other) {
            return path.compareTo(other.path);
        }
    }
}
