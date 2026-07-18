package dev.ishaankot.worldarchive.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import dev.ishaankot.worldarchive.core.AtomicFiles;
import dev.ishaankot.worldarchive.model.BackupId;
import dev.ishaankot.worldarchive.model.WorldId;
import dev.ishaankot.worldarchive.model.WorldIdentity;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.ReentrantLock;

/** Stable, versioned per-world identity with atomic creation and restored-copy provenance. */
public final class WorldIdentityStore {
    private static final String METADATA_DIRECTORY = ".worldarchive";

    private static final String IDENTITY_FILE = "world.json";

    private static final String LOCK_FILE = "world.json.lock";

    private static final int MAXIMUM_IDENTITY_BYTES = 4_096;

    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .disableHtmlEscaping()
            .create();

    private static final ConcurrentMap<Path, ReentrantLock> JVM_LOCKS = new ConcurrentHashMap<>();

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
        Path lockFile = metadata.resolve(LOCK_FILE);
        rejectSymlink(identityFile, "World identity file");
        rejectSymlink(lockFile, "World identity lock file");
        ReentrantLock jvmLock = JVM_LOCKS.computeIfAbsent(identityFile, ignored -> new ReentrantLock());
        jvmLock.lock();
        try {
            rejectSymlink(identityFile, "World identity file");
            rejectSymlink(lockFile, "World identity lock file");
            LockIdentity beforeOpen = ensureLockFile(lockFile);
            try (FileChannel channel = FileChannel.open(
                            lockFile,
                            StandardOpenOption.WRITE,
                            LinkOption.NOFOLLOW_LINKS)) {
                LockIdentity afterOpen = readLockIdentity(lockFile);
                requireSameLock(beforeOpen, afterOpen);
                try (FileLock ignored = channel.lock()) {
                    requireSameLock(afterOpen, readLockIdentity(lockFile));
                    rejectSymlink(identityFile, "World identity file");
                    return operation.apply(identityFile);
                }
            }
        } finally {
            jvmLock.unlock();
        }
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
            JsonElement parsed = JsonParser.parseString(Files.readString(identityFile, StandardCharsets.UTF_8));
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
        AtomicFiles.writeUtf8(identityFile, GSON.toJson(object) + System.lineSeparator());
    }

    private static void rejectSymlink(Path path, String description) throws IOException {
        if (Files.isSymbolicLink(path)) {
            throw new IOException(description + " must not be a symbolic link");
        }
    }

    private static LockIdentity ensureLockFile(Path lockFile) throws IOException {
        try {
            Files.createFile(lockFile);
        } catch (FileAlreadyExistsException exception) {
            // The persistent lock already exists; its identity is verified below.
        }
        return readLockIdentity(lockFile);
    }

    private static LockIdentity readLockIdentity(Path lockFile) throws IOException {
        BasicFileAttributes attributes = Files.readAttributes(
                lockFile,
                BasicFileAttributes.class,
                LinkOption.NOFOLLOW_LINKS);
        if (attributes.isSymbolicLink() || !attributes.isRegularFile()) {
            throw new IOException("World identity lock file must be a regular non-symbolic file");
        }
        return new LockIdentity(attributes.fileKey(), attributes.creationTime());
    }

    private static void requireSameLock(LockIdentity expected, LockIdentity actual) throws IOException {
        boolean same = expected.fileKey() != null || actual.fileKey() != null
                ? expected.fileKey() != null && expected.fileKey().equals(actual.fileKey())
                : expected.creationTime().equals(actual.creationTime());
        if (!same) {
            throw new IOException("World identity lock file changed while it was being acquired");
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

    private record LockIdentity(Object fileKey, FileTime creationTime) {
    }
}
