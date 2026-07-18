package dev.ishaankot.worldarchive.storage.zip;

import dev.ishaankot.worldarchive.model.BackupManifest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Versioned per-file integrity inventory embedded in each archive. */
record ZipInventory(int formatVersion, List<ZipInventoryEntry> files, String inventorySha256) {
    static final int CURRENT_FORMAT_VERSION = 1;

    ZipInventory {
        if (formatVersion != CURRENT_FORMAT_VERSION) {
            throw new IllegalArgumentException("Unsupported ZIP inventory format version");
        }
        if (files.size() > ZipLimits.MAXIMUM_INVENTORY_FILES) {
            throw new IllegalArgumentException("ZIP inventory exceeds its file limit");
        }
        files = List.copyOf(files);
        Set<String> collisionKeys = new HashSet<>();
        Set<String> directoryKeys = new HashSet<>();
        String previous = null;
        for (ZipInventoryEntry file : files) {
            Objects.requireNonNull(file, "file");
            if (previous != null && previous.compareTo(file.path()) >= 0) {
                throw new IllegalArgumentException("ZIP inventory paths are not strictly sorted");
            }
            String collisionKey = PortableZipPath.collisionKey(file.path(), false);
            if (!collisionKeys.add(collisionKey) || directoryKeys.contains(collisionKey)) {
                throw new IllegalArgumentException("ZIP inventory contains colliding paths");
            }
            String[] segments = file.path().split("/");
            StringBuilder prefix = new StringBuilder();
            for (int index = 0; index < segments.length - 1; index++) {
                if (!prefix.isEmpty()) {
                    prefix.append('/');
                }
                prefix.append(segments[index]);
                String prefixKey = PortableZipPath.collisionKey(prefix.toString(), false);
                if (collisionKeys.contains(prefixKey)) {
                    throw new IllegalArgumentException(
                            "ZIP inventory contains a file/directory path conflict");
                }
                directoryKeys.add(prefixKey);
            }
            previous = file.path();
        }
        Objects.requireNonNull(inventorySha256, "inventorySha256");
        String expected = ZipDigests.inventorySha256(files);
        if (!inventorySha256.equals(expected)) {
            throw new IllegalArgumentException("ZIP inventory digest does not match its entries");
        }
    }

    static ZipInventory create(List<ZipInventoryEntry> files) {
        if (files.size() > ZipLimits.MAXIMUM_INVENTORY_FILES) {
            throw new IllegalArgumentException("ZIP inventory exceeds its file limit");
        }
        List<ZipInventoryEntry> sorted = new ArrayList<>(files);
        sorted.sort(null);
        return new ZipInventory(CURRENT_FORMAT_VERSION, sorted, ZipDigests.inventorySha256(sorted));
    }

    long fileCount() {
        return files.size();
    }

    long byteCount() {
        long total = 0;
        try {
            for (ZipInventoryEntry file : files) {
                total = Math.addExact(total, file.size());
            }
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("ZIP inventory byte count overflow", exception);
        }
        return total;
    }

    boolean matches(BackupManifest manifest) {
        Objects.requireNonNull(manifest, "manifest");
        return fileCount() == manifest.sourceFileCount()
                && byteCount() == manifest.sourceByteCount()
                && ZipDigests.contentSha256(files).equals(manifest.contentSha256())
                && inventorySha256.equals(manifest.inventorySha256());
    }
}
