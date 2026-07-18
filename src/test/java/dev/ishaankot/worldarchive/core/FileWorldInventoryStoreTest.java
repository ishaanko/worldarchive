package dev.ishaankot.worldarchive.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.ishaankot.worldarchive.model.WorldId;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class FileWorldInventoryStoreTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void atomicallyRoundTripsInventoryOutsideWorld() throws Exception {
        Path world = Files.createDirectory(temporaryDirectory.resolve("world"));
        Path state = temporaryDirectory.resolve("state");
        WorldId worldId = WorldId.create();
        WorldInventory inventory = WorldInventory.create(List.of(
                entry("level.dat", "contents")));

        FileWorldInventoryStore store = new FileWorldInventoryStore(state);
        store.save(worldId, inventory);

        assertEquals(inventory, store.load(worldId).orElseThrow());
        try (var entries = Files.list(world)) {
            assertEquals(List.of(), entries.toList());
        }
        assertEquals(state.toAbsolutePath().normalize(), store.directory());
    }

    @Test
    void rejectsIdentityMismatchAndMalformedState() throws Exception {
        Path state = Files.createDirectory(temporaryDirectory.resolve("state"));
        WorldId requested = WorldId.create();
        WorldId other = WorldId.create();
        Files.writeString(
                state.resolve(requested + ".json"),
                """
                {"schemaVersion":1,"worldId":"%s","files":[]}
                """.formatted(other),
                StandardCharsets.UTF_8);

        FileWorldInventoryStore store = new FileWorldInventoryStore(state);
        assertThrows(IOException.class, () -> store.load(requested));

        Files.writeString(state.resolve(requested + ".json"), "not-json", StandardCharsets.UTF_8);
        assertThrows(IOException.class, () -> store.load(requested));
    }

    private static WorldInventory.Entry entry(String path, String contents) throws Exception {
        byte[] bytes = contents.getBytes(StandardCharsets.UTF_8);
        return new WorldInventory.Entry(
                path,
                bytes.length,
                HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));
    }
}
