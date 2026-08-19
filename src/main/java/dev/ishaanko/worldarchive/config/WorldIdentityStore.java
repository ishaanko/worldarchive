package dev.ishaanko.worldarchive.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import dev.ishaanko.worldarchive.core.AtomicFiles;
import dev.ishaanko.worldarchive.model.BackupId;
import dev.ishaanko.worldarchive.model.WorldId;
import dev.ishaanko.worldarchive.model.WorldIdentity;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Optional;

/** Stable, versioned per-world identity with atomic creation and restored-copy provenance. */
public final class WorldIdentityStore {
    private static final String METADATA_DIRECTORY = ".worldarchive";

    private static final String IDENTITY_FILE = "world.json";

    private static final int MAXIMUM_IDENTITY_BYTES = 4_096;

    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .disableHtmlEscaping()
            .create();

    /** Compatibility helper for callers that only need the stable UUID. */
    public WorldId loadOrCreate(Path worldDirectory) throws IOException {
        return loadOrCreateIdentity(worldDirectory).worldId();
    }

    public WorldIdentity loadOrCreateIdentity(Path worldDirectory) throws IOException {
        Path metadata = prepareMetadata(worldDirectory);
        return withLock(metadata, identityFile -> {
            if (Files.exists(identityFile, LinkOption.NOFOLLOW_LINKS)) {
                return read(identityFile);
            }
            WorldIdentity created = WorldIdentity.original(WorldId.create());
            write(identityFile, created);
            return created;
        });
    }

    /** Reads an existing identity without creating one; empty when the folder carries none. */
    public Optional<WorldIdentity> loadExisting(Path worldDirectory) throws IOException {
        Path world = worldDirectory.toRealPath();
        if (!Files.isDirectory(world, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("World path is not a directory: " + world);
        }
        Path metadata = world.resolve(METADATA_DIRECTORY);
        if (Files.isSymbolicLink(metadata)) {
            throw new IOException("World identity metadata directory must not be a symbolic link");
        }
        if (!Files.isDirectory(metadata, LinkOption.NOFOLLOW_LINKS)) {
            return Optional.empty();
        }
        Path realMetadata = metadata.toRealPath();
        if (!realMetadata.startsWith(world)) {
            throw new IOException("World identity metadata escaped the world directory");
        }
        return withLock(realMetadata, identityFile ->
                Files.exists(identityFile, LinkOption.NOFOLLOW_LINKS)
                        ? Optional.of(read(identityFile))
                        : Optional.empty());
    }

    /** Explicitly replaces a copied source identity with a fresh restored-world identity. */
    public WorldIdentity createFreshRestoredCopyIdentity(
            Path restoredWorldDirectory,
            BackupId sourceBackupId) throws IOException {
        Path metadata = prepareMetadata(restoredWorldDirectory);
        return withLock(metadata, identityFile -> {
            WorldIdentity created = WorldIdentity.restoredCopy(WorldId.create(), sourceBackupId);
            write(identityFile, created);
            return created;
        });
    }

    private static Path prepareMetadata(Path worldDirectory) throws IOException {
        Path world = worldDirectory.toRealPath();
        if (!Files.isDirectory(world, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("World path is not a directory: " + world);
        }
        Path metadata = world.resolve(METADATA_DIRECTORY);
        if (Files.isSymbolicLink(metadata)) {
            throw new IOException("World identity metadata directory must not be a symbolic link");
        }
        Files.createDirectories(metadata);
        Path realMetadata = metadata.toRealPath();
        if (!realMetadata.startsWith(world)) {
            throw new IOException("World identity metadata escaped the world directory");
        }
        return realMetadata;
    }

    private static <T> T withLock(Path metadata, IdentityOperation<T> operation) throws IOException {
        Path identityFile = metadata.resolve(IDENTITY_FILE);
        return new LockedFileStore(identityFile, IOException::new)
                .withLock(() -> operation.apply(identityFile));
    }

    private static WorldIdentity read(Path identityFile) throws IOException {
        rejectSymlink(identityFile, "World identity file");
        if (!Files.isRegularFile(identityFile, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("World identity is not a regular file");
        }
        if (Files.size(identityFile) > MAXIMUM_IDENTITY_BYTES) {
            throw new IOException("World identity file is unexpectedly large");
        }
        try {
            JsonElement parsed = JsonParser.parseString(
                    AtomicFiles.readUtf8(identityFile, MAXIMUM_IDENTITY_BYTES));
            if (!parsed.isJsonObject()) {
                throw new IOException("World identity root must be a JSON object");
            }
            JsonObject object = parsed.getAsJsonObject();
            int schemaVersion = requiredInteger(object, "schemaVersion");
            if (schemaVersion > WorldIdentity.CURRENT_SCHEMA_VERSION) {
                throw new IOException("Unsupported future world identity schema: " + schemaVersion);
            }
            WorldId worldId = WorldId.parse(requiredString(object, "worldId"));
            Optional<BackupId> sourceBackupId = optionalString(object, "sourceBackupId").map(BackupId::parse);
            return new WorldIdentity(schemaVersion, worldId, sourceBackupId);
        } catch (JsonParseException | IllegalArgumentException exception) {
            throw new IOException("World identity is malformed or invalid", exception);
        }
    }

    private static void write(Path identityFile, WorldIdentity identity) throws IOException {
        rejectSymlink(identityFile, "World identity file");
        JsonObject object = new JsonObject();
        object.addProperty("schemaVersion", identity.schemaVersion());
        object.addProperty("worldId", identity.worldId().toString());
        identity.sourceBackupId().ifPresent(id -> object.addProperty("sourceBackupId", id.toString()));
        AtomicFiles.writeUtf8(
                identityFile,
                GSON.toJson(object) + System.lineSeparator(),
                MAXIMUM_IDENTITY_BYTES);
    }

    private static void rejectSymlink(Path path, String description) throws IOException {
        if (Files.isSymbolicLink(path)) {
            throw new IOException(description + " must not be a symbolic link");
        }
    }

    private static int requiredInteger(JsonObject object, String name) throws IOException {
        JsonElement element = object.get(name);
        if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) {
            throw new IOException("Required world identity integer is missing or invalid: " + name);
        }
        try {
            return new BigDecimal(element.getAsString()).intValueExact();
        } catch (ArithmeticException | NumberFormatException exception) {
            throw new IOException("World identity integer is invalid: " + name, exception);
        }
    }

    private static String requiredString(JsonObject object, String name) throws IOException {
        return optionalString(object, name)
                .orElseThrow(() -> new IOException("Required world identity string is missing: " + name));
    }

    private static Optional<String> optionalString(JsonObject object, String name) throws IOException {
        JsonElement element = object.get(name);
        if (element == null || element.isJsonNull()) {
            return Optional.empty();
        }
        if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
            throw new IOException("World identity value must be a string: " + name);
        }
        return Optional.of(element.getAsString());
    }

    @FunctionalInterface
    private interface IdentityOperation<T> {
        T apply(Path identityFile) throws IOException;
    }
}
