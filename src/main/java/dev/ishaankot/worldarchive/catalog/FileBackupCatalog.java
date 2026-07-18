package dev.ishaankot.worldarchive.catalog;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import dev.ishaankot.worldarchive.core.AtomicFiles;
import dev.ishaankot.worldarchive.model.BackupId;
import dev.ishaankot.worldarchive.model.BackupManifest;
import dev.ishaankot.worldarchive.model.BackupRecord;
import dev.ishaankot.worldarchive.model.BackupResult;
import dev.ishaankot.worldarchive.model.BackupStatus;
import dev.ishaankot.worldarchive.model.BackupTrigger;
import dev.ishaankot.worldarchive.model.DestinationResult;
import dev.ishaankot.worldarchive.model.DestinationStatus;
import dev.ishaankot.worldarchive.model.DestinationType;
import dev.ishaankot.worldarchive.model.SyncStatus;
import dev.ishaankot.worldarchive.model.VerificationStatus;
import dev.ishaankot.worldarchive.model.WorldId;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.UnaryOperator;

/** Thread- and process-safe JSON catalog with atomic publication. */
public final class FileBackupCatalog implements BackupCatalog {
    public static final int CURRENT_SCHEMA_VERSION = 2;

    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .disableHtmlEscaping()
            .create();

    private static final Comparator<BackupRecord> NEWEST_FIRST = Comparator
            .comparing((BackupRecord record) -> record.manifest().createdAt())
            .reversed()
            .thenComparing(record -> record.manifest().backupId(), Comparator.reverseOrder());

    private static final ConcurrentMap<Path, ReentrantLock> JVM_LOCKS = new ConcurrentHashMap<>();

    private final Path file;

    private final Path lockFile;

    private final ReentrantLock jvmLock;

    public FileBackupCatalog(Path file) {
        this.file = file.toAbsolutePath().normalize();
        this.lockFile = this.file.resolveSibling(this.file.getFileName() + ".lock");
        this.jvmLock = JVM_LOCKS.computeIfAbsent(this.file, ignored -> new ReentrantLock());
    }

    @Override
    public void add(BackupRecord record) throws IOException {
        withLock(() -> {
            List<BackupRecord> records = readRecords();
            for (BackupRecord existing : records) {
                if (existing.manifest().backupId().equals(record.manifest().backupId())) {
                    if (existing.equals(record)) {
                        return null;
                    }
                    throw new IOException("Catalog already contains a different record for backup "
                            + record.manifest().backupId());
                }
            }
            records.add(record);
            writeRecords(records);
            return null;
        });
    }

    @Override
    public Optional<BackupRecord> find(BackupId backupId) throws IOException {
        return withLock(() -> readRecords().stream()
                .filter(record -> record.manifest().backupId().equals(backupId))
                .findFirst());
    }

    @Override
    public List<BackupRecord> listAll() throws IOException {
        return withLock(() -> sorted(readRecords()));
    }

    @Override
    public List<BackupRecord> list(WorldId worldId) throws IOException {
        return withLock(() -> sorted(readRecords().stream()
                .filter(record -> record.manifest().worldId().equals(worldId))
                .toList()));
    }

    @Override
    public Optional<BackupRecord> update(
            BackupId backupId,
            UnaryOperator<BackupRecord> update) throws IOException {
        return withLock(() -> {
            List<BackupRecord> records = readRecords();
            for (int index = 0; index < records.size(); index++) {
                BackupRecord existing = records.get(index);
                if (existing.manifest().backupId().equals(backupId)) {
                    BackupRecord replacement = java.util.Objects.requireNonNull(
                            update.apply(existing),
                            "Catalog update returned null");
                    if (!replacement.manifest().backupId().equals(backupId)
                            || !replacement.manifest().worldId().equals(existing.manifest().worldId())) {
                        throw new IOException("Catalog update must preserve backup and world identities");
                    }
                    records.set(index, replacement);
                    writeRecords(records);
                    return Optional.of(replacement);
                }
            }
            return Optional.empty();
        });
    }

    @Override
    public boolean remove(BackupId backupId) throws IOException {
        return withLock(() -> {
            List<BackupRecord> records = readRecords();
            boolean removed = records.removeIf(record -> record.manifest().backupId().equals(backupId));
            if (removed) {
                writeRecords(records);
            }
            return removed;
        });
    }

    public Path file() {
        return file;
    }

    private <T> T withLock(IoSupplier<T> operation) throws IOException {
        jvmLock.lock();
        try {
            Path parent = file.getParent();
            if (parent == null) {
                throw new IOException("Catalog path has no parent directory: " + file);
            }
            Files.createDirectories(parent);
            if (Files.isSymbolicLink(lockFile)) {
                throw new IOException("Catalog lock file must not be a symbolic link");
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

    private List<BackupRecord> readRecords() throws IOException {
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
            return new ArrayList<>();
        }
        if (Files.isSymbolicLink(file) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Catalog is not a regular file: " + file);
        }
        String json = Files.readString(file, StandardCharsets.UTF_8);
        try {
            JsonElement parsed = JsonParser.parseString(json);
            if (!parsed.isJsonObject()) {
                throw new IOException("Catalog root must be a JSON object");
            }
            JsonObject root = parsed.getAsJsonObject();
            int schemaVersion = requiredInteger(root, "schemaVersion");
            if (schemaVersion > CURRENT_SCHEMA_VERSION) {
                throw new IOException("Unsupported future catalog schema: " + schemaVersion);
            }
            if (schemaVersion < 1) {
                throw new IOException("Unsupported catalog schema: " + schemaVersion);
            }
            JsonArray array = requiredArray(root, "records");
            List<BackupRecord> records = new ArrayList<>(array.size());
            Set<BackupId> ids = new HashSet<>();
            for (JsonElement element : array) {
                if (!element.isJsonObject()) {
                    throw new IOException("Catalog record must be a JSON object");
                }
                BackupRecord record = decodeRecord(element.getAsJsonObject(), schemaVersion);
                if (!ids.add(record.manifest().backupId())) {
                    throw new IOException("Catalog contains duplicate backup IDs");
                }
                records.add(record);
            }
            return records;
        } catch (JsonParseException | IllegalArgumentException exception) {
            throw new IOException("Catalog is malformed or invalid", exception);
        }
    }

    private void writeRecords(List<BackupRecord> records) throws IOException {
        JsonObject root = new JsonObject();
        root.addProperty("schemaVersion", CURRENT_SCHEMA_VERSION);
        JsonArray encodedRecords = new JsonArray();
        sorted(records).forEach(record -> encodedRecords.add(encodeRecord(record)));
        root.add("records", encodedRecords);
        AtomicFiles.writeUtf8(file, GSON.toJson(root) + System.lineSeparator());
    }

    private static List<BackupRecord> sorted(List<BackupRecord> records) {
        return records.stream().sorted(NEWEST_FIRST).toList();
    }

    private static JsonObject encodeRecord(BackupRecord record) {
        JsonObject encoded = new JsonObject();
        encoded.add("manifest", encodeManifest(record.manifest()));
        encoded.add("result", encodeResult(record.result()));
        return encoded;
    }

    private static JsonObject encodeManifest(BackupManifest manifest) {
        JsonObject encoded = new JsonObject();
        encoded.addProperty("formatVersion", manifest.formatVersion());
        encoded.addProperty("backupId", manifest.backupId().toString());
        encoded.addProperty("worldId", manifest.worldId().toString());
        encoded.addProperty("worldName", manifest.worldName());
        manifest.label().ifPresent(label -> encoded.addProperty("label", label));
        encoded.addProperty("createdAt", manifest.createdAt().toString());
        encoded.addProperty("trigger", manifest.trigger().name());
        encoded.addProperty("sourceFileCount", manifest.sourceFileCount());
        encoded.addProperty("sourceByteCount", manifest.sourceByteCount());
        encoded.addProperty("changedFileCount", manifest.changedFileCount());
        encoded.addProperty("contentSha256", manifest.contentSha256());
        encoded.addProperty("inventorySha256", manifest.inventorySha256());
        return encoded;
    }

    private static JsonObject encodeResult(BackupResult result) {
        JsonObject encoded = new JsonObject();
        encoded.addProperty("backupId", result.backupId().toString());
        encoded.addProperty("worldId", result.worldId().toString());
        encoded.addProperty("status", result.status().name());
        encoded.addProperty("completedAt", result.completedAt().toString());
        JsonArray destinations = new JsonArray();
        result.destinations().forEach(destination -> destinations.add(encodeDestination(destination)));
        encoded.add("destinations", destinations);
        return encoded;
    }

    private static JsonObject encodeDestination(DestinationResult result) {
        JsonObject encoded = new JsonObject();
        encoded.addProperty("destination", result.destination().name());
        encoded.addProperty("status", result.status().name());
        result.artifactId().ifPresent(value -> encoded.addProperty("artifactId", value));
        result.message().ifPresent(value -> encoded.addProperty("message", value));
        encoded.addProperty("verificationStatus", result.verificationStatus().name());
        encoded.addProperty("syncStatus", result.syncStatus().name());
        return encoded;
    }

    private static BackupRecord decodeRecord(JsonObject encoded, int schemaVersion) throws IOException {
        BackupManifest manifest = decodeManifest(requiredObject(encoded, "manifest"), schemaVersion);
        BackupResult result = decodeResult(requiredObject(encoded, "result"), schemaVersion);
        return new BackupRecord(manifest, result);
    }

    private static BackupManifest decodeManifest(JsonObject encoded, int schemaVersion) throws IOException {
        if (schemaVersion == 1) {
            return new BackupManifest(
                    requiredInteger(encoded, "formatVersion"),
                    BackupId.parse(requiredString(encoded, "backupId")),
                    WorldId.parse(requiredString(encoded, "worldId")),
                    requiredString(encoded, "worldName"),
                    requiredInstant(encoded, "createdAt"),
                    requiredEnum(encoded, "trigger", BackupTrigger.class),
                    requiredLong(encoded, "sourceFileCount"),
                    requiredLong(encoded, "sourceByteCount"),
                    requiredString(encoded, "sourceSha256"));
        }
        return new BackupManifest(
                requiredInteger(encoded, "formatVersion"),
                BackupId.parse(requiredString(encoded, "backupId")),
                WorldId.parse(requiredString(encoded, "worldId")),
                requiredString(encoded, "worldName"),
                optionalString(encoded, "label"),
                requiredInstant(encoded, "createdAt"),
                requiredEnum(encoded, "trigger", BackupTrigger.class),
                requiredLong(encoded, "sourceFileCount"),
                requiredLong(encoded, "sourceByteCount"),
                requiredLong(encoded, "changedFileCount"),
                requiredString(encoded, "contentSha256"),
                requiredString(encoded, "inventorySha256"));
    }

    private static BackupResult decodeResult(JsonObject encoded, int schemaVersion) throws IOException {
        JsonArray destinations = requiredArray(encoded, "destinations");
        List<DestinationResult> decodedDestinations = new ArrayList<>(destinations.size());
        for (JsonElement destination : destinations) {
            if (!destination.isJsonObject()) {
                throw new IOException("Destination result must be a JSON object");
            }
            decodedDestinations.add(decodeDestination(destination.getAsJsonObject(), schemaVersion));
        }
        return new BackupResult(
                BackupId.parse(requiredString(encoded, "backupId")),
                WorldId.parse(requiredString(encoded, "worldId")),
                requiredEnum(encoded, "status", BackupStatus.class),
                decodedDestinations,
                requiredInstant(encoded, "completedAt"));
    }

    private static DestinationResult decodeDestination(JsonObject encoded, int schemaVersion) throws IOException {
        VerificationStatus verification = schemaVersion == 1
                ? VerificationStatus.NOT_VERIFIED
                : requiredEnum(encoded, "verificationStatus", VerificationStatus.class);
        SyncStatus sync = schemaVersion == 1
                ? SyncStatus.NOT_CONFIGURED
                : requiredEnum(encoded, "syncStatus", SyncStatus.class);
        return new DestinationResult(
                requiredEnum(encoded, "destination", DestinationType.class),
                requiredEnum(encoded, "status", DestinationStatus.class),
                optionalString(encoded, "artifactId"),
                optionalString(encoded, "message"),
                verification,
                sync);
    }

    private static JsonObject requiredObject(JsonObject object, String name) throws IOException {
        JsonElement element = object.get(name);
        if (element == null || !element.isJsonObject()) {
            throw new IOException("Required object is missing or invalid: " + name);
        }
        return element.getAsJsonObject();
    }

    private static JsonArray requiredArray(JsonObject object, String name) throws IOException {
        JsonElement element = object.get(name);
        if (element == null || !element.isJsonArray()) {
            throw new IOException("Required array is missing or invalid: " + name);
        }
        return element.getAsJsonArray();
    }

    private static String requiredString(JsonObject object, String name) throws IOException {
        JsonElement element = object.get(name);
        if (element == null
                || !element.isJsonPrimitive()
                || !element.getAsJsonPrimitive().isString()) {
            throw new IOException("Required string is missing or invalid: " + name);
        }
        return element.getAsString();
    }

    private static Optional<String> optionalString(JsonObject object, String name) throws IOException {
        JsonElement element = object.get(name);
        if (element == null || element.isJsonNull()) {
            return Optional.empty();
        }
        if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
            throw new IOException("Expected a string: " + name);
        }
        return Optional.of(element.getAsString());
    }

    private static int requiredInteger(JsonObject object, String name) throws IOException {
        long value = requiredLong(object, name);
        if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
            throw new IOException("Integer is out of range: " + name);
        }
        return (int) value;
    }

    private static long requiredLong(JsonObject object, String name) throws IOException {
        JsonElement element = object.get(name);
        if (element == null || !element.isJsonPrimitive()) {
            throw new IOException("Required number is missing or invalid: " + name);
        }
        JsonPrimitive primitive = element.getAsJsonPrimitive();
        if (!primitive.isNumber()) {
            throw new IOException("Required number is missing or invalid: " + name);
        }
        try {
            return new BigDecimal(primitive.getAsString()).longValueExact();
        } catch (ArithmeticException | NumberFormatException exception) {
            throw new IOException("Required number is not an integer: " + name, exception);
        }
    }

    private static Instant requiredInstant(JsonObject object, String name) throws IOException {
        try {
            return Instant.parse(requiredString(object, name));
        } catch (DateTimeParseException exception) {
            throw new IOException("Required timestamp is invalid: " + name, exception);
        }
    }

    private static <E extends Enum<E>> E requiredEnum(
            JsonObject object,
            String name,
            Class<E> enumType) throws IOException {
        try {
            return Enum.valueOf(enumType, requiredString(object, name));
        } catch (IllegalArgumentException exception) {
            throw new IOException("Enum value is invalid: " + name, exception);
        }
    }

    @FunctionalInterface
    private interface IoSupplier<T> {
        T get() throws IOException;
    }
}
