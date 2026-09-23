package dev.ishaanko.worldarchive.importing;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.ishaanko.worldarchive.model.BackupId;
import dev.ishaanko.worldarchive.model.ImportSourceId;
import dev.ishaanko.worldarchive.model.WorldId;
import dev.ishaanko.worldarchive.support.AtomicFiles;
import dev.ishaanko.worldarchive.support.JsonFields;
import dev.ishaanko.worldarchive.support.LockedFile;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The repositories that Git imports came from, and which imported backup came from which, in
 * {@code import-sources.json}. The start-up rebuild uses it to list an imported snapshot as
 * imported, and a delete unlinks what it deleted. Changes hold the file's lock across threads
 * and processes.
 */
public final class FileImportSourceRegistry {
    private static final int CURRENT_SCHEMA_VERSION = 1;

    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .disableHtmlEscaping()
            .create();

    private static final JsonFields<IOException> FIELDS = new JsonFields<>(IOException::new);

    private final LockedFile lock;

    private final Path file;

    public FileImportSourceRegistry(Path file) {
        this.lock = new LockedFile(file);
        this.file = lock.file();
    }

    /** Adds a source, or adds the source's bindings to the one already recorded for its location. */
    public void put(ImportSource source) throws IOException {
        lock.withLock(() -> {
            Map<ImportSourceId, ImportSource> sources = readSources();
            ImportSource current = sources.get(source.id());
            if (current != null
                    && (current.mode() != source.mode()
                            || !current.location().equals(source.location()))) {
                throw new IOException("Import source identity is already assigned to another location");
            }
            ImportSource merged = current == null ? source : current;
            try {
                if (current != null) {
                    for (ImportArtifactBinding binding : source.artifacts().values()) {
                        merged = merged.withArtifact(binding);
                    }
                }
            } catch (IllegalArgumentException exception) {
                throw new IOException("Import source contains a conflicting artifact binding", exception);
            }
            sources.put(source.id(), merged);
            writeSources(sources);
            return null;
        });
    }

    public Optional<ImportSource> find(ImportSourceId sourceId) throws IOException {
        return lock.withLock(() -> Optional.ofNullable(readSources().get(sourceId)));
    }

    public List<ImportSource> list() throws IOException {
        return lock.withLock(() -> readSources().values().stream()
                .sorted(Comparator.comparing(ImportSource::id))
                .toList());
    }

    /** Unlinks deleted backups from the sources they came from; a source left with none goes too. */
    public void unlink(Map<BackupId, ImportSourceId> deleted) throws IOException {
        Map<BackupId, ImportSourceId> artifacts = Map.copyOf(deleted);
        if (artifacts.isEmpty()) {
            return;
        }
        lock.withLock(() -> {
            Map<ImportSourceId, ImportSource> sources = readSources();
            artifacts.forEach((backupId, sourceId) -> {
                ImportSource source = sources.get(sourceId);
                if (source != null) {
                    ImportSource updated = source.withoutArtifact(backupId);
                    if (updated.artifacts().isEmpty()) {
                        sources.remove(sourceId);
                    } else {
                        sources.put(sourceId, updated);
                    }
                }
            });
            writeSources(sources);
            return null;
        });
    }

    private Map<ImportSourceId, ImportSource> readSources() throws IOException {
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
            return new LinkedHashMap<>();
        }
        try {
            JsonObject root = FIELDS.parseObject(AtomicFiles.readUtf8(file), "Import source registry");
            int schemaVersion = FIELDS.requiredInt(root, "schemaVersion");
            if (schemaVersion != CURRENT_SCHEMA_VERSION) {
                throw new IOException("Unsupported import source registry schema: " + schemaVersion);
            }
            Map<ImportSourceId, ImportSource> sources = new LinkedHashMap<>();
            for (JsonObject encoded : FIELDS.requiredObjects(root, "sources")) {
                ImportSource source = decodeSource(encoded);
                if (sources.putIfAbsent(source.id(), source) != null) {
                    throw new IOException("Import source registry contains duplicate source IDs");
                }
            }
            return sources;
        } catch (IllegalArgumentException exception) {
            throw new IOException("Import source registry is malformed or invalid", exception);
        }
    }

    private void writeSources(Map<ImportSourceId, ImportSource> sources) throws IOException {
        JsonObject root = new JsonObject();
        root.addProperty("schemaVersion", CURRENT_SCHEMA_VERSION);
        JsonArray encoded = new JsonArray();
        sources.values().stream()
                .sorted(Comparator.comparing(ImportSource::id))
                .forEach(source -> encoded.add(encodeSource(source)));
        root.add("sources", encoded);
        AtomicFiles.writeUtf8(file, GSON.toJson(root) + "\n");
    }

    private static JsonObject encodeSource(ImportSource source) {
        JsonObject encoded = new JsonObject();
        encoded.addProperty("id", source.id().toString());
        encoded.addProperty("mode", source.mode().name());
        encoded.addProperty("location", source.location());
        JsonArray artifacts = new JsonArray();
        source.artifacts().values().stream()
                .sorted(Comparator.comparing(ImportArtifactBinding::backupId))
                .forEach(binding -> artifacts.add(encodeBinding(binding)));
        encoded.add("artifacts", artifacts);
        return encoded;
    }

    private static JsonObject encodeBinding(ImportArtifactBinding binding) {
        JsonObject encoded = new JsonObject();
        encoded.addProperty("worldId", binding.worldId().toString());
        encoded.addProperty("backupId", binding.backupId().toString());
        encoded.addProperty("locator", binding.locator());
        encoded.addProperty("fingerprint", binding.fingerprint());
        return encoded;
    }

    private static ImportSource decodeSource(JsonObject encoded) throws IOException {
        ImportSourceId id = ImportSourceId.parse(FIELDS.requiredString(encoded, "id"));
        ImportSourceMode mode = FIELDS.requiredEnum(encoded, "mode", ImportSourceMode.class);
        String location = FIELDS.requiredString(encoded, "location");
        Map<BackupId, ImportArtifactBinding> bindings = new LinkedHashMap<>();
        for (JsonObject encodedBinding : FIELDS.requiredObjects(encoded, "artifacts")) {
            ImportArtifactBinding binding = decodeBinding(encodedBinding);
            if (bindings.putIfAbsent(binding.backupId(), binding) != null) {
                throw new IOException("Import source contains duplicate backup IDs");
            }
        }
        return new ImportSource(id, mode, location, bindings);
    }

    private static ImportArtifactBinding decodeBinding(JsonObject encoded) throws IOException {
        return new ImportArtifactBinding(
                WorldId.parse(FIELDS.requiredString(encoded, "worldId")),
                BackupId.parse(FIELDS.requiredString(encoded, "backupId")),
                FIELDS.requiredString(encoded, "locator"),
                FIELDS.requiredString(encoded, "fingerprint"));
    }
}
