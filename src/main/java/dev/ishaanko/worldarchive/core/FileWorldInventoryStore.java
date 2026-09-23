package dev.ishaanko.worldarchive.core;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.ishaanko.worldarchive.model.WorldId;
import dev.ishaanko.worldarchive.support.AtomicFiles;
import dev.ishaanko.worldarchive.support.JsonFields;
import java.io.IOException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * One JSON file per world holding the inventory of its last recorded capture. The next capture
 * compares against it to count changed files. The coordinator writes one world's file at a time
 * and replaces it atomically, so readers always see a whole file and no lock is needed.
 */
public final class FileWorldInventoryStore {
    private static final int SCHEMA_VERSION = 1;

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    /** A 500,000-file inventory encodes to well over the generic metadata ceiling. */
    private static final int MAXIMUM_INVENTORY_BYTES = 256 * 1_024 * 1_024;

    private static final JsonFields<IOException> FIELDS = new JsonFields<>(IOException::new);

    private final Path directory;

    public FileWorldInventoryStore(Path directory) {
        this.directory = Objects.requireNonNull(directory, "directory")
                .toAbsolutePath()
                .normalize();
    }

    /** The world's last inventory, or empty when none was saved yet. A damaged file is an IOException. */
    public Optional<WorldInventory> load(WorldId worldId) throws IOException {
        String json;
        try {
            json = AtomicFiles.readUtf8(file(worldId), MAXIMUM_INVENTORY_BYTES);
        } catch (NoSuchFileException exception) {
            return Optional.empty();
        }
        try {
            return Optional.of(decode(worldId, FIELDS.parseObject(json, "World inventory")));
        } catch (IllegalArgumentException exception) {
            throw new IOException("World inventory is malformed or invalid", exception);
        }
    }

    public void save(WorldId worldId, WorldInventory inventory) throws IOException {
        Objects.requireNonNull(inventory, "inventory");
        String json = GSON.toJson(encode(worldId, inventory)) + "\n";
        AtomicFiles.writeUtf8(file(worldId), json, MAXIMUM_INVENTORY_BYTES);
    }

    private Path file(WorldId worldId) {
        return directory.resolve(Objects.requireNonNull(worldId, "worldId") + ".json");
    }

    private static JsonObject encode(WorldId worldId, WorldInventory inventory) {
        JsonObject root = new JsonObject();
        root.addProperty("schemaVersion", SCHEMA_VERSION);
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
        int schemaVersion = FIELDS.requiredInt(root, "schemaVersion");
        if (schemaVersion != SCHEMA_VERSION) {
            throw new IOException("Unsupported world inventory schema: " + schemaVersion);
        }
        if (!WorldId.parse(FIELDS.requiredString(root, "worldId")).equals(expectedWorldId)) {
            throw new IOException("World inventory identity does not match its file name");
        }
        List<JsonObject> encodedFiles = FIELDS.requiredObjects(root, "files");
        if (encodedFiles.size() > WorldInventory.MAXIMUM_FILES) {
            throw new IOException("World inventory contains too many files");
        }
        List<WorldInventory.Entry> files = new ArrayList<>(encodedFiles.size());
        for (JsonObject encoded : encodedFiles) {
            files.add(new WorldInventory.Entry(
                    FIELDS.requiredString(encoded, "path"),
                    FIELDS.requiredLong(encoded, "size"),
                    FIELDS.requiredString(encoded, "sha256")));
        }
        return WorldInventory.create(files);
    }
}
