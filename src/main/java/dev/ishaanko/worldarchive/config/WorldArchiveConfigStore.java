package dev.ishaanko.worldarchive.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.ishaanko.worldarchive.model.SafeText;
import dev.ishaanko.worldarchive.model.WorldId;
import dev.ishaanko.worldarchive.support.AtomicFiles;
import dev.ishaanko.worldarchive.support.JsonFields;
import dev.ishaanko.worldarchive.support.LockedFile;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.UnaryOperator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Reads and writes {@link WorldArchiveConfig} as UTF-8 JSON. Each write replaces the file
 * atomically while a lock shared by threads and game instances is held, and each change is
 * applied to what the file holds at that moment. Destination folders equal to the defaults
 * ({@link DefaultDestinations}) are left out of the file and filled in when it is read.
 *
 * <p>A file that exists but cannot be read is never written over: every read fails with
 * {@link UnreadableConfigurationException} until {@link #reset} keeps the file under a new
 * name. Schemas 4 (WorldArchive 0.1.1) to 7 are read; an older schema is upgraded on load,
 * after the old file is kept as {@code <name>.schema<N>.bak}.</p>
 */
public final class WorldArchiveConfigStore {
    private static final Logger LOGGER = LoggerFactory.getLogger("WorldArchive");

    /** WorldArchive 0.1.1 wrote schema 4; the schema 3 of 0.1.0 is no longer read. */
    private static final int OLDEST_SCHEMA_VERSION = 4;

    /** Schema 4 kept one remote URL for every world, with {@code {worldId}} for each world's ID. */
    private static final int REMOTE_TEMPLATE_SCHEMA_VERSION = 4;

    /** Schema 7 added each world's storage policy. */
    private static final int STORAGE_POLICY_SCHEMA_VERSION = 7;

    private static final String WORLD_ID_PLACEHOLDER = "{worldId}";

    private static final int MAXIMUM_CONFIG_BYTES = 1_048_576;

    private static final int MESSAGE_LIMIT = 512;

    private static final DateTimeFormatter KEPT_COPY_TIME = DateTimeFormatter
            .ofPattern("uuuuMMdd'T'HHmmss'Z'", Locale.ROOT)
            .withZone(ZoneOffset.UTC);

    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .disableHtmlEscaping()
            .create();

    private static final JsonFields<ConfigurationException> FIELDS =
            new JsonFields<>(ConfigurationException::new);

    private final Path file;

    private final LockedFile lock;

    private final DefaultDestinations defaults;

    public WorldArchiveConfigStore(Path file, DefaultDestinations defaults) {
        this.file = Objects.requireNonNull(file, "file").toAbsolutePath().normalize();
        this.lock = new LockedFile(this.file);
        this.defaults = Objects.requireNonNull(defaults, "defaults");
    }

    /**
     * Reads the settings, with the default folders filled in and every path canonical;
     * product defaults when the file does not exist.
     *
     * @throws UnreadableConfigurationException when the file exists but cannot be read or understood
     */
    public WorldArchiveConfig load() throws UnreadableConfigurationException {
        try {
            return lock.withLock(() -> {
                Stored stored = read();
                WorldArchiveConfig kept = defaults.withoutDefaults(stored.config());
                if (stored.outdated() || !kept.equals(stored.config())) {
                    upgradeInPlace(kept, stored);
                }
                return defaults.resolve(kept).canonicalize();
            });
        } catch (UnreadableConfigurationException exception) {
            throw exception;
        } catch (IOException | RuntimeException exception) {
            throw new UnreadableConfigurationException(file, exception);
        }
    }

    /**
     * Applies a change to the settings the file holds now and writes the result, so a change
     * saved meanwhile by another game instance is kept. The change receives the settings as
     * {@link #load} returns them. Nothing is written when it returns them unchanged. Before
     * writing, every destination is checked against the configured worlds and the given world
     * folders.
     *
     * @return the settings as written, in canonical form
     * @throws UnreadableConfigurationException when the file cannot be read; it is left as it is
     */
    public WorldArchiveConfig update(
            UnaryOperator<WorldArchiveConfig> change,
            Collection<Path> knownWorldPaths) throws IOException {
        Objects.requireNonNull(change, "change");
        List<Path> worlds = List.copyOf(knownWorldPaths);
        return lock.withLock(() -> {
            Stored stored = read();
            WorldArchiveConfig current = defaults.resolve(stored.config()).canonicalize();
            WorldArchiveConfig changed = Objects.requireNonNull(change.apply(current), "changed settings");
            if (changed.equals(current)) {
                return current;
            }
            WorldArchiveConfig validated = changed.validateDestinations(worlds);
            write(defaults.withoutDefaults(validated), stored);
            return validated;
        });
    }

    /**
     * Keeps the unreadable file as {@code <name>.unreadable-<UTC time>} and writes new settings
     * that {@code start} builds from the product defaults. When the new file cannot be written,
     * the old one is moved back. Settings that read now, as after another game instance fixed the
     * file, are never replaced: the result is then empty and nothing changes.
     */
    public Optional<Reset> reset(
            UnaryOperator<WorldArchiveConfig> start,
            Collection<Path> knownWorldPaths,
            Instant now) throws IOException {
        Objects.requireNonNull(start, "start");
        Objects.requireNonNull(now, "now");
        List<Path> worlds = List.copyOf(knownWorldPaths);
        return lock.withLock(() -> {
            if (readable()) {
                return Optional.empty();
            }
            WorldArchiveConfig fresh = start.apply(defaults.resolve(WorldArchiveConfig.defaults()))
                    .validateDestinations(worlds);
            Path keptCopy = file.resolveSibling(file.getFileName() + ".unreadable-" + KEPT_COPY_TIME.format(now));
            Files.move(file, keptCopy);
            try {
                AtomicFiles.writeUtf8(file, encode(defaults.withoutDefaults(fresh)), MAXIMUM_CONFIG_BYTES);
            } catch (IOException | RuntimeException exception) {
                moveBack(keptCopy, exception);
                throw exception;
            }
            return Optional.of(new Reset(fresh, keptCopy));
        });
    }

    /** Whether the file reads now, or there is none; the caller holds the lock. */
    private boolean readable() {
        try {
            read();
            return true;
        } catch (UnreadableConfigurationException unreadable) {
            return false;
        }
    }

    private void moveBack(Path copy, Exception failure) {
        try {
            Files.move(copy, file);
        } catch (IOException | RuntimeException exception) {
            failure.addSuppressed(exception);
        }
    }

    private Stored read() throws UnreadableConfigurationException {
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
            return new Stored(WorldArchiveConfig.defaults(), WorldArchiveConfig.CURRENT_SCHEMA_VERSION, "");
        }
        try {
            String text = AtomicFiles.readUtf8(file, MAXIMUM_CONFIG_BYTES);
            JsonObject root = FIELDS.parseObject(text, "WorldArchive settings");
            rejectCredentialFields(root);
            int schemaVersion = FIELDS.requiredInt(root, "schemaVersion");
            return new Stored(parse(root, schemaVersion), schemaVersion, text);
        } catch (IOException | RuntimeException exception) {
            throw new UnreadableConfigurationException(file, exception);
        }
    }

    /**
     * Writes an older schema, or default folders an older version stored, in the current form.
     * A failure is only logged: the settings were read, and the next change writes the file.
     */
    private void upgradeInPlace(WorldArchiveConfig config, Stored stored) {
        try {
            write(config, stored);
        } catch (IOException exception) {
            LOGGER.warn("WorldArchive settings could not be upgraded in place: {}",
                    SafeText.from(exception, "the file could not be written", MESSAGE_LIMIT));
        }
    }

    private void write(WorldArchiveConfig config, Stored replaced) throws IOException {
        if (replaced.outdated()) {
            Path backup = file.resolveSibling(file.getFileName() + ".schema" + replaced.schemaVersion() + ".bak");
            if (!Files.exists(backup, LinkOption.NOFOLLOW_LINKS)) {
                AtomicFiles.writeUtf8(backup, replaced.text(), MAXIMUM_CONFIG_BYTES);
            }
        }
        AtomicFiles.writeUtf8(file, encode(config), MAXIMUM_CONFIG_BYTES);
    }

    private WorldArchiveConfig parse(JsonObject root, int schemaVersion) throws ConfigurationException {
        if (schemaVersion < OLDEST_SCHEMA_VERSION || schemaVersion > WorldArchiveConfig.CURRENT_SCHEMA_VERSION) {
            throw new UnsupportedSchemaVersionException(schemaVersion);
        }
        JsonObject destinations = FIELDS.requiredObject(root, "destinations");
        JsonObject git = FIELDS.requiredObject(destinations, "git");
        Optional<String> remoteTemplate = schemaVersion == REMOTE_TEMPLATE_SCHEMA_VERSION
                ? FIELDS.optionalString(git, "remoteUrlTemplate")
                : Optional.empty();
        return new WorldArchiveConfig(
                parseGlobalTriggers(FIELDS.requiredObject(root, "triggers")),
                parseGit(git),
                parseZip(FIELDS.requiredObject(destinations, "zip")),
                parseWorlds(FIELDS.requiredObjects(root, "worlds"), schemaVersion, remoteTemplate));
    }

    private static TriggerConfig parseGlobalTriggers(JsonObject object) throws ConfigurationException {
        return new TriggerConfig(
                FIELDS.requiredBoolean(object, "manualEnabled"),
                FIELDS.requiredBoolean(object, "worldExitEnabled"),
                FIELDS.requiredBoolean(object, "scheduledEnabled"),
                FIELDS.requiredInt(object, "scheduleIntervalMinutes"));
    }

    /** Fields that older versions wrote here (health, the 0.1.0 shared repository) are ignored. */
    private GitDestinationConfig parseGit(JsonObject object) throws ConfigurationException {
        return new GitDestinationConfig(
                FIELDS.requiredBoolean(object, "enabled"),
                optionalPath(object, "repositoryRoot"),
                FIELDS.requiredString(object, "remoteName"),
                parseDestinationTriggers(FIELDS.requiredObject(object, "triggers")),
                FIELDS.requiredStrings(object, "lfsPatterns"));
    }

    private ZipDestinationConfig parseZip(JsonObject object) throws ConfigurationException {
        return new ZipDestinationConfig(
                FIELDS.requiredBoolean(object, "enabled"),
                optionalPath(object, "destination"),
                parseDestinationTriggers(FIELDS.requiredObject(object, "triggers")));
    }

    private static DestinationTriggerConfig parseDestinationTriggers(JsonObject object)
            throws ConfigurationException {
        return new DestinationTriggerConfig(
                FIELDS.requiredBoolean(object, "manualEnabled"),
                FIELDS.requiredBoolean(object, "worldExitEnabled"),
                FIELDS.requiredBoolean(object, "scheduledEnabled"));
    }

    private List<WorldConfig> parseWorlds(
            List<JsonObject> encodedWorlds,
            int schemaVersion,
            Optional<String> remoteTemplate) throws ConfigurationException {
        List<WorldConfig> worlds = new ArrayList<>(encodedWorlds.size());
        for (JsonObject world : encodedWorlds) {
            WorldId worldId = WorldId.parse(FIELDS.requiredString(world, "worldId"));
            Optional<String> remoteUrl = remoteTemplate.isPresent()
                    ? Optional.of(remoteFromTemplate(remoteTemplate.get(), worldId))
                    : FIELDS.optionalString(world, "remoteUrl");
            StoragePolicy storagePolicy = schemaVersion >= STORAGE_POLICY_SCHEMA_VERSION
                    ? parseStoragePolicy(FIELDS.requiredObject(world, "storage"))
                    : StoragePolicy.defaults();
            worlds.add(new WorldConfig(
                    worldId,
                    FIELDS.requiredBoolean(world, "enabled"),
                    requiredPath(world, "path"),
                    remoteUrl,
                    optionalPath(world, "zipDestination"),
                    storagePolicy));
        }
        return worlds;
    }

    private static String remoteFromTemplate(String template, WorldId worldId) throws ConfigurationException {
        int first = template.indexOf(WORLD_ID_PLACEHOLDER);
        if (first < 0 || template.indexOf(WORLD_ID_PLACEHOLDER, first + 1) >= 0) {
            throw new ConfigurationException(
                    "Git remoteUrlTemplate must include exactly one {worldId} placeholder");
        }
        return template.replace(WORLD_ID_PLACEHOLDER, worldId.toString());
    }

    private static StoragePolicy parseStoragePolicy(JsonObject object) throws ConfigurationException {
        return new StoragePolicy(
                FIELDS.requiredLong(object, "budgetBytes"),
                FIELDS.requiredInt(object, "dailyCopies"),
                FIELDS.requiredInt(object, "weeklyCopies"),
                FIELDS.requiredInt(object, "monthlyCopies"));
    }

    private Path requiredPath(JsonObject object, String name) throws ConfigurationException {
        return optionalPath(object, name)
                .orElseThrow(() -> new ConfigurationException("Required filesystem path is missing: " + name));
    }

    /** A relative path is read from the folder that holds the settings file. */
    private Optional<Path> optionalPath(JsonObject object, String name) throws ConfigurationException {
        Optional<String> path = FIELDS.optionalString(object, name);
        if (path.isEmpty()) {
            return Optional.empty();
        }
        if (path.get().isBlank()) {
            throw new ConfigurationException("Filesystem path must not be blank: " + name);
        }
        try {
            return Optional.of(file.resolveSibling(path.get()).normalize());
        } catch (InvalidPathException exception) {
            throw new ConfigurationException("Invalid filesystem path in " + name, exception);
        }
    }

    private static String encode(WorldArchiveConfig config) {
        JsonObject root = new JsonObject();
        root.addProperty("schemaVersion", WorldArchiveConfig.CURRENT_SCHEMA_VERSION);
        root.add("triggers", encodeGlobalTriggers(config.triggers()));
        JsonObject destinations = new JsonObject();
        destinations.add("git", encodeGit(config.git()));
        destinations.add("zip", encodeZip(config.zip()));
        root.add("destinations", destinations);
        JsonArray worlds = new JsonArray();
        config.worlds().forEach(world -> worlds.add(encodeWorld(world)));
        root.add("worlds", worlds);
        return GSON.toJson(root) + "\n";
    }

    private static JsonObject encodeGlobalTriggers(TriggerConfig config) {
        JsonObject triggers = new JsonObject();
        triggers.addProperty("manualEnabled", config.manualEnabled());
        triggers.addProperty("worldExitEnabled", config.worldExitEnabled());
        triggers.addProperty("scheduledEnabled", config.scheduledEnabled());
        triggers.addProperty("scheduleIntervalMinutes", config.scheduleIntervalMinutes());
        return triggers;
    }

    private static JsonObject encodeGit(GitDestinationConfig config) {
        JsonObject git = new JsonObject();
        git.addProperty("enabled", config.enabled());
        config.repository().ifPresent(path -> git.addProperty("repositoryRoot", path.toString()));
        git.addProperty("remoteName", config.remoteName());
        git.add("triggers", encodeDestinationTriggers(config.triggers()));
        JsonArray lfsPatterns = new JsonArray();
        config.lfsPatterns().forEach(lfsPatterns::add);
        git.add("lfsPatterns", lfsPatterns);
        git.add("health", healthPlaceholder());
        return git;
    }

    private static JsonObject encodeZip(ZipDestinationConfig config) {
        JsonObject zip = new JsonObject();
        zip.addProperty("enabled", config.enabled());
        config.destination().ifPresent(path -> zip.addProperty("destination", path.toString()));
        zip.add("triggers", encodeDestinationTriggers(config.triggers()));
        zip.add("health", healthPlaceholder());
        return zip;
    }

    /**
     * WorldArchive 0.3.9 and older refuse a destination without this object, and a version that
     * cannot read its settings replaces them with defaults. Writing it keeps a downgrade from
     * losing the settings; it is ignored when read.
     */
    private static JsonObject healthPlaceholder() {
        JsonObject health = new JsonObject();
        health.addProperty("status", "UNCONFIGURED");
        health.addProperty("message", "Not checked");
        health.addProperty("checkedAt", Instant.EPOCH.toString());
        return health;
    }

    private static JsonObject encodeDestinationTriggers(DestinationTriggerConfig config) {
        JsonObject triggers = new JsonObject();
        triggers.addProperty("manualEnabled", config.manualEnabled());
        triggers.addProperty("worldExitEnabled", config.worldExitEnabled());
        triggers.addProperty("scheduledEnabled", config.scheduledEnabled());
        return triggers;
    }

    private static JsonObject encodeWorld(WorldConfig config) {
        JsonObject world = new JsonObject();
        world.addProperty("worldId", config.worldId().toString());
        world.addProperty("enabled", config.enabled());
        world.addProperty("path", config.path().toString());
        config.remoteUrl().ifPresent(url -> world.addProperty("remoteUrl", url));
        config.zipDestination().ifPresent(path -> world.addProperty("zipDestination", path.toString()));
        JsonObject storage = new JsonObject();
        storage.addProperty("budgetBytes", config.storagePolicy().budgetBytes());
        storage.addProperty("dailyCopies", config.storagePolicy().dailyCopies());
        storage.addProperty("weeklyCopies", config.storagePolicy().weeklyCopies());
        storage.addProperty("monthlyCopies", config.storagePolicy().monthlyCopies());
        world.add("storage", storage);
        return world;
    }

    /** Refuses a hand-written file that keeps a password or token under its own key; WorldArchive never writes one. */
    private static void rejectCredentialFields(JsonElement element) throws ConfigurationException {
        if (element.isJsonObject()) {
            for (Map.Entry<String, JsonElement> field : element.getAsJsonObject().entrySet()) {
                if (isCredentialName(field.getKey())) {
                    throw new ConfigurationException(
                            "Credential fields are not permitted in WorldArchive configuration");
                }
                rejectCredentialFields(field.getValue());
            }
        } else if (element.isJsonArray()) {
            for (JsonElement value : element.getAsJsonArray()) {
                rejectCredentialFields(value);
            }
        }
    }

    private static boolean isCredentialName(String name) {
        String normalized = name.toLowerCase(Locale.ROOT).replace("_", "").replace("-", "");
        return normalized.contains("password")
                || normalized.contains("passwd")
                || normalized.contains("token")
                || normalized.contains("secret")
                || normalized.contains("credential")
                || normalized.contains("apikey");
    }

    /** The settings written by {@link #reset}, and where the unreadable file was kept. */
    public record Reset(WorldArchiveConfig config, Path keptCopy) {
        public Reset {
            Objects.requireNonNull(config, "config");
            Objects.requireNonNull(keptCopy, "keptCopy");
        }
    }

    /** What the file held: the settings as written in it, its schema version, and its text. */
    private record Stored(WorldArchiveConfig config, int schemaVersion, String text) {
        boolean outdated() {
            return schemaVersion < WorldArchiveConfig.CURRENT_SCHEMA_VERSION;
        }
    }
}
