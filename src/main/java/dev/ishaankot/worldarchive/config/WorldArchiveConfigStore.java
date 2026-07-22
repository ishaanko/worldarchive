package dev.ishaankot.worldarchive.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import dev.ishaankot.worldarchive.core.AtomicFiles;
import dev.ishaankot.worldarchive.model.DestinationHealth;
import dev.ishaankot.worldarchive.model.DestinationHealthStatus;
import dev.ishaankot.worldarchive.model.DestinationType;
import dev.ishaankot.worldarchive.model.SensitiveDataRedactor;
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
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.ReentrantLock;

/** Atomic UTF-8 JSON persistence and migration for {@link WorldArchiveConfig}. */
public final class WorldArchiveConfigStore {
    private static final int MAXIMUM_CONFIG_BYTES = 1_048_576;

    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .disableHtmlEscaping()
            .create();

    private static final ConcurrentMap<Path, ReentrantLock> JVM_LOCKS = new ConcurrentHashMap<>();

    private final Path file;

    private final Path lockFile;

    private final ReentrantLock jvmLock;

    public WorldArchiveConfigStore(Path file) throws IOException {
        Path normalized = file.toAbsolutePath().normalize();
        rejectSymlink(normalized, "Configuration file");
        this.file = PathSafety.canonicalize(normalized);
        this.lockFile = this.file.resolveSibling(this.file.getFileName() + ".lock");
        this.jvmLock = JVM_LOCKS.computeIfAbsent(this.file, ignored -> new ReentrantLock());
    }

    /** Loads and, when needed, atomically migrates using the complete set of known source worlds. */
    public WorldArchiveConfig load(Collection<Path> knownWorldPaths) throws IOException {
        return withLock(() -> loadUnlocked(knownWorldPaths));
    }

    /** Atomically saves only after checking every destination against all known source worlds. */
    public void save(WorldArchiveConfig config, Collection<Path> knownWorldPaths) throws IOException {
        withLock(() -> {
            writeUnlocked(config.validateDestinations(knownWorldPaths));
            return null;
        });
    }

    public Path file() {
        return file;
    }

    private WorldArchiveConfig loadUnlocked(Collection<Path> knownWorldPaths) throws IOException {
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
            return WorldArchiveConfig.defaults();
        }
        rejectSymlink(file, "Configuration file");
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            throw new ConfigurationException("Configuration path is not a regular file");
        }
        if (Files.size(file) > MAXIMUM_CONFIG_BYTES) {
            throw new ConfigurationException("Configuration file is unexpectedly large");
        }
        JsonObject root = parseObject(Files.readString(file, StandardCharsets.UTF_8));
        rejectCredentialData(root);
        int schemaVersion = optionalInteger(root, "schemaVersion").orElse(0);
        if (schemaVersion > WorldArchiveConfig.CURRENT_SCHEMA_VERSION) {
            throw new UnsupportedSchemaVersionException(schemaVersion);
        }
        if (schemaVersion < 0) {
            throw new ConfigurationException("Configuration schema version must not be negative");
        }
        try {
            WorldArchiveConfig parsed;
            boolean migrated;
            if (schemaVersion == 0) {
                if (!looksLikeLegacyConfiguration(root)) {
                    throw new ConfigurationException(
                            "Configuration has no schema version and does not match the legacy layout");
                }
                parsed = migrateLegacy(root);
                migrated = true;
            } else if (schemaVersion == 1) {
                parsed = migrateVersionOne(root);
                migrated = true;
            } else if (schemaVersion == 2) {
                parsed = migrateVersionTwo(root);
                migrated = true;
            } else if (schemaVersion == 3) {
                parsed = migrateVersionThree(root);
                migrated = true;
            } else if (schemaVersion == 4) {
                parsed = migrateVersionFour(root);
                migrated = true;
            } else if (schemaVersion == 5) {
                parsed = parseCurrent(root);
                migrated = true;
            } else {
                parsed = parseCurrent(root);
                migrated = false;
            }
            WorldArchiveConfig validated = parsed.validateDestinations(knownWorldPaths);
            if (migrated) {
                writeUnlocked(validated);
            }
            return validated;
        } catch (IllegalArgumentException exception) {
            throw new ConfigurationException("WorldArchive configuration is invalid: " + exception.getMessage(), exception);
        }
    }

    private WorldArchiveConfig parseCurrent(JsonObject root) throws IOException {
        TriggerConfig triggers = parseGlobalTriggers(requiredObject(root, "triggers"));
        JsonObject destinations = requiredObject(root, "destinations");
        GitDestinationConfig git = parseCurrentGit(requiredObject(destinations, "git"));
        ZipDestinationConfig zip = parseCurrentZip(requiredObject(destinations, "zip"));
        return new WorldArchiveConfig(
                WorldArchiveConfig.CURRENT_SCHEMA_VERSION,
                triggers,
                git,
                zip,
                parseWorlds(requiredArray(root, "worlds")));
    }

    private WorldArchiveConfig migrateVersionOne(JsonObject root) throws IOException {
        TriggerConfig triggers = parseGlobalTriggers(requiredObject(root, "triggers"));
        JsonObject destinations = requiredObject(root, "destinations");
        JsonObject gitObject = requiredObject(destinations, "git");
        GitDestinationConfig git = migratedGit(
                requiredBoolean(gitObject, "enabled"),
                optionalPath(gitObject, "repository"),
                requiredString(gitObject, "remoteName"),
                optionalString(gitObject, "remoteUrl"),
                DestinationTriggerConfig.defaults(),
                GitDestinationConfig.DEFAULT_LFS_PATTERNS);
        JsonObject zipObject = requiredObject(destinations, "zip");
        ZipDestinationConfig zip = new ZipDestinationConfig(
                requiredBoolean(zipObject, "enabled"),
                optionalPath(zipObject, "destination"));
        return new WorldArchiveConfig(WorldArchiveConfig.CURRENT_SCHEMA_VERSION, triggers, git, zip, List.of());
    }

    private WorldArchiveConfig migrateVersionTwo(JsonObject root) throws IOException {
        TriggerConfig triggers = parseGlobalTriggers(requiredObject(root, "triggers"));
        JsonObject destinations = requiredObject(root, "destinations");
        JsonObject gitObject = requiredObject(destinations, "git");
        GitDestinationConfig git = migratedGit(
                requiredBoolean(gitObject, "enabled"),
                optionalPath(gitObject, "repository"),
                requiredString(gitObject, "remoteName"),
                optionalString(gitObject, "remoteUrl"),
                parseDestinationTriggers(requiredObject(gitObject, "triggers")),
                requiredStringArray(gitObject, "lfsPatterns"));
        JsonObject zipObject = requiredObject(destinations, "zip");
        ZipDestinationConfig zip = new ZipDestinationConfig(
                requiredBoolean(zipObject, "enabled"),
                optionalPath(zipObject, "destination"),
                parseDestinationTriggers(requiredObject(zipObject, "triggers")));
        return new WorldArchiveConfig(
                WorldArchiveConfig.CURRENT_SCHEMA_VERSION,
                triggers,
                git,
                zip,
                parseWorlds(requiredArray(root, "worlds")));
    }

    private WorldArchiveConfig migrateVersionThree(JsonObject root) throws IOException {
        TriggerConfig triggers = parseGlobalTriggers(requiredObject(root, "triggers"));
        JsonObject destinations = requiredObject(root, "destinations");
        JsonObject gitObject = requiredObject(destinations, "git");
        GitDestinationConfig git = migratedGit(
                requiredBoolean(gitObject, "enabled"),
                optionalPath(gitObject, "repository"),
                requiredString(gitObject, "remoteName"),
                optionalString(gitObject, "remoteUrl"),
                parseDestinationTriggers(requiredObject(gitObject, "triggers")),
                requiredStringArray(gitObject, "lfsPatterns"));
        ZipDestinationConfig zip = parseCurrentZip(requiredObject(destinations, "zip"));
        return new WorldArchiveConfig(
                WorldArchiveConfig.CURRENT_SCHEMA_VERSION,
                triggers,
                git,
                zip,
                parseWorlds(requiredArray(root, "worlds")));
    }

    /** Moves the schema-4 global URL template onto the worlds that it previously covered. */
    private WorldArchiveConfig migrateVersionFour(JsonObject root) throws IOException {
        TriggerConfig triggers = parseGlobalTriggers(requiredObject(root, "triggers"));
        JsonObject destinations = requiredObject(root, "destinations");
        JsonObject gitObject = requiredObject(destinations, "git");
        Optional<String> template = optionalString(gitObject, "remoteUrlTemplate");
        if (template.isPresent()
                && !GitDestinationConfig.isPerWorldRemoteTemplate(template.orElseThrow())) {
            throw new ConfigurationException(
                    "Git remoteUrlTemplate must include exactly one {worldId} placeholder");
        }
        GitDestinationConfig git = new GitDestinationConfig(
                requiredBoolean(gitObject, "enabled"),
                optionalPath(gitObject, "repositoryRoot"),
                requiredString(gitObject, "remoteName"),
                Optional.empty(),
                parseDestinationTriggers(requiredObject(gitObject, "triggers")),
                requiredStringArray(gitObject, "lfsPatterns"),
                parseHealth(requiredObject(gitObject, "health"), DestinationType.GIT),
                optionalPath(gitObject, "legacySharedRepository"),
                optionalString(gitObject, "legacyRemoteUrl"));
        List<WorldConfig> worlds = parseWorlds(requiredArray(root, "worlds")).stream()
                .map(world -> new WorldConfig(
                        world.worldId(),
                        world.enabled(),
                        world.path(),
                        template.map(value -> RemoteUrlPolicy.resolveWorldId(
                                value,
                                world.worldId().value()))))
                .toList();
        return new WorldArchiveConfig(
                WorldArchiveConfig.CURRENT_SCHEMA_VERSION,
                triggers,
                git,
                parseCurrentZip(requiredObject(destinations, "zip")),
                worlds);
    }

    /** Migrates the unversioned prototype layout that preceded schema version 1. */
    private WorldArchiveConfig migrateLegacy(JsonObject root) throws IOException {
        TriggerConfig triggers = new TriggerConfig(
                optionalBoolean(root, "manualBackups").orElse(true),
                optionalBoolean(root, "exitBackups").orElse(true),
                optionalBoolean(root, "scheduleEnabled").orElse(false),
                optionalInteger(root, "scheduleMinutes")
                        .orElse(TriggerConfig.DEFAULT_SCHEDULE_INTERVAL_MINUTES));
        GitDestinationConfig git = migratedGit(
                optionalBoolean(root, "gitEnabled").orElse(true),
                optionalPath(root, "gitRepository"),
                optionalString(root, "gitRemoteName").orElse(GitDestinationConfig.DEFAULT_REMOTE_NAME),
                optionalString(root, "gitRemoteUrl"),
                DestinationTriggerConfig.defaults(),
                GitDestinationConfig.DEFAULT_LFS_PATTERNS);
        ZipDestinationConfig zip = new ZipDestinationConfig(
                optionalBoolean(root, "zipEnabled").orElse(true),
                optionalPath(root, "zipDestination"));
        return new WorldArchiveConfig(WorldArchiveConfig.CURRENT_SCHEMA_VERSION, triggers, git, zip, List.of());
    }

    private TriggerConfig parseGlobalTriggers(JsonObject object) throws ConfigurationException {
        return new TriggerConfig(
                requiredBoolean(object, "manualEnabled"),
                requiredBoolean(object, "worldExitEnabled"),
                requiredBoolean(object, "scheduledEnabled"),
                requiredInteger(object, "scheduleIntervalMinutes"));
    }

    private GitDestinationConfig parseCurrentGit(JsonObject object) throws IOException {
        return new GitDestinationConfig(
                requiredBoolean(object, "enabled"),
                optionalPath(object, "repositoryRoot"),
                requiredString(object, "remoteName"),
                Optional.empty(),
                parseDestinationTriggers(requiredObject(object, "triggers")),
                requiredStringArray(object, "lfsPatterns"),
                parseHealth(requiredObject(object, "health"), DestinationType.GIT),
                optionalPath(object, "legacySharedRepository"),
                optionalString(object, "legacyRemoteUrl"));
    }

    private static GitDestinationConfig migratedGit(
            boolean enabled,
            Optional<Path> legacyRepository,
            String remoteName,
            Optional<String> legacyRemoteUrl,
            DestinationTriggerConfig triggers,
            List<String> lfsPatterns) {
        return new GitDestinationConfig(
                enabled,
                Optional.empty(),
                remoteName,
                Optional.empty(),
                triggers,
                lfsPatterns,
                DestinationHealth.notChecked(DestinationType.GIT),
                legacyRepository,
                legacyRemoteUrl);
    }

    private ZipDestinationConfig parseCurrentZip(JsonObject object) throws IOException {
        return new ZipDestinationConfig(
                requiredBoolean(object, "enabled"),
                optionalPath(object, "destination"),
                parseDestinationTriggers(requiredObject(object, "triggers")),
                parseHealth(requiredObject(object, "health"), DestinationType.ZIP));
    }

    private static DestinationTriggerConfig parseDestinationTriggers(JsonObject object)
            throws ConfigurationException {
        return new DestinationTriggerConfig(
                requiredBoolean(object, "manualEnabled"),
                requiredBoolean(object, "worldExitEnabled"),
                requiredBoolean(object, "scheduledEnabled"));
    }

    private static DestinationHealth parseHealth(JsonObject object, DestinationType destination)
            throws ConfigurationException {
        return new DestinationHealth(
                destination,
                requiredEnum(object, "status", DestinationHealthStatus.class),
                requiredString(object, "message"),
                requiredInstant(object, "checkedAt"));
    }

    private void writeUnlocked(WorldArchiveConfig config) throws IOException {
        rejectSymlink(file, "Configuration file");
        AtomicFiles.writeUtf8(file, GSON.toJson(encode(config)) + System.lineSeparator());
    }

    private static JsonObject encode(WorldArchiveConfig config) {
        JsonObject root = new JsonObject();
        root.addProperty("schemaVersion", config.schemaVersion());
        root.add("triggers", encodeGlobalTriggers(config.triggers()));

        JsonObject destinations = new JsonObject();
        JsonObject git = new JsonObject();
        git.addProperty("enabled", config.git().enabled());
        config.git().repository().ifPresent(path -> git.addProperty("repositoryRoot", path.toString()));
        git.addProperty("remoteName", config.git().remoteName());
        config.git().legacyRepository()
                .ifPresent(path -> git.addProperty("legacySharedRepository", path.toString()));
        config.git().legacyRemoteUrl().ifPresent(url -> git.addProperty("legacyRemoteUrl", url));
        git.add("triggers", encodeDestinationTriggers(config.git().triggers()));
        JsonArray lfsPatterns = new JsonArray();
        config.git().lfsPatterns().forEach(lfsPatterns::add);
        git.add("lfsPatterns", lfsPatterns);
        git.add("health", encodeHealth(config.git().health()));
        destinations.add("git", git);

        JsonObject zip = new JsonObject();
        zip.addProperty("enabled", config.zip().enabled());
        config.zip().destination().ifPresent(path -> zip.addProperty("destination", path.toString()));
        zip.add("triggers", encodeDestinationTriggers(config.zip().triggers()));
        zip.add("health", encodeHealth(config.zip().health()));
        destinations.add("zip", zip);
        root.add("destinations", destinations);

        JsonArray worlds = new JsonArray();
        for (WorldConfig worldConfig : config.worlds()) {
            JsonObject world = new JsonObject();
            world.addProperty("worldId", worldConfig.worldId().toString());
            world.addProperty("enabled", worldConfig.enabled());
            world.addProperty("path", worldConfig.path().toString());
            worldConfig.remoteUrl().ifPresent(url -> world.addProperty("remoteUrl", url));
            worldConfig.zipDestination()
                    .ifPresent(path -> world.addProperty("zipDestination", path.toString()));
            worlds.add(world);
        }
        root.add("worlds", worlds);
        return root;
    }

    private static JsonObject encodeGlobalTriggers(TriggerConfig config) {
        JsonObject triggers = new JsonObject();
        triggers.addProperty("manualEnabled", config.manualEnabled());
        triggers.addProperty("worldExitEnabled", config.worldExitEnabled());
        triggers.addProperty("scheduledEnabled", config.scheduledEnabled());
        triggers.addProperty("scheduleIntervalMinutes", config.scheduleIntervalMinutes());
        return triggers;
    }

    private static JsonObject encodeHealth(DestinationHealth health) {
        JsonObject encoded = new JsonObject();
        encoded.addProperty("status", health.status().name());
        encoded.addProperty("message", health.message());
        encoded.addProperty("checkedAt", health.checkedAt().toString());
        return encoded;
    }

    private static JsonObject encodeDestinationTriggers(DestinationTriggerConfig config) {
        JsonObject triggers = new JsonObject();
        triggers.addProperty("manualEnabled", config.manualEnabled());
        triggers.addProperty("worldExitEnabled", config.worldExitEnabled());
        triggers.addProperty("scheduledEnabled", config.scheduledEnabled());
        return triggers;
    }

    private <T> T withLock(IoSupplier<T> operation) throws IOException {
        jvmLock.lock();
        try {
            Path parent = file.getParent();
            if (parent == null) {
                throw new ConfigurationException("Configuration path has no parent directory");
            }
            Files.createDirectories(parent);
            rejectSymlink(file, "Configuration file");
            rejectSymlink(lockFile, "Configuration lock file");
            try (FileChannel channel = FileChannel.open(
                            lockFile,
                            StandardOpenOption.CREATE,
                            StandardOpenOption.WRITE,
                            LinkOption.NOFOLLOW_LINKS);
                    FileLock ignored = channel.lock()) {
                rejectSymlink(file, "Configuration file");
                return operation.get();
            }
        } finally {
            jvmLock.unlock();
        }
    }

    private static JsonObject parseObject(String json) throws ConfigurationException {
        try {
            JsonElement element = JsonParser.parseString(json);
            if (!element.isJsonObject()) {
                throw new ConfigurationException("WorldArchive configuration root must be a JSON object");
            }
            return element.getAsJsonObject();
        } catch (JsonParseException exception) {
            throw new ConfigurationException("WorldArchive configuration is malformed JSON", exception);
        }
    }

    private static JsonObject requiredObject(JsonObject object, String name) throws ConfigurationException {
        JsonElement element = object.get(name);
        if (element == null || !element.isJsonObject()) {
            throw new ConfigurationException("Required object is missing or invalid: " + name);
        }
        return element.getAsJsonObject();
    }

    private static JsonArray requiredArray(JsonObject object, String name) throws ConfigurationException {
        JsonElement element = object.get(name);
        if (element == null || !element.isJsonArray()) {
            throw new ConfigurationException("Required array is missing or invalid: " + name);
        }
        return element.getAsJsonArray();
    }

    private static boolean requiredBoolean(JsonObject object, String name) throws ConfigurationException {
        return optionalBoolean(object, name)
                .orElseThrow(() -> new ConfigurationException("Required boolean is missing: " + name));
    }

    private static Optional<Boolean> optionalBoolean(JsonObject object, String name)
            throws ConfigurationException {
        JsonElement element = object.get(name);
        if (element == null || element.isJsonNull()) {
            return Optional.empty();
        }
        if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isBoolean()) {
            throw new ConfigurationException("Expected a boolean: " + name);
        }
        return Optional.of(element.getAsBoolean());
    }

    private static int requiredInteger(JsonObject object, String name) throws ConfigurationException {
        return optionalInteger(object, name)
                .orElseThrow(() -> new ConfigurationException("Required integer is missing: " + name));
    }

    private static Optional<Integer> optionalInteger(JsonObject object, String name)
            throws ConfigurationException {
        JsonElement element = object.get(name);
        if (element == null || element.isJsonNull()) {
            return Optional.empty();
        }
        if (!element.isJsonPrimitive()) {
            throw new ConfigurationException("Expected an integer: " + name);
        }
        JsonPrimitive primitive = element.getAsJsonPrimitive();
        if (!primitive.isNumber()) {
            throw new ConfigurationException("Expected an integer: " + name);
        }
        try {
            return Optional.of(new BigDecimal(primitive.getAsString()).intValueExact());
        } catch (ArithmeticException | NumberFormatException exception) {
            throw new ConfigurationException("Expected an integer: " + name, exception);
        }
    }

    private static String requiredString(JsonObject object, String name) throws ConfigurationException {
        return optionalString(object, name)
                .orElseThrow(() -> new ConfigurationException("Required string is missing: " + name));
    }

    private static Optional<String> optionalString(JsonObject object, String name)
            throws ConfigurationException {
        JsonElement element = object.get(name);
        if (element == null || element.isJsonNull()) {
            return Optional.empty();
        }
        if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
            throw new ConfigurationException("Expected a string: " + name);
        }
        return Optional.of(element.getAsString());
    }

    private static List<String> requiredStringArray(JsonObject object, String name)
            throws ConfigurationException {
        JsonArray array = requiredArray(object, name);
        List<String> values = new ArrayList<>(array.size());
        for (JsonElement element : array) {
            if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
                throw new ConfigurationException("Expected an array of strings: " + name);
            }
            values.add(element.getAsString());
        }
        return values;
    }

    private static Instant requiredInstant(JsonObject object, String name) throws ConfigurationException {
        try {
            return Instant.parse(requiredString(object, name));
        } catch (DateTimeParseException exception) {
            throw new ConfigurationException("Expected an ISO-8601 instant: " + name, exception);
        }
    }

    private static <E extends Enum<E>> E requiredEnum(
            JsonObject object,
            String name,
            Class<E> enumType) throws ConfigurationException {
        try {
            return Enum.valueOf(enumType, requiredString(object, name));
        } catch (IllegalArgumentException exception) {
            throw new ConfigurationException("Enum value is invalid: " + name, exception);
        }
    }

    private List<WorldConfig> parseWorlds(JsonArray worldsArray) throws IOException {
        List<WorldConfig> worlds = new ArrayList<>(worldsArray.size());
        for (JsonElement element : worldsArray) {
            if (!element.isJsonObject()) {
                throw new ConfigurationException("Per-world configuration must be an object");
            }
            JsonObject world = element.getAsJsonObject();
            worlds.add(new WorldConfig(
                    WorldId.parse(requiredString(world, "worldId")),
                    requiredBoolean(world, "enabled"),
                    requiredPath(world, "path"),
                    optionalString(world, "remoteUrl"),
                    optionalPath(world, "zipDestination")));
        }
        return worlds;
    }

    private Path requiredPath(JsonObject object, String name) throws IOException {
        return optionalPath(object, name)
                .orElseThrow(() -> new ConfigurationException("Required filesystem path is missing: " + name));
    }

    private Optional<Path> optionalPath(JsonObject object, String name) throws IOException {
        Optional<String> path = optionalString(object, name);
        if (path.isEmpty()) {
            return Optional.empty();
        }
        if (path.get().isBlank()) {
            throw new ConfigurationException("Filesystem path must not be blank: " + name);
        }
        try {
            Path parsed = Path.of(path.get());
            if (!parsed.isAbsolute()) {
                Path parent = file.getParent();
                if (parent == null) {
                    throw new ConfigurationException("Configuration path has no parent directory");
                }
                parsed = parent.resolve(parsed);
            }
            return Optional.of(PathSafety.canonicalize(parsed));
        } catch (RuntimeException exception) {
            throw new ConfigurationException("Invalid filesystem path in " + name, exception);
        }
    }

    private static boolean looksLikeLegacyConfiguration(JsonObject root) {
        return root.has("manualBackups")
                || root.has("exitBackups")
                || root.has("scheduleEnabled")
                || root.has("scheduleMinutes")
                || root.has("gitEnabled")
                || root.has("gitRepository")
                || root.has("gitRemoteName")
                || root.has("gitRemoteUrl")
                || root.has("zipEnabled")
                || root.has("zipDestination");
    }

    private static void rejectCredentialData(JsonElement element) throws ConfigurationException {
        if (element.isJsonObject()) {
            JsonObject object = element.getAsJsonObject();
            for (String name : object.keySet()) {
                String normalized = name.toLowerCase(Locale.ROOT).replace("_", "").replace("-", "");
                if (normalized.contains("password")
                        || normalized.contains("passwd")
                        || normalized.contains("token")
                        || normalized.contains("secret")
                        || normalized.contains("credential")
                        || normalized.contains("apikey")) {
                    throw new ConfigurationException(
                            "Credential fields are not permitted in WorldArchive configuration");
                }
                rejectCredentialData(object.get(name));
            }
        } else if (element.isJsonArray()) {
            for (JsonElement value : element.getAsJsonArray()) {
                rejectCredentialData(value);
            }
        } else if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()
                && SensitiveDataRedactor.containsSensitiveData(element.getAsString())) {
            throw new ConfigurationException("Sensitive values are not permitted in WorldArchive configuration");
        }
    }

    private static void rejectSymlink(Path path, String description) throws ConfigurationException {
        if (Files.isSymbolicLink(path)) {
            throw new ConfigurationException(description + " must not be a symbolic link");
        }
    }

    @FunctionalInterface
    private interface IoSupplier<T> {
        T get() throws IOException;
    }
}
