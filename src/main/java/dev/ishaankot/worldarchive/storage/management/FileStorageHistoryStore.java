package dev.ishaankot.worldarchive.storage.management;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.ishaankot.worldarchive.core.AtomicFiles;
import dev.ishaankot.worldarchive.model.WorldId;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** Atomic bounded history used only for storage growth forecasts. */
public final class FileStorageHistoryStore {
    private static final int SCHEMA_VERSION = 1;

    private static final int MAXIMUM_FILE_BYTES = 256 * 1_024;

    private static final int MAXIMUM_SAMPLES = 180;

    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .disableHtmlEscaping()
            .create();

    private final Path directory;

    public FileStorageHistoryStore(Path directory) {
        this.directory = Objects.requireNonNull(directory, "directory")
                .toAbsolutePath()
                .normalize();
    }

    public synchronized List<StorageSample> load(WorldId worldId) throws IOException {
        Path file = file(worldId);
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
            return List.of();
        }
        requireRegularFile(file);
        if (Files.size(file) > MAXIMUM_FILE_BYTES) {
            throw new IOException("Storage history is unexpectedly large");
        }
        try {
            JsonObject root = JsonParser.parseString(
                    AtomicFiles.readUtf8(file, MAXIMUM_FILE_BYTES)).getAsJsonObject();
            if (root.get("schemaVersion").getAsInt() != SCHEMA_VERSION
                    || !worldId.toString().equals(root.get("worldId").getAsString())) {
                throw new IOException("Storage history identity or schema is invalid");
            }
            List<StorageSample> samples = new ArrayList<>();
            JsonArray encoded = root.getAsJsonArray("samples");
            if (encoded.size() > MAXIMUM_SAMPLES) {
                throw new IOException("Storage history contains too many samples");
            }
            for (JsonElement element : encoded) {
                JsonObject sample = element.getAsJsonObject();
                samples.add(new StorageSample(
                        Instant.parse(sample.get("measuredAt").getAsString()),
                        sample.get("bytes").getAsLong()));
            }
            samples.sort(Comparator.comparing(StorageSample::measuredAt));
            return List.copyOf(samples);
        } catch (RuntimeException exception) {
            throw new IOException("Storage history is malformed or invalid", exception);
        }
    }

    public synchronized void append(WorldId worldId, StorageSample sample) throws IOException {
        Objects.requireNonNull(sample, "sample");
        List<StorageSample> samples;
        try {
            samples = new ArrayList<>(load(worldId));
        } catch (IOException exception) {
            samples = new ArrayList<>();
        }
        samples.removeIf(existing -> existing.measuredAt().equals(sample.measuredAt()));
        samples.add(sample);
        samples.sort(Comparator.comparing(StorageSample::measuredAt));
        if (samples.size() > MAXIMUM_SAMPLES) {
            samples = new ArrayList<>(
                    samples.subList(samples.size() - MAXIMUM_SAMPLES, samples.size()));
        }
        Path file = file(worldId);
        Files.createDirectories(directory);
        if (Files.isSymbolicLink(directory) || Files.isSymbolicLink(file)) {
            throw new IOException("Storage history path must not be a symbolic link");
        }
        JsonObject root = new JsonObject();
        root.addProperty("schemaVersion", SCHEMA_VERSION);
        root.addProperty("worldId", worldId.toString());
        JsonArray encoded = new JsonArray();
        for (StorageSample stored : samples) {
            JsonObject item = new JsonObject();
            item.addProperty("measuredAt", stored.measuredAt().toString());
            item.addProperty("bytes", stored.bytes());
            encoded.add(item);
        }
        root.add("samples", encoded);
        AtomicFiles.writeUtf8(
                file,
                GSON.toJson(root) + System.lineSeparator(),
                MAXIMUM_FILE_BYTES);
    }

    private Path file(WorldId worldId) {
        Objects.requireNonNull(worldId, "worldId");
        return directory.resolve(worldId + ".json").normalize();
    }

    private static void requireRegularFile(Path file) throws IOException {
        if (Files.isSymbolicLink(file)
                || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Storage history is not a regular file");
        }
    }
}
