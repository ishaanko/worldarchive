package dev.ishaankot.worldarchive.core;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import dev.ishaankot.worldarchive.model.WorldId;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.ReentrantLock;

/** Atomic, process-safe JSON persistence for unchanged-world detection. */
public final class FileWorldInventoryStore implements WorldInventoryStore {
    public static final int CURRENT_SCHEMA_VERSION = 1;

    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .disableHtmlEscaping()
            .create();

    private static final ConcurrentMap<Path, ReentrantLock> JVM_LOCKS = new ConcurrentHashMap<>();

    private final Path directory;

    public FileWorldInventoryStore(Path directory) {
        this.directory = Objects.requireNonNull(directory, "directory")
                .toAbsolutePath()
                .normalize();
    }

    public Path directory() {
        return directory;
    }

    @Override
    public Optional<WorldInventory> load(WorldId worldId) throws IOException {
        Objects.requireNonNull(worldId, "worldId");
        return withLock(worldId, () -> {
            Path file = file(worldId);
            if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
                return Optional.empty();
            }
            requireRegularFile(file, "World inventory is not a regular file");
            try {
                JsonElement parsed = JsonParser.parseString(Files.readString(file));
                if (!parsed.isJsonObject()) {
                    throw new IOException("World inventory root must be a JSON object");
                }
                return Optional.of(decode(worldId, parsed.getAsJsonObject()));
            } catch (JsonParseException | IllegalArgumentException exception) {
                throw new IOException("World inventory is malformed or invalid", exception);
            }
        });
    }

    @Override
    public void save(WorldId worldId, WorldInventory inventory) throws IOException {
        Objects.requireNonNull(worldId, "worldId");
        Objects.requireNonNull(inventory, "inventory");
        withLock(worldId, () -> {
            AtomicFiles.writeUtf8(file(worldId), GSON.toJson(encode(worldId, inventory))
                    + System.lineSeparator());
            return null;
        });
    }

    private <T> T withLock(WorldId worldId, IoSupplier<T> operation) throws IOException {
        Path lockPath = lockFile(worldId);
        ReentrantLock jvmLock = JVM_LOCKS.computeIfAbsent(lockPath, ignored -> new ReentrantLock());
        jvmLock.lock();
        try {
            createSafeDirectory();
            if (Files.exists(lockPath, LinkOption.NOFOLLOW_LINKS)) {
                requireRegularFile(lockPath, "World inventory lock is not a regular file");
            }
            try (FileChannel channel = FileChannel.open(
                            lockPath,
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

    private void createSafeDirectory() throws IOException {
        Files.createDirectories(directory);
        if (Files.isSymbolicLink(directory)
                || !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("World inventory directory is unsafe");
        }
    }

    private Path file(WorldId worldId) {
        return directory.resolve(worldId + ".json");
    }

    private Path lockFile(WorldId worldId) {
        return directory.resolve(worldId + ".lock");
    }

    private static JsonObject encode(WorldId worldId, WorldInventory inventory) {
        JsonObject root = new JsonObject();
        root.addProperty("schemaVersion", CURRENT_SCHEMA_VERSION);
        root.addProperty("worldId", worldId.toString());
        JsonArray files = new JsonArray();
        for (WorldInventory.Entry entry : inventory.files()) {
            JsonObject encoded = new JsonObject();
            encoded.addProperty("path", entry.path());
            encoded.addProperty("size", entry.size());
            encoded.addProperty("sha256", entry.sha256());
            files.add(encoded);
        }
        root.add("files", files);
        return root;
    }

    private static WorldInventory decode(WorldId expectedWorldId, JsonObject root) throws IOException {
        int schemaVersion = requiredInteger(root, "schemaVersion");
        if (schemaVersion != CURRENT_SCHEMA_VERSION) {
            throw new IOException("Unsupported world inventory schema: " + schemaVersion);
        }
        WorldId storedWorldId = WorldId.parse(requiredString(root, "worldId"));
        if (!storedWorldId.equals(expectedWorldId)) {
            throw new IOException("World inventory identity does not match its file name");
        }
        JsonArray encodedFiles = requiredArray(root, "files");
        if (encodedFiles.size() > WorldInventory.MAXIMUM_FILES) {
            throw new IOException("World inventory contains too many files");
        }
        List<WorldInventory.Entry> files = new ArrayList<>(encodedFiles.size());
        for (JsonElement encoded : encodedFiles) {
            if (!encoded.isJsonObject()) {
                throw new IOException("World inventory entry must be an object");
            }
            JsonObject object = encoded.getAsJsonObject();
            files.add(new WorldInventory.Entry(
                    requiredString(object, "path"),
                    requiredLong(object, "size"),
                    requiredString(object, "sha256")));
        }
        return WorldInventory.create(files);
    }

    private static void requireRegularFile(Path path, String message) throws IOException {
        if (Files.isSymbolicLink(path) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException(message);
        }
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

    private static int requiredInteger(JsonObject object, String name) throws IOException {
        long value = requiredLong(object, name);
        if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
            throw new IOException("Required integer is out of range: " + name);
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

    @FunctionalInterface
    private interface IoSupplier<T> {
        T get() throws IOException;
    }
}
