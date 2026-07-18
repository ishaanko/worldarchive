package dev.ishaankot.worldarchive.settings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.ishaankot.worldarchive.config.WorldArchiveConfig;
import dev.ishaankot.worldarchive.config.WorldArchiveConfigStore;
import dev.ishaankot.worldarchive.config.ZipDestinationConfig;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorldArchiveSettingsRepositoryTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void savesAtomicallyThroughSharedKnownWorldValidation() throws IOException {
        Path world = Files.createDirectory(temporaryDirectory.resolve("world"));
        SettingsDefaults settingsDefaults = new SettingsDefaults(temporaryDirectory.resolve("storage"));
        WorldArchiveSettingsRepository repository = new WorldArchiveSettingsRepository(
                new WorldArchiveConfigStore(temporaryDirectory.resolve("config/worldarchive.json")),
                () -> List.of(world),
                settingsDefaults);
        WorldArchiveConfig defaults = WorldArchiveConfig.defaults();
        WorldArchiveConfig recursive = new WorldArchiveConfig(
                WorldArchiveConfig.CURRENT_SCHEMA_VERSION,
                defaults.triggers(),
                defaults.git(),
                new ZipDestinationConfig(true, Optional.of(world.resolve("backups"))),
                defaults.worlds());

        assertThrows(IOException.class, () -> repository.save(recursive));
        assertEquals(settingsDefaults.resolve(WorldArchiveConfig.defaults()), repository.current());
    }

    @Test
    void roundTripsAValidPausedConfigurationWithOperationalDefaults() throws IOException {
        WorldArchiveConfig defaults = WorldArchiveConfig.defaults();
        WorldArchiveConfig paused = new WorldArchiveConfig(
                WorldArchiveConfig.CURRENT_SCHEMA_VERSION,
                defaults.triggers(),
                new dev.ishaankot.worldarchive.config.GitDestinationConfig(
                        false,
                        Optional.empty(),
                        "origin",
                        Optional.empty()),
                new ZipDestinationConfig(false, Optional.empty()),
                List.of());
        Path configFile = temporaryDirectory.resolve("config/worldarchive.json");
        WorldArchiveSettingsRepository repository = new WorldArchiveSettingsRepository(
                new WorldArchiveConfigStore(configFile),
                List::of);

        repository.save(paused);

        WorldArchiveSettingsRepository reloaded = new WorldArchiveSettingsRepository(
                new WorldArchiveConfigStore(configFile),
                List::of);
        WorldArchiveConfig expected = SettingsDefaults.fromConfigFile(configFile).resolve(paused);
        assertEquals(expected, reloaded.load());
    }

    @Test
    void resolvesAndPersistsProductDestinationPathsOnFirstLoad() throws IOException {
        Path gameDirectory = Files.createDirectory(temporaryDirectory.resolve("minecraft"));
        Path configFile = gameDirectory.resolve("config/worldarchive.json");
        WorldArchiveSettingsRepository repository = new WorldArchiveSettingsRepository(
                new WorldArchiveConfigStore(configFile),
                List::of);

        WorldArchiveConfig loaded = repository.load();

        assertEquals(
                gameDirectory.resolve("worldarchive/worldarchive.git"),
                loaded.git().repository().orElseThrow());
        assertEquals(
                gameDirectory.resolve("worldarchive/archives"),
                loaded.zip().destination().orElseThrow());
        assertTrue(Files.isRegularFile(configFile));
        assertEquals(loaded, new WorldArchiveConfigStore(configFile).load(List.of()));
    }

    @Test
    void disabledConfiguredDestinationIsStillCheckedAgainstWorlds() throws IOException {
        Path world = Files.createDirectory(temporaryDirectory.resolve("disabled-world"));
        SettingsDefaults settingsDefaults = new SettingsDefaults(temporaryDirectory.resolve("storage"));
        WorldArchiveSettingsRepository repository = new WorldArchiveSettingsRepository(
                new WorldArchiveConfigStore(temporaryDirectory.resolve("config/disabled.json")),
                () -> List.of(world),
                settingsDefaults);
        WorldArchiveConfig defaults = settingsDefaults.resolve(WorldArchiveConfig.defaults());
        WorldArchiveConfig unsafe = new WorldArchiveConfig(
                WorldArchiveConfig.CURRENT_SCHEMA_VERSION,
                defaults.triggers(),
                defaults.git(),
                new ZipDestinationConfig(false, Optional.of(world.resolve("archives"))),
                defaults.worlds());

        assertThrows(IOException.class, () -> repository.save(unsafe));
    }
}
