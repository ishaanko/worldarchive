package dev.ishaankot.worldarchive.importing;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import dev.ishaankot.worldarchive.core.AtomicFiles;
import dev.ishaankot.worldarchive.model.BackupId;
import dev.ishaankot.worldarchive.model.ImportSourceId;
import dev.ishaankot.worldarchive.model.WorldId;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.ReentrantLock;

/** Process-safe JSON registry for read-only import sources. */
public final class FileImportSourceRegistry implements ImportSourceRegistry {
    public static final int CURRENT_SCHEMA_VERSION = 1;

    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .disableHtmlEscaping()
            .create();

    private static final ConcurrentMap<Path, ReentrantLock> JVM_LOCKS = new ConcurrentHashMap<>();

    private final Path file;

    private final Path lockFile;

    private final ReentrantLock jvmLock;

    public FileImportSourceRegistry(Path file) {
        this.file = file.toAbsolutePath().normalize();
        this.lockFile = this.file.resolveSibling(this.file.getFileName() + ".lock");
        this.jvmLock = JVM_LOCKS.computeIfAbsent(this.file, ignored -> new ReentrantLock());
    }

    @Override
    public void put(ImportSource source) throws IOException {
        withLock(() -> {
            Map<ImportSourceId, ImportSource> sources = readSources();
            ImportSource current = sources.get(source.id());
            if (current != null
                    && (current.mode() != source.mode()
                            || !current.location().equals(source.location()))) {
                throw new IOException("Import source identity is already assigned to another location");
            }
            ImportSource merged = current == null ? source : current;
            try {
                for (ImportArtifactBinding binding : source.artifacts().values()) {
                    merged = merged.withArtifact(binding);
                }
            } catch (IllegalArgumentException exception) {
                throw new IOException("Import source contains a conflicting artifact binding", exception);
            }
            sources.put(source.id(), merged);
            writeSources(sources);
            return null;
        });
    }

    @Override
    public Optional<ImportSource> find(ImportSourceId sourceId) throws IOException {
        return withLock(() -> Optional.ofNullable(readSources().get(sourceId)));
    }

    @Override
    public List<ImportSource> list() throws IOException {
        return withLock(() -> readSources().values().stream()
                .sorted(Comparator.comparing(ImportSource::id))
                .toList());
    }

    @Override
    public void unlink(ImportSourceId sourceId, BackupId backupId) throws IOException {
        withLock(() -> {
            Map<ImportSourceId, ImportSource> sources = readSources();
            ImportSource source = sources.get(sourceId);
            if (source == null) {
                return null;
            }
            ImportSource updated = source.withoutArtifact(backupId);
            if (updated.artifacts().isEmpty()) {
                sources.remove(sourceId);
            } else {
                sources.put(sourceId, updated);
            }
            writeSources(sources);
            return null;
        });
    }

    private Map<ImportSourceId, ImportSource> readSources() throws IOException {
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
            return new LinkedHashMap<>();
        }
        if (Files.isSymbolicLink(file) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Import source registry is not a safe regular file");
        }
        try {
            JsonElement parsed = JsonParser.parseString(Files.readString(file));
            if (!parsed.isJsonObject()) {
                throw new IOException("Import source registry root must be an object");
            }
            JsonObject root = parsed.getAsJsonObject();
            int schemaVersion = root.get("schemaVersion").getAsInt();
            if (schemaVersion != CURRENT_SCHEMA_VERSION) {
                throw new IOException("Unsupported import source registry schema: " + schemaVersion);
            }
            JsonArray encodedSources = root.getAsJsonArray("sources");
            Map<ImportSourceId, ImportSource> sources = new LinkedHashMap<>();
            for (JsonElement element : encodedSources) {
                ImportSource source = decodeSource(element.getAsJsonObject());
                if (sources.putIfAbsent(source.id(), source) != null) {
                    throw new IOException("Import source registry contains duplicate source IDs");
                }
            }
            return sources;
        } catch (JsonParseException | IllegalArgumentException | NullPointerException exception) {
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
        AtomicFiles.writeUtf8(file, GSON.toJson(root) + System.lineSeparator());
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
        ImportSourceId id = ImportSourceId.parse(requiredString(encoded, "id"));
        ImportSourceMode mode = ImportSourceMode.valueOf(requiredString(encoded, "mode"));
        String location = requiredString(encoded, "location");
        JsonArray artifacts = encoded.getAsJsonArray("artifacts");
        Map<BackupId, ImportArtifactBinding> bindings = new LinkedHashMap<>();
        for (JsonElement element : artifacts) {
            ImportArtifactBinding binding = decodeBinding(element.getAsJsonObject());
            if (bindings.putIfAbsent(binding.backupId(), binding) != null) {
                throw new IOException("Import source contains duplicate backup IDs");
            }
        }
        return new ImportSource(id, mode, location, bindings);
    }

    private static ImportArtifactBinding decodeBinding(JsonObject encoded) throws IOException {
        return new ImportArtifactBinding(
                WorldId.parse(requiredString(encoded, "worldId")),
                BackupId.parse(requiredString(encoded, "backupId")),
                requiredString(encoded, "locator"),
                requiredString(encoded, "fingerprint"));
    }

    private static String requiredString(JsonObject encoded, String name) throws IOException {
        JsonElement value = encoded.get(name);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
            throw new IOException("Import source field is missing or invalid: " + name);
        }
        return value.getAsString();
    }

    private <T> T withLock(IoSupplier<T> operation) throws IOException {
        jvmLock.lock();
        try {
            Path parent = file.getParent();
            if (parent == null) {
                throw new IOException("Import source registry path has no parent");
            }
            Files.createDirectories(parent);
            if (Files.isSymbolicLink(lockFile)) {
                throw new IOException("Import source registry lock must not be symbolic");
            }
            try (FileChannel channel = FileChannel.open(
                            lockFile,
                            StandardOpenOption.CREATE,
                            StandardOpenOption.WRITE,
                            LinkOption.NOFOLLOW_LINKS);
                    FileLock ignored = channel.lock()) {
                return operation.get();
            }
        } finally {
            jvmLock.unlock();
        }
    }

    @FunctionalInterface
    private interface IoSupplier<T> {
        T get() throws IOException;
    }
}
