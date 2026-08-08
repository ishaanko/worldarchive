package dev.ishaankot.worldarchive.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.ishaankot.worldarchive.model.DestinationHealth;
import dev.ishaankot.worldarchive.model.DestinationType;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class PathSafetyTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void rejectsExistingAndMissingDestinationsInsideWorld() throws IOException {
        Path world = Files.createDirectory(temporaryDirectory.resolve("world"));
        Path existing = Files.createDirectory(world.resolve("backups"));

        assertThrows(IOException.class, () -> PathSafety.requireOutsideWorlds(existing, List.of(world)));
        assertThrows(IOException.class, () -> PathSafety.requireOutsideWorlds(
                world.resolve("missing/nested"),
                List.of(world)));
    }

    @Test
    void allowsCanonicalSiblingDestination() throws IOException {
        Path world = Files.createDirectory(temporaryDirectory.resolve("world"));
        Path sibling = temporaryDirectory.resolve("backups/new");

        assertEquals(PathSafety.canonicalize(sibling), PathSafety.requireOutsideWorlds(sibling, List.of(world)));
    }

    @Test
    void validatesEveryConfiguredDestination() throws IOException {
        Path world = Files.createDirectory(temporaryDirectory.resolve("world"));
        Path sibling = Files.createDirectory(temporaryDirectory.resolve("zips"));
        WorldArchiveConfig unsafe = new WorldArchiveConfig(
                WorldArchiveConfig.CURRENT_SCHEMA_VERSION,
                TriggerConfig.defaults(),
                new GitDestinationConfig(true, Optional.of(world.resolve("git")), "origin", Optional.empty()),
                new ZipDestinationConfig(true, Optional.of(sibling)));

        assertThrows(IOException.class, () -> unsafe.validateDestinations(List.of(world)));
    }

    @Test
    void validatesHiddenLegacyGitRepositoryAgainstWorlds() throws IOException {
        Path world = Files.createDirectory(temporaryDirectory.resolve("legacy-world"));
        WorldArchiveConfig unsafe = new WorldArchiveConfig(
                WorldArchiveConfig.CURRENT_SCHEMA_VERSION,
                TriggerConfig.defaults(),
                new GitDestinationConfig(
                        true,
                        Optional.of(temporaryDirectory.resolve("git-root")),
                        "origin",
                        Optional.empty(),
                        DestinationTriggerConfig.defaults(),
                        GitDestinationConfig.DEFAULT_LFS_PATTERNS,
                        DestinationHealth.notChecked(DestinationType.GIT),
                        Optional.of(world.resolve("legacy.git")),
                        Optional.empty()),
                ZipDestinationConfig.defaults());

        assertThrows(IOException.class, () -> unsafe.validateDestinations(List.of(world)));
    }
}
