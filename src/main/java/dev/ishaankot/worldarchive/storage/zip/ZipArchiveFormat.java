package dev.ishaankot.worldarchive.storage.zip;

/** Stable archive namespace shared by creation, verification, and restoration. */
final class ZipArchiveFormat {
    static final String METADATA_PREFIX = "META-INF/worldarchive/";

    static final String MANIFEST_ENTRY = METADATA_PREFIX + "manifest.json";

    static final String INVENTORY_ENTRY = METADATA_PREFIX + "inventory.json";

    static final String WORLD_PREFIX = "world/";

    static final int MAXIMUM_MANIFEST_BYTES = 1_024 * 1_024;

    static final int MAXIMUM_INVENTORY_BYTES = 64 * 1_024 * 1_024;

    private ZipArchiveFormat() {
    }
}
