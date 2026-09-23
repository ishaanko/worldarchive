package dev.ishaanko.worldarchive.catalog;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.stream.JsonReader;
import dev.ishaanko.worldarchive.model.ArtifactOwnership;
import dev.ishaanko.worldarchive.model.BackupId;
import dev.ishaanko.worldarchive.model.BackupManifest;
import dev.ishaanko.worldarchive.model.BackupManifestJson;
import dev.ishaanko.worldarchive.model.BackupRecord;
import dev.ishaanko.worldarchive.model.BackupResult;
import dev.ishaanko.worldarchive.model.DestinationResult;
import dev.ishaanko.worldarchive.model.DestinationStatus;
import dev.ishaanko.worldarchive.model.DestinationType;
import dev.ishaanko.worldarchive.model.ImportSourceId;
import dev.ishaanko.worldarchive.model.SyncStatus;
import dev.ishaanko.worldarchive.model.VerificationStatus;
import dev.ishaanko.worldarchive.model.WorldId;
import dev.ishaanko.worldarchive.support.AtomicFiles;
import dev.ishaanko.worldarchive.support.JsonFields;
import dev.ishaanko.worldarchive.support.LockedFile;
import java.io.IOException;
import java.io.StringReader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.UnaryOperator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The catalog in one JSON file. Reads take no lock: every change publishes a whole new file with
 * an atomic rename, so a reader sees the catalog as it was before or after a change. A change
 * holds the file's lock across threads and processes from its read to its write.
 *
 * <p>A file that cannot be decoded is moved aside to {@code catalog.json.corrupt-<UTC time>} and
 * the catalog starts over with every record that still reads in it; the start-up rebuild then
 * lists the backups on disk again. A file from a newer version of WorldArchive is refused
 * instead, so this version never drops what it cannot read.</p>
 */
public final class FileBackupCatalog implements BackupCatalog {
    public static final int CURRENT_SCHEMA_VERSION = 3;

    /** 0.1.0 wrote schema 2; schema 3 added imported artifacts. */
    private static final int OLDEST_SCHEMA_VERSION = 2;

    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .disableHtmlEscaping()
            .create();

    private static final Comparator<BackupRecord> NEWEST_FIRST = Comparator
            .comparing((BackupRecord record) -> record.manifest().createdAt())
            .reversed()
            .thenComparing(record -> record.manifest().backupId(), Comparator.reverseOrder());

    /** The catalog grows with every backup, so it gets a ceiling well above generic metadata. */
    private static final int MAXIMUM_CATALOG_BYTES = 256 * 1_024 * 1_024;

    private static final JsonFields<DamagedFileException> FIELDS = new JsonFields<>(DamagedFileException::new);

    private static final Logger LOGGER = LoggerFactory.getLogger("WorldArchive");

    private final Path file;

    private final LockedFile lock;

    private final AtomicBoolean lostRecords = new AtomicBoolean();

    public FileBackupCatalog(Path file) {
        this.lock = new LockedFile(file);
        this.file = lock.file();
    }

    @Override
    public void add(BackupRecord record) throws IOException {
        Objects.requireNonNull(record, "record");
        lock.withLock(() -> {
            Map<BackupId, BackupRecord> records = readForChange();
            BackupRecord existing = records.putIfAbsent(record.manifest().backupId(), record);
            if (existing == null) {
                write(records.values());
            } else if (!existing.equals(record)) {
                throw new IOException("Catalog already contains a different record for backup "
                        + record.manifest().backupId());
            }
            return null;
        });
    }

    @Override
    public Optional<BackupRecord> find(BackupId backupId) throws IOException {
        Objects.requireNonNull(backupId, "backupId");
        return Optional.ofNullable(read().get(backupId));
    }

    @Override
    public List<BackupRecord> listAll() throws IOException {
        return sorted(read().values());
    }

    @Override
    public List<BackupRecord> list(WorldId worldId) throws IOException {
        Objects.requireNonNull(worldId, "worldId");
        return sorted(read().values().stream()
                .filter(record -> record.manifest().worldId().equals(worldId))
                .toList());
    }

    @Override
    public boolean lostRecords() {
        return lostRecords.get();
    }

    @Override
    public Map<BackupId, Optional<BackupRecord>> updateAll(
            Map<BackupId, UnaryOperator<Optional<BackupRecord>>> changes) throws IOException {
        Map<BackupId, UnaryOperator<Optional<BackupRecord>>> requested =
                new LinkedHashMap<>(Objects.requireNonNull(changes, "changes"));
        return lock.withLock(() -> {
            Map<BackupId, BackupRecord> records = readForChange();
            Map<BackupId, Optional<BackupRecord>> results = new LinkedHashMap<>();
            boolean changed = false;
            for (Map.Entry<BackupId, UnaryOperator<Optional<BackupRecord>>> change : requested.entrySet()) {
                BackupId backupId = change.getKey();
                Optional<BackupRecord> current = Optional.ofNullable(records.get(backupId));
                Optional<BackupRecord> replacement = Objects.requireNonNull(
                        change.getValue().apply(current), "A catalog change returned null");
                if (replacement.isPresent()) {
                    requireSameIdentity(backupId, current, replacement.get());
                    records.put(backupId, replacement.get());
                } else {
                    records.remove(backupId);
                }
                changed |= !replacement.equals(current);
                results.put(backupId, replacement);
            }
            if (changed) {
                write(records.values());
            }
            return results;
        });
    }

    private static void requireSameIdentity(
            BackupId backupId,
            Optional<BackupRecord> current,
            BackupRecord replacement) throws IOException {
        boolean sameBackup = replacement.manifest().backupId().equals(backupId);
        boolean sameWorld = current.isEmpty()
                || current.get().manifest().worldId().equals(replacement.manifest().worldId());
        if (!sameBackup || !sameWorld) {
            throw new IOException("A catalog change must keep the backup and world IDs of " + backupId);
        }
    }

    /** The records by ID, read without the lock; a damaged file is first moved aside under the lock. */
    private Map<BackupId, BackupRecord> read() throws IOException {
        try {
            return decode(DamagedFiles.read(file, MAXIMUM_CATALOG_BYTES));
        } catch (DamagedFileException damaged) {
            return lock.withLock(this::readForChange);
        }
    }

    /** The records by ID for a change, whose caller holds the lock; a damaged file is moved aside. */
    private Map<BackupId, BackupRecord> readForChange() throws IOException {
        Optional<String> json;
        try {
            json = DamagedFiles.read(file, MAXIMUM_CATALOG_BYTES);
        } catch (DamagedFileException notText) {
            return startOver(new LinkedHashMap<>(), notText);
        }
        try {
            return decode(json);
        } catch (DamagedFileException damaged) {
            return startOver(json.map(FileBackupCatalog::salvage).orElseGet(LinkedHashMap::new), damaged);
        }
    }

    /** Moves the damaged file aside and starts over with the records that still read in it; the caller holds the lock. */
    private Map<BackupId, BackupRecord> startOver(Map<BackupId, BackupRecord> salvaged, DamagedFileException damage)
            throws IOException {
        DamagedFiles.moveAside(file, damage);
        lostRecords.set(true);
        if (!salvaged.isEmpty()) {
            write(salvaged.values());
            LOGGER.warn("WorldArchive kept the {} backups that the damaged backup list still named", salvaged.size());
        }
        return salvaged;
    }

    /**
     * The records of a damaged file that still decode. A record that does not decode is left out
     * and the next one is read; damage to the JSON itself ends the reading, and the records before
     * it are kept. A file of a schema this version does not read gives none.
     */
    private static Map<BackupId, BackupRecord> salvage(String json) {
        Map<BackupId, BackupRecord> records = new LinkedHashMap<>();
        try (JsonReader reader = new JsonReader(new StringReader(json))) {
            int schemaVersion = CURRENT_SCHEMA_VERSION;
            reader.beginObject();
            while (reader.hasNext()) {
                String name = reader.nextName();
                if (name.equals("schemaVersion")) {
                    schemaVersion = reader.nextInt();
                } else if (name.equals("records")
                        && schemaVersion >= OLDEST_SCHEMA_VERSION && schemaVersion <= CURRENT_SCHEMA_VERSION) {
                    salvageRecords(reader, schemaVersion, records);
                } else {
                    reader.skipValue();
                }
            }
        } catch (IOException | RuntimeException damaged) {
            // The rest of the file cannot be read; what was read before the damage is kept.
        }
        return records;
    }

    private static void salvageRecords(JsonReader reader, int schemaVersion, Map<BackupId, BackupRecord> records)
            throws IOException {
        reader.beginArray();
        while (reader.hasNext()) {
            JsonElement encoded = JsonParser.parseReader(reader);
            try {
                if (encoded.isJsonObject()) {
                    BackupRecord record = decodeRecord(encoded.getAsJsonObject(), schemaVersion);
                    records.putIfAbsent(record.manifest().backupId(), record);
                }
            } catch (DamagedFileException | RuntimeException unreadable) {
                // This record is lost; the next one may still read.
            }
        }
        reader.endArray();
    }

    private Map<BackupId, BackupRecord> decode(Optional<String> json) throws IOException {
        Map<BackupId, BackupRecord> records = new LinkedHashMap<>();
        if (json.isEmpty()) {
            return records;
        }
        try {
            JsonObject root = FIELDS.parseObject(json.get(), "The backup list");
            int schemaVersion = FIELDS.requiredInt(root, "schemaVersion");
            if (schemaVersion > CURRENT_SCHEMA_VERSION) {
                throw new IOException("The backup list " + file + " was written by a newer version of"
                        + " WorldArchive (schema " + schemaVersion + "). Update WorldArchive to use it.");
            }
            if (schemaVersion < OLDEST_SCHEMA_VERSION) {
                throw new DamagedFileException("The backup list has an unknown schema " + schemaVersion, null);
            }
            for (JsonObject encoded : FIELDS.requiredObjects(root, "records")) {
                BackupRecord record = decodeRecord(encoded, schemaVersion);
                if (records.putIfAbsent(record.manifest().backupId(), record) != null) {
                    throw new DamagedFileException(
                            "The backup list names backup " + record.manifest().backupId() + " twice", null);
                }
            }
            return records;
        } catch (IllegalArgumentException invalid) {
            throw new DamagedFileException("The backup list holds an invalid record", invalid);
        }
    }

    private void write(Collection<BackupRecord> records) throws IOException {
        JsonObject root = new JsonObject();
        root.addProperty("schemaVersion", CURRENT_SCHEMA_VERSION);
        JsonArray encodedRecords = new JsonArray();
        sorted(records).forEach(record -> encodedRecords.add(encodeRecord(record)));
        root.add("records", encodedRecords);
        AtomicFiles.writeUtf8(file, GSON.toJson(root) + "\n", MAXIMUM_CATALOG_BYTES);
    }

    private static List<BackupRecord> sorted(Collection<BackupRecord> records) {
        return records.stream().sorted(NEWEST_FIRST).toList();
    }

    private static JsonObject encodeRecord(BackupRecord record) {
        JsonObject manifest = new JsonObject();
        BackupManifestJson.write(record.manifest(), manifest);
        JsonObject encoded = new JsonObject();
        encoded.add("manifest", manifest);
        encoded.add("result", encodeResult(record.result()));
        return encoded;
    }

    /** Writes the derived status too, so the file stays readable by older versions. */
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
        encoded.addProperty("ownership", result.ownership().name());
        result.importSourceId().ifPresent(value -> encoded.addProperty("importSourceId", value.toString()));
        return encoded;
    }

    private static BackupRecord decodeRecord(JsonObject encoded, int schemaVersion) throws DamagedFileException {
        BackupManifest manifest = BackupManifestJson.read(FIELDS.requiredObject(encoded, "manifest"), FIELDS);
        BackupResult result = decodeResult(FIELDS.requiredObject(encoded, "result"), schemaVersion);
        return new BackupRecord(manifest, result);
    }

    /** The stored status is derived from the destinations, so it is written but never read. */
    private static BackupResult decodeResult(JsonObject encoded, int schemaVersion) throws DamagedFileException {
        List<DestinationResult> destinations = new ArrayList<>();
        for (JsonObject destination : FIELDS.requiredObjects(encoded, "destinations")) {
            destinations.add(decodeDestination(destination, schemaVersion));
        }
        return new BackupResult(
                BackupId.parse(FIELDS.requiredString(encoded, "backupId")),
                WorldId.parse(FIELDS.requiredString(encoded, "worldId")),
                destinations,
                FIELDS.requiredInstant(encoded, "completedAt"));
    }

    /** Schema 2 predates imports, so every artifact it lists is WorldArchive's own. */
    private static DestinationResult decodeDestination(JsonObject encoded, int schemaVersion)
            throws DamagedFileException {
        boolean imports = schemaVersion >= 3;
        return new DestinationResult(
                FIELDS.requiredEnum(encoded, "destination", DestinationType.class),
                FIELDS.requiredEnum(encoded, "status", DestinationStatus.class),
                FIELDS.optionalString(encoded, "artifactId"),
                FIELDS.optionalString(encoded, "message"),
                FIELDS.requiredEnum(encoded, "verificationStatus", VerificationStatus.class),
                FIELDS.requiredEnum(encoded, "syncStatus", SyncStatus.class),
                imports
                        ? FIELDS.requiredEnum(encoded, "ownership", ArtifactOwnership.class)
                        : ArtifactOwnership.MANAGED,
                imports
                        ? FIELDS.optionalString(encoded, "importSourceId").map(ImportSourceId::parse)
                        : Optional.empty());
    }
}
