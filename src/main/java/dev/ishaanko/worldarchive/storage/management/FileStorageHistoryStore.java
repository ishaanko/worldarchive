package dev.ishaanko.worldarchive.storage.management;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.ishaanko.worldarchive.model.WorldId;
import dev.ishaanko.worldarchive.support.AtomicFiles;
import dev.ishaanko.worldarchive.support.JsonFields;
import java.io.IOException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Each world's measured storage over time, which the growth forecast reads: one small JSON file
 * per world with at most one sample per local day, so a day with many screen visits counts once.
 */
public final class FileStorageHistoryStore {
    private static final int SCHEMA_VERSION = 1;

    private static final int MAXIMUM_FILE_BYTES = 256 * 1_024;

    private static final int MAXIMUM_SAMPLES = 180;

    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .disableHtmlEscaping()
            .create();

    private static final JsonFields<IOException> FIELDS = new JsonFields<>(IOException::new);

    private final Path directory;

    public FileStorageHistoryStore(Path directory) {
        this.directory = Objects.requireNonNull(directory, "directory").toAbsolutePath().normalize();
    }

    /** The world's samples, oldest first; none when the world has no history yet. */
    public synchronized List<StorageSample> load(WorldId worldId) throws IOException {
        String json;
        try {
            json = AtomicFiles.readUtf8(file(worldId), MAXIMUM_FILE_BYTES);
        } catch (NoSuchFileException missing) {
            return List.of();
        }
        try {
            JsonObject root = FIELDS.parseObject(json, "Storage history");
            if (FIELDS.requiredInt(root, "schemaVersion") != SCHEMA_VERSION
                    || !worldId.toString().equals(FIELDS.requiredString(root, "worldId"))) {
                throw new IOException("Storage history identity or schema is invalid");
            }
            List<StorageSample> samples = new ArrayList<>();
            for (JsonObject sample : FIELDS.requiredObjects(root, "samples")) {
                samples.add(new StorageSample(
                        FIELDS.requiredInstant(sample, "measuredAt"), FIELDS.requiredLong(sample, "bytes")));
            }
            samples.sort(Comparator.comparing(StorageSample::measuredAt));
            return List.copyOf(samples.subList(Math.max(0, samples.size() - MAXIMUM_SAMPLES), samples.size()));
        } catch (IllegalArgumentException invalid) {
            throw new IOException("Storage history is invalid", invalid);
        }
    }

    /** Records a measurement; it replaces an earlier sample from the same local day. A damaged history starts over. */
    public synchronized void record(WorldId worldId, StorageSample sample, ZoneId zone) throws IOException {
        Objects.requireNonNull(sample, "sample");
        List<StorageSample> samples;
        try {
            samples = new ArrayList<>(load(worldId));
        } catch (IOException damaged) {
            samples = new ArrayList<>();
        }
        LocalDate day = sample.measuredAt().atZone(zone).toLocalDate();
        samples.removeIf(existing -> existing.measuredAt().atZone(zone).toLocalDate().equals(day));
        samples.add(sample);
        samples.sort(Comparator.comparing(StorageSample::measuredAt));
        JsonArray encoded = new JsonArray();
        for (StorageSample stored : samples.subList(Math.max(0, samples.size() - MAXIMUM_SAMPLES), samples.size())) {
            JsonObject item = new JsonObject();
            item.addProperty("measuredAt", stored.measuredAt().toString());
            item.addProperty("bytes", stored.bytes());
            encoded.add(item);
        }
        JsonObject root = new JsonObject();
        root.addProperty("schemaVersion", SCHEMA_VERSION);
        root.addProperty("worldId", worldId.toString());
        root.add("samples", encoded);
        AtomicFiles.writeUtf8(file(worldId), GSON.toJson(root) + "\n", MAXIMUM_FILE_BYTES);
    }

    private Path file(WorldId worldId) {
        return directory.resolve(Objects.requireNonNull(worldId, "worldId") + ".json");
    }
}
