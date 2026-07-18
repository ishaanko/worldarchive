package dev.ishaankot.worldarchive.core;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/** Canonical identity of the regular files in one captured world tree. */
public record WorldInventory(
        List<Entry> files,
        long fileCount,
        long byteCount,
        String contentSha256,
        String inventorySha256) {
    public static final int MAXIMUM_FILES = 500_000;

    public static final long MAXIMUM_BYTES = 8L * 1_024 * 1_024 * 1_024 * 1_024;

    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");

    public WorldInventory {
        files = List.copyOf(Objects.requireNonNull(files, "files"));
        if (files.size() != fileCount || fileCount < 0 || fileCount > MAXIMUM_FILES) {
            throw new IllegalArgumentException("World inventory file count is invalid");
        }
        if (byteCount < 0 || byteCount > MAXIMUM_BYTES) {
            throw new IllegalArgumentException("World inventory byte count is invalid");
        }
        requireSha256(contentSha256, "contentSha256");
        requireSha256(inventorySha256, "inventorySha256");

        String previous = null;
        long actualBytes = 0;
        Set<String> collisionKeys = new HashSet<>();
        Set<String> directoryKeys = new HashSet<>();
        try {
            for (Entry file : files) {
                Objects.requireNonNull(file, "file");
                if (previous != null && previous.compareTo(file.path()) >= 0) {
                    throw new IllegalArgumentException("World inventory paths must be strictly sorted");
                }
                String collisionKey = PortableWorldPath.collisionKey(file.path());
                if (!collisionKeys.add(collisionKey) || directoryKeys.contains(collisionKey)) {
                    throw new IllegalArgumentException("World inventory contains colliding paths");
                }
                String[] segments = file.path().split("/");
                StringBuilder prefix = new StringBuilder();
                for (int index = 0; index < segments.length - 1; index++) {
                    if (!prefix.isEmpty()) {
                        prefix.append('/');
                    }
                    prefix.append(segments[index]);
                    String prefixKey = PortableWorldPath.collisionKey(prefix.toString());
                    if (collisionKeys.contains(prefixKey)) {
                        throw new IllegalArgumentException(
                                "World inventory contains a file/directory path conflict");
                    }
                    directoryKeys.add(prefixKey);
                }
                actualBytes = Math.addExact(actualBytes, file.size());
                previous = file.path();
            }
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("World inventory size overflowed", exception);
        }
        if (actualBytes != byteCount) {
            throw new IllegalArgumentException("World inventory byte count does not match its files");
        }
        if (!contentSha256.equals(contentDigest(files))
                || !inventorySha256.equals(inventoryDigest(files))) {
            throw new IllegalArgumentException("World inventory digest does not match its files");
        }
    }

    public static WorldInventory create(List<Entry> entries) {
        Objects.requireNonNull(entries, "entries");
        if (entries.size() > MAXIMUM_FILES) {
            throw new IllegalArgumentException("World contains too many files");
        }
        List<Entry> sorted = new ArrayList<>(entries);
        sorted.sort(Entry::compareTo);
        long bytes = 0;
        try {
            for (Entry entry : sorted) {
                bytes = Math.addExact(bytes, Objects.requireNonNull(entry, "entry").size());
                if (bytes > MAXIMUM_BYTES) {
                    throw new IllegalArgumentException("World is too large to capture");
                }
            }
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("World size overflowed", exception);
        }
        return new WorldInventory(
                sorted,
                sorted.size(),
                bytes,
                contentDigest(sorted),
                inventoryDigest(sorted));
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

    public boolean hasSameFiles(WorldInventory other) {
        Objects.requireNonNull(other, "other");
        return inventorySha256.equals(other.inventorySha256);
    }

    private static String contentDigest(List<Entry> entries) {
        MessageDigest digest = sha256();
        ByteBuffer number = ByteBuffer.allocate(Long.BYTES);
        for (Entry entry : entries) {
            putLong(digest, number, entry.size);
            digest.update(HexFormat.of().parseHex(entry.sha256));
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static String inventoryDigest(List<Entry> entries) {
        MessageDigest digest = sha256();
        ByteBuffer number = ByteBuffer.allocate(Long.BYTES);
        for (Entry entry : entries) {
            byte[] path = entry.path.getBytes(StandardCharsets.UTF_8);
            putLong(digest, number, path.length);
            digest.update(path);
            putLong(digest, number, entry.size);
            digest.update(HexFormat.of().parseHex(entry.sha256));
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("The Java runtime does not provide SHA-256", exception);
        }
    }

    private static void putLong(MessageDigest digest, ByteBuffer number, long value) {
        number.clear();
        number.putLong(value);
        digest.update(number.array());
    }

    private static void requireSha256(String value, String name) {
        Objects.requireNonNull(value, name);
        if (!SHA256.matcher(value).matches()) {
            throw new IllegalArgumentException(name + " must be lowercase SHA-256");
        }
    }

    /** One portable regular file in a captured tree. */
    public record Entry(String path, long size, String sha256) implements Comparable<Entry> {
        public Entry {
            path = PortableWorldPath.validate(Objects.requireNonNull(path, "path"));
            if (size < 0 || size > MAXIMUM_BYTES) {
                throw new IllegalArgumentException("World inventory entry size is invalid");
            }
            requireSha256(sha256, "sha256");
        }

        @Override
        public int compareTo(Entry other) {
            return path.compareTo(other.path);
        }
    }
}
