package dev.ishaanko.worldarchive.settings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorldFolderDiscoveryTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void findsOnlyFoldersWithLevelDataAndChangesNothing() throws IOException {
        Path saves = Files.createDirectory(temporaryDirectory.resolve("saves"));
        Path world = world(saves, "actual-world");
        Path unrelated = Files.createDirectory(saves.resolve("screenshots"));
        Files.writeString(unrelated.resolve("notes.txt"), "not a world");
        Files.createDirectories(saves.resolve("fake-world/level.dat"));
        Path created = Files.createDirectory(saves.resolve("being-created"));

        assertEquals(List.of(world.toRealPath()), WorldFolderDiscovery.discover(saves));
        assertFalse(Files.exists(world.resolve(".worldarchive")));

        Files.writeString(created.resolve("level.dat"), "level data");
        assertEquals(List.of(world.toRealPath(), created.toRealPath()), WorldFolderDiscovery.discover(saves));
    }

    @Test
    void findsLinkedSavesAndLinkedWorldsByTheirRealPathOnce() throws IOException {
        Path sharedSaves = Files.createDirectory(temporaryDirectory.resolve("shared-saves"));
        Path shared = world(sharedSaves, "shared-world");
        Path otherDrive = world(Files.createDirectory(temporaryDirectory.resolve("other-drive")), "moved-world");
        Path instance = Files.createDirectory(temporaryDirectory.resolve("instance"));
        Path saves = link(instance.resolve("saves"), sharedSaves);
        link(sharedSaves.resolve("moved-world"), otherDrive);
        link(sharedSaves.resolve("shared-world-alias"), shared);

        assertEquals(
                List.of(otherDrive.toRealPath(), shared.toRealPath()).stream().sorted().toList(),
                WorldFolderDiscovery.discover(saves));
    }

    @Test
    void temporaryReplayWorldIsNotAChildOfTheMinecraftSavesFolder() {
        Path minecraft = temporaryDirectory.resolve("minecraft");
        Path saves = minecraft.resolve("saves");
        Path replay = minecraft.resolve("flashback/temp/server/id/saves/replay");

        assertTrue(WorldFolderDiscovery.isDirectChild(saves, saves.resolve("world")));
        assertFalse(WorldFolderDiscovery.isDirectChild(saves, replay));
    }

    private static Path world(Path saves, String name) throws IOException {
        Path world = Files.createDirectory(saves.resolve(name));
        Files.writeString(world.resolve("level.dat"), "level data");
        return world;
    }

    private static Path link(Path link, Path target) {
        try {
            return Files.createSymbolicLink(link, target);
        } catch (IOException | UnsupportedOperationException exception) {
            return Assumptions.abort("Symbolic links are not available: " + exception.getMessage());
        }
    }
}
