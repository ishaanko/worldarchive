package dev.ishaanko.worldarchive.storage.zip;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.ishaanko.worldarchive.core.WorldInventory;
import dev.ishaanko.worldarchive.model.BackupManifest;
import dev.ishaanko.worldarchive.model.BackupManifestJson;
import dev.ishaanko.worldarchive.support.JsonFields;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * The layout every WorldArchive ZIP has had since 0.1.0, and the JSON of its two metadata
 * entries. The manifest is the first entry, the world follows under {@code world/} with an
 * entry for every folder, and the inventory, with the size and SHA-256 of each file, is the last
 * entry. Stored archives depend on the names and on the JSON bytes, so change neither.
 */
final class ZipArchiveFormat {
    static final String MANIFEST_ENTRY = "META-INF/worldarchive/manifest.json";

    static final String INVENTORY_ENTRY = "META-INF/worldarchive/inventory.json";

    static final String WORLD_PREFIX = "world/";

    /** Far above any real manifest; bounds what a damaged archive can make a reader load. */
    static final int MAXIMUM_MANIFEST_BYTES = 1 << 20;

    /** A world at the {@link WorldInventory#MAXIMUM_FILES} limit needs about 100 MiB of JSON. */
    static final int MAXIMUM_INVENTORY_BYTES = 256 << 20;

    private static final int INVENTORY_FORMAT_VERSION = 1;

    private static final String DIGEST_ALGORITHM = "SHA-256";

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private static final JsonFields<IOException> JSON = new JsonFields<>(IOException::new);

    private ZipArchiveFormat() {
    }

    static byte[] encodeManifest(BackupManifest manifest) {
        JsonObject root = new JsonObject();
        BackupManifestJson.write(manifest, root);
        return encode(root);
    }

    static BackupManifest decodeManifest(byte[] encoded) throws ZipArchiveDamagedException {
        JsonObject root;
        int formatVersion;
        try {
            root = JSON.parseObject(new String(encoded, StandardCharsets.UTF_8), "ZIP manifest");
            formatVersion = JSON.requiredInt(root, "formatVersion");
        } catch (IOException exception) {
            throw new ZipArchiveDamagedException("The backup manifest inside the ZIP file cannot be read.", exception);
        }
        if (formatVersion > BackupManifest.CURRENT_FORMAT_VERSION) {
            throw new ZipArchiveDamagedException(
                    "A newer version of WorldArchive made this ZIP backup. Update WorldArchive to use it.");
        }
        try {
            return BackupManifestJson.read(root, JSON);
        } catch (IOException | IllegalArgumentException exception) {
            throw new ZipArchiveDamagedException("The backup manifest inside the ZIP file cannot be read.", exception);
        }
    }

    static byte[] encodeInventory(WorldInventory inventory) {
        JsonObject root = new JsonObject();
        root.addProperty("formatVersion", INVENTORY_FORMAT_VERSION);
        root.addProperty("algorithm", DIGEST_ALGORITHM);
        root.addProperty("fileCount", inventory.fileCount());
        root.addProperty("byteCount", inventory.byteCount());
        root.addProperty("inventorySha256", inventory.inventorySha256());
        JsonArray files = new JsonArray();
        for (WorldInventory.Entry file : inventory.files()) {
            JsonObject encoded = new JsonObject();
            encoded.addProperty("path", file.path());
            encoded.addProperty("size", file.size());
            encoded.addProperty("sha256", file.sha256());
            files.add(encoded);
        }
        root.add("files", files);
        return encode(root);
    }

    /** Decodes an inventory whose entries are sorted, portable, and agree with its own totals. */
    static WorldInventory decodeInventory(byte[] encoded) throws ZipArchiveDamagedException {
        try {
            JsonObject root = JSON.parseObject(new String(encoded, StandardCharsets.UTF_8), "ZIP inventory");
            if (JSON.requiredInt(root, "formatVersion") != INVENTORY_FORMAT_VERSION
                    || !DIGEST_ALGORITHM.equals(JSON.requiredString(root, "algorithm"))) {
                throw new IOException("Unsupported inventory format");
            }
            List<JsonObject> files = JSON.requiredObjects(root, "files");
            List<WorldInventory.Entry> entries = new ArrayList<>(files.size());
            for (JsonObject file : files) {
                entries.add(new WorldInventory.Entry(
                        JSON.requiredString(file, "path"),
                        JSON.requiredLong(file, "size"),
                        JSON.requiredString(file, "sha256")));
            }
            WorldInventory inventory = new WorldInventory(entries);
            if (JSON.requiredLong(root, "fileCount") != inventory.fileCount()
                    || JSON.requiredLong(root, "byteCount") != inventory.byteCount()
                    || !JSON.requiredString(root, "inventorySha256").equals(inventory.inventorySha256())) {
                throw new IOException("Inventory totals do not match its files");
            }
            return inventory;
        } catch (IOException | IllegalArgumentException exception) {
            throw new ZipArchiveDamagedException("The file list inside the ZIP backup cannot be read.", exception);
        }
    }

    /** True when the inventory holds exactly the files that the manifest's counts and digests describe. */
    static boolean matches(WorldInventory inventory, BackupManifest manifest) {
        return inventory.fileCount() == manifest.sourceFileCount()
                && inventory.byteCount() == manifest.sourceByteCount()
                && inventory.contentSha256().equals(manifest.contentSha256())
                && inventory.inventorySha256().equals(manifest.inventorySha256());
    }

    private static byte[] encode(JsonObject object) {
        return (GSON.toJson(object) + "\n").getBytes(StandardCharsets.UTF_8);
    }
}
