package dev.ishaankot.worldarchive.storage.git;

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

/** Strict codec for the metadata injected into every Git snapshot tree. */
final class GitSnapshotManifestCodec {
    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .disableHtmlEscaping()
            .create();

    private GitSnapshotManifestCodec() {
    }

    static byte[] encode(GitSnapshotManifest snapshotManifest) {
        BackupManifest manifest = snapshotManifest.manifest();
        JsonObject root = new JsonObject();
        root.addProperty("storageFormatVersion", snapshotManifest.storageFormatVersion());
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
        root.addProperty("sourceIdentity", snapshotManifest.sourceIdentity());
        JsonArray patterns = new JsonArray();
        snapshotManifest.lfsPatterns().forEach(patterns::add);
        root.add("lfsPatterns", patterns);
        return (GSON.toJson(root) + System.lineSeparator()).getBytes(StandardCharsets.UTF_8);
    }

    static GitSnapshotManifest decode(byte[] encoded) throws IOException {
        try {
            JsonElement parsed = JsonParser.parseString(new String(encoded, StandardCharsets.UTF_8));
            if (!parsed.isJsonObject()) {
                throw new IOException("Git snapshot manifest root is not an object");
            }
            JsonObject root = parsed.getAsJsonObject();
            BackupManifest manifest = new BackupManifest(
                    requiredInteger(root, "formatVersion"),
                    BackupId.parse(requiredString(root, "backupId")),
                    WorldId.parse(requiredString(root, "worldId")),
                    requiredString(root, "worldName"),
                    optionalString(root, "label"),
                    Instant.parse(requiredString(root, "createdAt")),
                    BackupTrigger.valueOf(requiredString(root, "trigger")),
                    requiredLong(root, "sourceFileCount"),
                    requiredLong(root, "sourceByteCount"),
                    requiredLong(root, "changedFileCount"),
                    requiredString(root, "contentSha256"),
                    requiredString(root, "inventorySha256"));
            return new GitSnapshotManifest(
                    requiredInteger(root, "storageFormatVersion"),
                    manifest,
                    requiredStrings(root, "lfsPatterns"),
                    requiredString(root, "sourceIdentity"));
        } catch (JsonParseException | IllegalArgumentException | DateTimeException exception) {
            throw new IOException("Git snapshot manifest contains invalid data", exception);
        }
    }

    private static List<String> requiredStrings(JsonObject root, String name) throws IOException {
        JsonElement element = root.get(name);
        if (element == null || !element.isJsonArray()) {
            throw new IOException("Git snapshot manifest is missing array: " + name);
        }
        List<String> values = new ArrayList<>();
        for (JsonElement value : element.getAsJsonArray()) {
            if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
                throw new IOException("Git snapshot manifest contains an invalid string array");
            }
            values.add(value.getAsString());
        }
        return List.copyOf(values);
    }

    private static String requiredString(JsonObject root, String name) throws IOException {
        JsonElement element = root.get(name);
        if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
            throw new IOException("Git snapshot manifest is missing string: " + name);
        }
        return element.getAsString();
    }

    private static Optional<String> optionalString(JsonObject root, String name) throws IOException {
        JsonElement element = root.get(name);
        if (element == null || element.isJsonNull()) {
            return Optional.empty();
        }
        if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
            throw new IOException("Git snapshot manifest contains an invalid optional string");
        }
        return Optional.of(element.getAsString());
    }

    private static int requiredInteger(JsonObject root, String name) throws IOException {
        long value = requiredLong(root, name);
        if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
            throw new IOException("Git snapshot manifest integer is out of range: " + name);
        }
        return (int) value;
    }

    private static long requiredLong(JsonObject root, String name) throws IOException {
        JsonElement element = root.get(name);
        if (element == null || !element.isJsonPrimitive()) {
            throw new IOException("Git snapshot manifest is missing number: " + name);
        }
        JsonPrimitive primitive = element.getAsJsonPrimitive();
        if (!primitive.isNumber()) {
            throw new IOException("Git snapshot manifest value is not numeric: " + name);
        }
        try {
            return new BigDecimal(primitive.getAsString()).longValueExact();
        } catch (ArithmeticException | NumberFormatException exception) {
            throw new IOException("Git snapshot manifest number is not an integer: " + name, exception);
        }
    }
}
