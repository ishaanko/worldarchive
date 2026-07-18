package dev.ishaankot.worldarchive.runtime;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.ishaankot.worldarchive.model.WorldId;
import java.nio.file.Path;
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
}
