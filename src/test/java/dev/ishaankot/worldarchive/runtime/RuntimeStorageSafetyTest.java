package dev.ishaankot.worldarchive.runtime;

import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.ishaankot.worldarchive.config.WorldArchiveConfig;
import dev.ishaankot.worldarchive.config.ZipDestinationConfig;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class RuntimeStorageSafetyTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void overlapLocksStorageUntilConfigurationIsSafeAgain() {
        Path world = temporaryDirectory.resolve("new-world");
        WorldArchiveConfig defaults = WorldArchiveConfig.defaults();
        WorldArchiveConfig unsafe = new WorldArchiveConfig(
                WorldArchiveConfig.CURRENT_SCHEMA_VERSION,
                defaults.triggers(),
                defaults.git(),
                new ZipDestinationConfig(false, Optional.of(world.resolve("archives"))),
                defaults.worlds());
        RuntimeStorageSafety safety = new RuntimeStorageSafety();

        safety.refresh(unsafe, List.of(world));
        assertTrue(safety.warning().isPresent());

        safety.refresh(defaults, List.of(world));
        assertTrue(safety.warning().isEmpty());
    }
}
