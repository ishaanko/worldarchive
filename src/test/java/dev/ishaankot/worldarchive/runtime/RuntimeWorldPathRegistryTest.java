package dev.ishaankot.worldarchive.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.ishaankot.worldarchive.config.WorldArchiveConfig;
import dev.ishaankot.worldarchive.config.ZipDestinationConfig;
import dev.ishaankot.worldarchive.model.WorldId;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class RuntimeWorldPathRegistryTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void rejectsEitherIdentityAtTwoPathsOrTwoIdentitiesAtOnePath() {
        RuntimeWorldPathRegistry registry = new RuntimeWorldPathRegistry();
        WorldId first = WorldId.create();
        WorldId second = WorldId.create();
        Path firstPath = temporaryDirectory.resolve("first-world");
        Path secondPath = temporaryDirectory.resolve("second-world");

        assertTrue(registry.register(first, firstPath));
        assertFalse(registry.register(first, secondPath));
        assertFalse(registry.register(second, firstPath));
        assertTrue(registry.register(second, secondPath));
        assertEquals(
                java.util.Set.of(
                        firstPath.toAbsolutePath().normalize(),
                        secondPath.toAbsolutePath().normalize()),
                java.util.Set.copyOf(registry.snapshotPaths()));
    }

    @Test
    void configuredPathOwnerIsAuthoritative() {
        RuntimeWorldPathRegistry registry = new RuntimeWorldPathRegistry();
        WorldId configured = WorldId.create();
        WorldId conflicting = WorldId.create();
        Path path = temporaryDirectory.resolve("world");

        registry.configure(configured, path);

        assertTrue(registry.matches(configured, path));
        assertFalse(registry.matches(conflicting, path));
        assertFalse(registry.register(conflicting, path));
    }

    @Test
    void runtimePathSnapshotRejectsDestinationInsideNewWorld() {
        RuntimeWorldPathRegistry registry = new RuntimeWorldPathRegistry();
        Path newWorld = temporaryDirectory.resolve("new-world");
        assertTrue(registry.register(WorldId.create(), newWorld));
        WorldArchiveConfig defaults = WorldArchiveConfig.defaults();
        WorldArchiveConfig unsafe = new WorldArchiveConfig(
                WorldArchiveConfig.CURRENT_SCHEMA_VERSION,
                defaults.triggers(),
                defaults.git(),
                new ZipDestinationConfig(false, Optional.of(newWorld.resolve("archives"))),
                defaults.worlds());

        assertThrows(
                IOException.class,
                () -> unsafe.validateDestinations(registry.snapshotPaths()));
    }
}
