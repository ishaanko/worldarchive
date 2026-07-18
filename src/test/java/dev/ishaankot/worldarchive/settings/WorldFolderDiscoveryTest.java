package dev.ishaankot.worldarchive.settings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.ishaankot.worldarchive.config.WorldIdentityStore;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorldFolderDiscoveryTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void ignoresAndDoesNotModifyNonWorldFolders() throws IOException {
        Path saves = Files.createDirectory(temporaryDirectory.resolve("saves"));
        Path world = Files.createDirectory(saves.resolve("actual-world"));
        Files.writeString(world.resolve("level.dat"), "level data");
        Path unrelated = Files.createDirectory(saves.resolve("screenshots"));
        Files.writeString(unrelated.resolve("notes.txt"), "not a world");
        Path fake = Files.createDirectory(saves.resolve("fake-world"));
        Files.createDirectory(fake.resolve("level.dat"));

        List<Path> discovered = WorldFolderDiscovery.discover(saves);
        WorldIdentityStore identities = new WorldIdentityStore();
        discovered.forEach(path -> {
            try {
                identities.loadOrCreate(path);
            } catch (IOException exception) {
                throw new AssertionError(exception);
            }
        });

        assertEquals(List.of(world.toRealPath()), discovered);
        assertTrue(Files.isRegularFile(world.resolve(".worldarchive/world.json")));
        assertFalse(Files.exists(unrelated.resolve(".worldarchive")));
        assertFalse(Files.exists(fake.resolve(".worldarchive")));
    }

    @Test
    void ignoresLinkedWorldDirectoriesWithoutTouchingTheirTargets() throws IOException {
        Path saves = Files.createDirectory(temporaryDirectory.resolve("linked-saves"));
        Path outsideWorld = Files.createDirectory(temporaryDirectory.resolve("outside-world"));
        Files.writeString(outsideWorld.resolve("level.dat"), "level data");
        try {
            Files.createSymbolicLink(saves.resolve("linked-world"), outsideWorld);
        } catch (IOException | UnsupportedOperationException | SecurityException exception) {
            return;
        }

        List<Path> discovered = WorldFolderDiscovery.discover(saves);

        assertTrue(discovered.isEmpty());
        assertFalse(Files.exists(outsideWorld.resolve(".worldarchive")));
    }
}
