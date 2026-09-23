package dev.ishaanko.worldarchive.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

final class PathSafetyTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void rejectsExistingAndMissingDestinationsInsideWorld() throws IOException {
        Path world = Files.createDirectory(temporaryDirectory.resolve("world"));
        Path existing = Files.createDirectory(world.resolve("backups"));
        List<Path> worlds = PathSafety.canonicalizeAll(List.of(world));

        assertThrows(IOException.class, () -> PathSafety.requireOutsideWorlds(existing, worlds));
        assertThrows(IOException.class, () -> PathSafety.requireOutsideWorlds(world.resolve("missing/nested"), worlds));
    }

    @Test
    void rejectsADestinationThatReachesAWorldThroughALink() throws IOException {
        Path world = Files.createDirectory(temporaryDirectory.resolve("world"));
        Path link = temporaryDirectory.resolve("world-link");
        try {
            Files.createSymbolicLink(link, world);
        } catch (IOException | UnsupportedOperationException exception) {
            Assumptions.abort("Symbolic links are not available: " + exception.getMessage());
        }

        assertThrows(IOException.class, () -> PathSafety.requireOutsideWorlds(
                link.resolve("backups"), PathSafety.canonicalizeAll(List.of(world))));
    }

    @Test
    void allowsCanonicalSiblingDestination() throws IOException {
        Path world = Files.createDirectory(temporaryDirectory.resolve("world"));
        Path sibling = temporaryDirectory.resolve("backups/new");

        assertEquals(
                PathSafety.canonicalize(sibling),
                PathSafety.requireOutsideWorlds(sibling, PathSafety.canonicalizeAll(List.of(world))));
    }

    @Test
    void validatesEveryConfiguredDestination() throws IOException {
        Path world = Files.createDirectory(temporaryDirectory.resolve("world"));
        Path sibling = Files.createDirectory(temporaryDirectory.resolve("zips"));
        WorldArchiveConfig unsafe = WorldArchiveConfig.defaults()
                .withGit(GitDestinationConfig.defaults().withRepository(Optional.of(world.resolve("git"))))
                .withZip(ZipDestinationConfig.defaults().withDestination(Optional.of(sibling)));

        assertThrows(IOException.class, () -> unsafe.validateDestinations(List.of(world)));
    }

    /** A folder on a drive that is not connected must not stop the settings from loading. */
    @Test
    @EnabledOnOs(OS.WINDOWS)
    void aFolderOnAMissingDriveStaysAsWritten() throws IOException {
        Optional<Character> missingDrive = "ZYXWVUTSRQPONMLKJIHGFED".chars()
                .mapToObj(letter -> (char) letter)
                .filter(letter -> !Files.exists(Path.of(letter + ":\\")))
                .findFirst();
        Assumptions.assumeTrue(missingDrive.isPresent(), "every drive letter is in use");
        Path offline = Path.of(missingDrive.get() + ":\\WorldBackups");

        assertEquals(offline, PathSafety.canonicalize(offline));
    }
}
