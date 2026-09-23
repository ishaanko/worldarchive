package dev.ishaanko.worldarchive.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import dev.ishaanko.worldarchive.model.BackupId;
import dev.ishaanko.worldarchive.model.WorldId;
import dev.ishaanko.worldarchive.model.WorldIdentity;
import dev.ishaanko.worldarchive.support.AtomicFiles;
import dev.ishaanko.worldarchive.support.JsonFields;
import dev.ishaanko.worldarchive.support.LockedFile;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

/**
 * The identity each world folder carries in {@code .worldarchive/world.json}, so its backups
 * stay its own when the folder is renamed or moved. Identities are created and replaced under a
 * lock and published atomically, so readers never see half a file.
 */
public final class WorldIdentityStore {
    private static final String METADATA_DIRECTORY = ".worldarchive";

    private static final String IDENTITY_FILE = "world.json";

    private static final int MAXIMUM_IDENTITY_BYTES = 4_096;

    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .disableHtmlEscaping()
            .create();

    private static final JsonFields<IOException> FIELDS = new JsonFields<>(IOException::new);

    /** The world's ID; a new identity is created when the folder has none. */
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

    /** Reads an existing identity without creating any file; empty when the folder carries none. */
    public Optional<WorldIdentity> loadExisting(Path worldDirectory) throws IOException {
        Path world = worldDirectory.toRealPath();
        Path identityFile = world.resolve(METADATA_DIRECTORY).resolve(IDENTITY_FILE);
        if (!Files.isDirectory(world, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("World path is not a directory: " + world);
        }
        if (Files.isSymbolicLink(identityFile.getParent())) {
            throw new IOException("World identity metadata directory must not be a symbolic link");
        }
        return Files.exists(identityFile, LinkOption.NOFOLLOW_LINKS)
                ? Optional.of(read(identityFile))
                : Optional.empty();
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

    /**
     * Gives a world folder copied in a file manager its own identity, so its backups never mix
     * with the original's. Nothing changes when the folder no longer carries {@code copiedId}.
     *
     * @return the identity the folder carries afterwards
     */
    public WorldIdentity giveCopyItsOwnIdentity(Path copyDirectory, WorldId copiedId) throws IOException {
        Objects.requireNonNull(copiedId, "copiedId");
        Path metadata = prepareMetadata(copyDirectory);
        return withLock(metadata, identityFile -> {
            WorldIdentity current = read(identityFile);
            if (!current.worldId().equals(copiedId)) {
                return current;
            }
            WorldIdentity own = WorldIdentity.original(WorldId.create());
            write(identityFile, own);
            return own;
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
        return new LockedFile(identityFile).withLock(() -> operation.apply(identityFile));
    }

    /** Refuses links, special files, and oversized files through {@link AtomicFiles#readUtf8}. */
    private static WorldIdentity read(Path identityFile) throws IOException {
        JsonObject object = FIELDS.parseObject(
                AtomicFiles.readUtf8(identityFile, MAXIMUM_IDENTITY_BYTES),
                "World identity");
        int schemaVersion = FIELDS.requiredInt(object, "schemaVersion");
        if (schemaVersion != WorldIdentity.CURRENT_SCHEMA_VERSION) {
            throw new IOException("Unsupported world identity schema: " + schemaVersion);
        }
        try {
            WorldId worldId = WorldId.parse(FIELDS.requiredString(object, "worldId"));
            Optional<BackupId> sourceBackupId = FIELDS.optionalString(object, "sourceBackupId")
                    .map(BackupId::parse);
            return new WorldIdentity(worldId, sourceBackupId);
        } catch (IllegalArgumentException exception) {
            throw new IOException("World identity is malformed or invalid", exception);
        }
    }

    /** {@link AtomicFiles#writeUtf8} replaces the file by renaming, so it never writes through a link. */
    private static void write(Path identityFile, WorldIdentity identity) throws IOException {
        JsonObject object = new JsonObject();
        object.addProperty("schemaVersion", WorldIdentity.CURRENT_SCHEMA_VERSION);
        object.addProperty("worldId", identity.worldId().toString());
        identity.sourceBackupId().ifPresent(id -> object.addProperty("sourceBackupId", id.toString()));
        AtomicFiles.writeUtf8(
                identityFile,
                GSON.toJson(object) + "\n",
                MAXIMUM_IDENTITY_BYTES);
    }

    @FunctionalInterface
    private interface IdentityOperation<T> {
        T apply(Path identityFile) throws IOException;
    }
}
