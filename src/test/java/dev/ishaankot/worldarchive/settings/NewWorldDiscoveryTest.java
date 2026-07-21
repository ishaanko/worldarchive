package dev.ishaankot.worldarchive.settings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class NewWorldDiscoveryTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void newWorldBecomesDiscoverableAfterLevelDataIsPublished() throws IOException {
        Path saves = Files.createDirectory(temporaryDirectory.resolve("saves"));
        Path world = Files.createDirectory(saves.resolve("new-world"));

        assertTrue(WorldFolderDiscovery.discover(saves).isEmpty());

        Files.writeString(world.resolve("level.dat"), "level data");

        assertEquals(List.of(world.toRealPath()), WorldFolderDiscovery.discover(saves));
    }
}
