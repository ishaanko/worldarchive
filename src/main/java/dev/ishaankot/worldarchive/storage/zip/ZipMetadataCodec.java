package dev.ishaankot.worldarchive.storage.zip;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import dev.ishaankot.worldarchive.model.BackupId;
import dev.ishaankot.worldarchive.model.BackupManifest;
import dev.ishaankot.worldarchive.model.BackupTrigger;
import dev.ishaankot.worldarchive.model.WorldId;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.DateTimeException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Strict JSON encoding for portable archive metadata. */
final class ZipMetadataCodec {
    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .disableHtmlEscaping()
            .create();

    private ZipMetadataCodec() {
    }

    static byte[] encodeManifest(BackupManifest manifest) {
        JsonObject root = new JsonObject();
        root.addProperty("formatVersion", manifest.formatVersion());
        root.addProperty("backupId", manifest.backupId().toString());
        root.addProperty("worldId", manifest.worldId().toString());
        root.addProperty("worldName", manifest.worldName());
        manifest.label().ifPresent(label -> root.addProperty("label", label));
        root.addProperty("createdAt", manifest.createdAt().toString());
        root.addProperty("trigger", manifest.trigger().name());
        root.addProperty("sourceFileCount", manifest.sourceFileCount());
        root.addProperty("sourceByteCount", manifest.sourceByteCount());
        root.addProperty("changedFileCount", manifest.changedFileCount());
        root.addProperty("contentSha256", manifest.contentSha256());
        root.addProperty("inventorySha256", manifest.inventorySha256());
        return encode(root);
    }

    static BackupManifest decodeManifest(byte[] encoded) throws IOException {
        JsonObject root = parseObject(encoded, "ZIP manifest");
        try {
            int formatVersion = requiredInteger(root, "formatVersion");
            BackupId backupId = BackupId.parse(requiredString(root, "backupId"));
            WorldId worldId = WorldId.parse(requiredString(root, "worldId"));
            String worldName = requiredString(root, "worldName");
            Instant createdAt = Instant.parse(requiredString(root, "createdAt"));
            BackupTrigger trigger = BackupTrigger.valueOf(requiredString(root, "trigger"));
            long sourceFileCount = requiredLong(root, "sourceFileCount");
            long sourceByteCount = requiredLong(root, "sourceByteCount");
            if (root.has("contentSha256")) {
                return new BackupManifest(
                        formatVersion,
                        backupId,
                        worldId,
                        worldName,
                        optionalString(root, "label"),
                        createdAt,
                        trigger,
                        sourceFileCount,
                        sourceByteCount,
                        requiredLong(root, "changedFileCount"),
                        requiredString(root, "contentSha256"),
                        requiredString(root, "inventorySha256"));
            }
            return new BackupManifest(
                    formatVersion,
                    backupId,
                    worldId,
                    worldName,
                    createdAt,
                    trigger,
                    sourceFileCount,
                    sourceByteCount,
                    requiredString(root, "sourceSha256"));
        } catch (IllegalArgumentException | DateTimeException exception) {
            throw new IOException("ZIP manifest contains invalid values", exception);
        }
    }

    static byte[] encodeInventory(ZipInventory inventory) {
        JsonObject root = new JsonObject();
        root.addProperty("formatVersion", inventory.formatVersion());
        root.addProperty("algorithm", "SHA-256");
        root.addProperty("fileCount", inventory.fileCount());
        root.addProperty("byteCount", inventory.byteCount());
        root.addProperty("inventorySha256", inventory.inventorySha256());
        JsonArray files = new JsonArray();
        for (ZipInventoryEntry file : inventory.files()) {
            JsonObject encodedFile = new JsonObject();
            encodedFile.addProperty("path", file.path());
            encodedFile.addProperty("size", file.size());
            encodedFile.addProperty("sha256", file.sha256());
            files.add(encodedFile);
        }
        root.add("files", files);
        return encode(root);
    }

    static ZipInventory decodeInventory(byte[] encoded) throws IOException {
        JsonObject root = parseObject(encoded, "ZIP inventory");
        if (!"SHA-256".equals(requiredString(root, "algorithm"))) {
            throw new IOException("ZIP inventory uses an unsupported digest algorithm");
        }
        JsonElement fileElement = root.get("files");
        if (fileElement == null || !fileElement.isJsonArray()) {
            throw new IOException("ZIP inventory is missing its file array");
        }
        if (fileElement.getAsJsonArray().size() > ZipLimits.MAXIMUM_INVENTORY_FILES) {
            throw new IOException("ZIP inventory exceeds its file limit");
        }
        List<ZipInventoryEntry> files = new ArrayList<>();
        try {
            for (JsonElement element : fileElement.getAsJsonArray()) {
                if (!element.isJsonObject()) {
                    throw new IOException("ZIP inventory contains a non-object file entry");
                }
                JsonObject file = element.getAsJsonObject();
                files.add(new ZipInventoryEntry(
                        requiredString(file, "path"),
                        requiredLong(file, "size"),
                        requiredString(file, "sha256")));
            }
            ZipInventory inventory = new ZipInventory(
                    requiredInteger(root, "formatVersion"),
                    files,
                    requiredString(root, "inventorySha256"));
            if (requiredLong(root, "fileCount") != inventory.fileCount()
                    || requiredLong(root, "byteCount") != inventory.byteCount()) {
                throw new IOException("ZIP inventory aggregate counts do not match its entries");
            }
            return inventory;
        } catch (IllegalArgumentException exception) {
            throw new IOException("ZIP inventory contains invalid values", exception);
        }
    }

    private static byte[] encode(JsonObject object) {
        return (GSON.toJson(object) + System.lineSeparator()).getBytes(StandardCharsets.UTF_8);
    }

    private static JsonObject parseObject(byte[] encoded, String description) throws IOException {
        try {
            JsonElement element = JsonParser.parseString(new String(encoded, StandardCharsets.UTF_8));
            if (!element.isJsonObject()) {
                throw new IOException(description + " root is not an object");
            }
            return element.getAsJsonObject();
        } catch (JsonParseException exception) {
            throw new IOException(description + " is malformed JSON", exception);
        }
    }

    private static String requiredString(JsonObject object, String name) throws IOException {
        JsonElement element = object.get(name);
        if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
            throw new IOException("Required ZIP metadata string is missing: " + name);
        }
        return element.getAsString();
    }

    private static Optional<String> optionalString(JsonObject object, String name) throws IOException {
        JsonElement element = object.get(name);
        if (element == null || element.isJsonNull()) {
            return Optional.empty();
        }
        if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
            throw new IOException("Optional ZIP metadata string is invalid: " + name);
        }
        return Optional.of(element.getAsString());
    }

    private static int requiredInteger(JsonObject object, String name) throws IOException {
        long value = requiredLong(object, name);
        if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
            throw new IOException("ZIP metadata integer is out of range: " + name);
        }
        return (int) value;
    }

    private static long requiredLong(JsonObject object, String name) throws IOException {
        JsonElement element = object.get(name);
        if (element == null || !element.isJsonPrimitive()) {
            throw new IOException("Required ZIP metadata number is missing: " + name);
        }
        JsonPrimitive primitive = element.getAsJsonPrimitive();
        if (!primitive.isNumber()) {
            throw new IOException("Required ZIP metadata value is not numeric: " + name);
        }
        try {
            return new BigDecimal(primitive.getAsString()).longValueExact();
        } catch (ArithmeticException | NumberFormatException exception) {
            throw new IOException("ZIP metadata value is not an integer: " + name, exception);
        }
    }
}
