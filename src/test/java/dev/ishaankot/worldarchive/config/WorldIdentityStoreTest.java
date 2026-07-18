package dev.ishaankot.worldarchive.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.ishaankot.worldarchive.model.BackupId;
import dev.ishaankot.worldarchive.model.WorldId;
import dev.ishaankot.worldarchive.model.WorldIdentity;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.io.TempDir;

final class WorldIdentityStoreTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void identityIsStableAcrossCallsAndStoreInstances() throws IOException {
        Path world = Files.createDirectory(temporaryDirectory.resolve("world"));
        WorldId first = new WorldIdentityStore().loadOrCreate(world);
        WorldId second = new WorldIdentityStore().loadOrCreate(world);

        assertEquals(first, second);
        String stored = Files.readString(
                world.resolve(".worldarchive/world.json"),
                StandardCharsets.UTF_8);
        assertTrue(stored.contains("\"schemaVersion\": 1"));
        assertTrue(stored.contains(first.toString()));
        assertFalse(Files.exists(world.resolve(".worldarchive/world-id")));
    }

    @Test
    void concurrentCreationReturnsOneIdentity() throws Exception {
        Path world = Files.createDirectory(temporaryDirectory.resolve("world"));
        ExecutorService executor = Executors.newFixedThreadPool(8);
        try {
            List<Future<WorldId>> futures = new ArrayList<>();
            for (int index = 0; index < 32; index++) {
                futures.add(executor.submit(() -> new WorldIdentityStore().loadOrCreate(world)));
            }
            WorldId expected = get(futures.getFirst());
            for (Future<WorldId> future : futures) {
                assertEquals(expected, get(future));
            }
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void malformedIdentityFailsWithoutReplacement() throws IOException {
        Path world = Files.createDirectory(temporaryDirectory.resolve("world"));
        Path metadata = Files.createDirectory(world.resolve(".worldarchive"));
        Path marker = metadata.resolve("world.json");
        Files.writeString(marker, "not-a-uuid", StandardCharsets.UTF_8);

        assertThrows(IOException.class, () -> new WorldIdentityStore().loadOrCreate(world));
        assertEquals("not-a-uuid", Files.readString(marker, StandardCharsets.UTF_8));
    }

    @Test
    void restoredCopyAlwaysReceivesFreshIdentityAndProvenance() throws IOException {
        Path source = Files.createDirectory(temporaryDirectory.resolve("source"));
        Path restored = Files.createDirectory(temporaryDirectory.resolve("restored"));
        WorldIdentityStore store = new WorldIdentityStore();
        WorldIdentity sourceIdentity = store.loadOrCreateIdentity(source);
        Files.createDirectories(restored.resolve(".worldarchive"));
        Files.copy(
                source.resolve(".worldarchive/world.json"),
                restored.resolve(".worldarchive/world.json"));
        BackupId sourceBackupId = BackupId.create();

        WorldIdentity restoredIdentity = store.createFreshRestoredCopyIdentity(restored, sourceBackupId);

        assertNotEquals(sourceIdentity.worldId(), restoredIdentity.worldId());
        assertEquals(sourceBackupId, restoredIdentity.sourceBackupId().orElseThrow());
        assertEquals(restoredIdentity, store.loadOrCreateIdentity(restored));
    }

    @Test
    void futureIdentitySchemaFailsWithoutReplacement() throws IOException {
        Path world = Files.createDirectory(temporaryDirectory.resolve("future-world"));
        Path metadata = Files.createDirectory(world.resolve(".worldarchive"));
        Path identity = metadata.resolve("world.json");
        String future = "{\"schemaVersion\":999,\"worldId\":\"" + WorldId.create() + "\"}";
        Files.writeString(identity, future, StandardCharsets.UTF_8);

        assertThrows(IOException.class, () -> new WorldIdentityStore().loadOrCreateIdentity(world));
        assertEquals(future, Files.readString(identity, StandardCharsets.UTF_8));
    }

    @Test
    void rejectsSymbolicIdentityAndLockFiles() throws IOException {
        Path world = Files.createDirectory(temporaryDirectory.resolve("linked-world"));
        Path metadata = Files.createDirectory(world.resolve(".worldarchive"));
        Path target = Files.createFile(temporaryDirectory.resolve("identity-target"));
        Path identity = metadata.resolve("world.json");
        try {
            Files.createSymbolicLink(identity, target);
        } catch (UnsupportedOperationException | IOException exception) {
            Assumptions.assumeTrue(false, "Symbolic links unavailable: " + exception.getMessage());
        }
        assertThrows(IOException.class, () -> new WorldIdentityStore().loadOrCreateIdentity(world));
        Files.delete(identity);
        Files.createSymbolicLink(metadata.resolve("world.json.lock"), target);
        assertThrows(IOException.class, () -> new WorldIdentityStore().loadOrCreateIdentity(world));
    }

    private static WorldId get(Future<WorldId> future) throws InterruptedException, ExecutionException {
        return future.get();
    }
}
