package dev.ishaankot.worldarchive.settings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.ishaankot.worldarchive.config.WorldArchiveConfig;
import dev.ishaankot.worldarchive.config.WorldArchiveConfigStore;
import dev.ishaankot.worldarchive.config.WorldConfig;
import dev.ishaankot.worldarchive.config.ZipDestinationConfig;
import dev.ishaankot.worldarchive.model.WorldId;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
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
        WorldArchiveConfig expected = SettingsDefaults.fromConfigFile(configFile)
                .resolve(paused)
                .validateDestinations(List.of());
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
                gameDirectory.toRealPath().resolve("worldarchive/worldarchive.git"),
                loaded.git().repository().orElseThrow());
        assertEquals(
                gameDirectory.toRealPath().resolve("worldarchive/archives"),
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

    @Test
    void newlyKnownRuntimeWorldRejectsUnsafeSaveWithoutChangingStoredConfig() throws IOException {
        AtomicReference<List<Path>> knownWorlds = new AtomicReference<>(List.of());
        Path configFile = temporaryDirectory.resolve("config/runtime-world.json");
        SettingsDefaults defaults = new SettingsDefaults(temporaryDirectory.resolve("storage"));
        WorldArchiveSettingsRepository repository = new WorldArchiveSettingsRepository(
                new WorldArchiveConfigStore(configFile),
                knownWorlds::get,
                defaults);
        WorldArchiveConfig safe = defaults.resolve(WorldArchiveConfig.defaults())
                .validateDestinations(List.of());
        repository.save(safe);
        Path runtimeWorld = Files.createDirectory(temporaryDirectory.resolve("runtime-world"));
        knownWorlds.set(List.of(runtimeWorld));
        WorldArchiveConfig unsafe = new WorldArchiveConfig(
                WorldArchiveConfig.CURRENT_SCHEMA_VERSION,
                safe.triggers(),
                safe.git(),
                new ZipDestinationConfig(false, Optional.of(runtimeWorld.resolve("archives"))),
                safe.worlds());

        assertThrows(IOException.class, () -> repository.save(unsafe));
        assertEquals(safe, repository.current());
        assertEquals(safe, new WorldArchiveConfigStore(configFile).load(knownWorlds.get()));
    }

    @Test
    void runtimeWorldRegistrationIsImmediatelyKnownAndDurablyUpserted() throws IOException {
        Path world = Files.createDirectory(temporaryDirectory.resolve("registered-world"));
        WorldId worldId = WorldId.create();
        List<Path> known = ClientSettingsAccess.mergedWorldPaths(List.of(), List.of(world));
        WorldArchiveConfig updated = ClientSettingsAccess.upsertWorld(
                WorldArchiveConfig.defaults(), worldId, world);
        Path configFile = temporaryDirectory.resolve("config/registered-world.json");
        WorldArchiveSettingsRepository repository = new WorldArchiveSettingsRepository(
                new WorldArchiveConfigStore(configFile),
                () -> known);

        repository.save(updated);

        WorldArchiveConfig durable = new WorldArchiveSettingsRepository(
                new WorldArchiveConfigStore(configFile),
                () -> known).load();
        assertTrue(known.contains(world.toAbsolutePath().normalize()));
        assertTrue(durable.worlds().contains(new WorldConfig(worldId, true, world.toRealPath())));
    }

    @Test
    void saveRefreshFindsUnselectedNewWorldBeforeDestinationValidation() throws IOException {
        Path saves = Files.createDirectory(temporaryDirectory.resolve("saves"));
        Path newWorld = Files.createDirectory(saves.resolve("new-world"));
        Files.writeString(newWorld.resolve("level.dat"), "world data");
        AtomicReference<List<Path>> known = new AtomicReference<>(List.of());
        WorldArchiveConfig defaults = WorldArchiveConfig.defaults();
        WorldArchiveConfig unsafe = new WorldArchiveConfig(
                WorldArchiveConfig.CURRENT_SCHEMA_VERSION,
                defaults.triggers(),
                defaults.git(),
                new ZipDestinationConfig(false, Optional.of(newWorld.resolve("archives"))),
                defaults.worlds());
        WorldArchiveConfig refreshed = ClientSettingsAccess.refreshWorlds(
                unsafe, saves, known::set);
        WorldArchiveSettingsRepository repository = new WorldArchiveSettingsRepository(
                new WorldArchiveConfigStore(
                        temporaryDirectory.resolve("config/refreshed-world.json")),
                known::get);

        assertThrows(IOException.class, () -> repository.save(refreshed));
        assertEquals(List.of(newWorld.toRealPath()), known.get());
        assertEquals(1, refreshed.worlds().size());
    }
}
