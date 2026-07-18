package dev.ishaankot.worldarchive.storage.zip;

import java.util.Objects;
import java.util.regex.Pattern;

/** One regular file recorded in an archive inventory. */
record ZipInventoryEntry(String path, long size, String sha256) implements Comparable<ZipInventoryEntry> {
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");

    ZipInventoryEntry {
        path = PortableZipPath.validate(Objects.requireNonNull(path, "path"), false);
        if (size < 0) {
            throw new IllegalArgumentException("Inventory file size must not be negative");
        }
        Objects.requireNonNull(sha256, "sha256");
        if (!SHA256.matcher(sha256).matches()) {
            throw new IllegalArgumentException("Inventory checksum must be lowercase SHA-256");
        }
    }

    @Override
    public int compareTo(ZipInventoryEntry other) {
        return path.compareTo(other.path);
    }
}
