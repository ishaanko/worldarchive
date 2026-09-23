package dev.ishaanko.worldarchive.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.ishaanko.worldarchive.model.WorldId;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class WorldArchiveConfigStoreTest {
    private static final String WORLD_ID = "12345678-1234-1234-1234-123456789abc";

    @TempDir
    Path temporaryDirectory;

    @Test
    void roundTripsEverySettingAndStaysReadableByOlderVersions() throws IOException {
        Path gitRepository = Files.createDirectory(temporaryDirectory.resolve("git-世界"));
        Path zipDestination = Files.createDirectory(temporaryDirectory.resolve("zip-é"));
        Path world = Files.createDirectory(temporaryDirectory.resolve("forever-world"));
        Path worldZip = Files.createDirectory(temporaryDirectory.resolve("forever-world-zips"));
        WorldArchiveConfigStore store = store();
        WorldArchiveConfig expected = new WorldArchiveConfig(
                new TriggerConfig(true, false, true, 45),
                new GitDestinationConfig(true, Optional.of(gitRepository), "backup-origin",
                        new DestinationTriggerConfig(true, false, false), List.of("*.mca", "*.nbt")),
                new ZipDestinationConfig(false, Optional.of(zipDestination), new DestinationTriggerConfig(false, true, false)),
                List.of(new WorldConfig(
                        WorldId.create(),
                        false,
                        world,
                        Optional.of("ssh://git@example.invalid:2222/forever-world.git"),
                        Optional.of(worldZip),
                        new StoragePolicy(16L * 1_024 * 1_024 * 1_024, 5, 3, 9))));

        WorldArchiveConfig saved = store.update(ignored -> expected, List.of(world));

        assertEquals(expected.canonicalize(), saved);
        assertEquals(saved, store().load());
        JsonObject destinations = written().getAsJsonObject("destinations");
        // WorldArchive 0.3.9 refuses a destination without "health", and replaces unreadable settings.
        assertTrue(destinations.getAsJsonObject("git").has("health"));
        assertTrue(destinations.getAsJsonObject("zip").has("health"));
    }

    @Test
    void defaultFoldersAreFilledInButNeverStored() throws IOException {
        WorldArchiveConfig loaded = store().load();
        Path storageRoot = temporaryDirectory.toRealPath().resolve("worldarchive");
        assertEquals(Optional.of(storageRoot.resolve("git")), loaded.git().repository());
        assertEquals(Optional.of(storageRoot.resolve("archives")), loaded.zip().destination());

        store().update(config -> new WorldArchiveConfig(
                new TriggerConfig(true, true, true, 60), config.git(), config.zip(), config.worlds()), List.of());

        JsonObject destinations = written().getAsJsonObject("destinations");
        assertFalse(destinations.getAsJsonObject("git").has("repositoryRoot"));
        assertFalse(destinations.getAsJsonObject("zip").has("destination"));
        Path copiedGame = Files.createDirectory(temporaryDirectory.resolve("copied-instance"));
        Path copiedConfig = Files.createDirectory(copiedGame.resolve("config")).resolve("worldarchive.json");
        Files.copy(configFile(), copiedConfig);
        WorldArchiveConfig copied = new WorldArchiveConfigStore(
                copiedConfig, new DefaultDestinations(copiedGame.resolve("worldarchive"))).load();
        assertEquals(Optional.of(copiedGame.toRealPath().resolve("worldarchive/git")), copied.git().repository());
    }

    /**
     * The default folders are links to a USB drive that is not connected. The settings still load
     * and an unreadable file can still be reset; only a backup to such a folder fails, on its own.
     */
    @Test
    void defaultFoldersLinkedToAnOfflineDriveLeaveTheSettingsReadable() throws IOException {
        Path drive = Files.createDirectories(temporaryDirectory.resolve("usb-drive"));
        Path storage = Files.createDirectories(temporaryDirectory.resolve("worldarchive"));
        link(storage.resolve("git"), Files.createDirectories(drive.resolve("git")));
        link(storage.resolve("archives"), Files.createDirectories(drive.resolve("archives")));
        store().load();
        Files.move(drive, temporaryDirectory.resolve("unplugged"));

        WorldArchiveConfig loaded = store().load();
        Files.writeString(configFile(), "{\"schemaVersion\": 7,", StandardCharsets.UTF_8);
        Optional<WorldArchiveConfigStore.Reset> reset = store().reset(config -> config, List.of(), Instant.EPOCH);

        assertEquals(Optional.of(storage.resolve("git")), loaded.git().repository());
        assertTrue(reset.isPresent());
        assertEquals(loaded.zip().destination(), store().load().zip().destination());
    }

    @Test
    void upgradesAVersion031FileAndKeepsTheOriginal() throws IOException {
        Path gameFolder = temporaryDirectory.toRealPath();
        Path world = Files.createDirectories(gameFolder.resolve("saves/World"));
        String original = """
                {
                  "schemaVersion": 6,
                  "triggers": {"manualEnabled": true, "worldExitEnabled": true, "scheduledEnabled": false,
                    "scheduleIntervalMinutes": 30},
                  "destinations": {
                    "git": {
                      "enabled": true,
                      "repositoryRoot": "%s",
                      "remoteName": "origin",
                      "triggers": {"manualEnabled": true, "worldExitEnabled": true, "scheduledEnabled": true},
                      "lfsPatterns": ["*.mca", "*.mcr", "*.dat", "*.dat_old", "*.nbt", "*.zip"],
                      "health": {"status": "HEALTHY", "message": "Git git version 2.45.1 | LFS git-lfs/3.5.1",
                        "checkedAt": "2026-08-01T10:00:00Z"}
                    },
                    "zip": {
                      "enabled": true,
                      "destination": "%s",
                      "triggers": {"manualEnabled": true, "worldExitEnabled": true, "scheduledEnabled": true},
                      "health": {"status": "HEALTHY", "message": "ZIP archive folder is ready",
                        "checkedAt": "2026-08-01T10:00:00Z"}
                    }
                  },
                  "worlds": [
                    {"worldId": "%s", "enabled": true, "path": "%s", "remoteUrl": "git@github.com:player/world.git"}
                  ]
                }
                """.formatted(
                        json(gameFolder.resolve("worldarchive/git")),
                        json(gameFolder.resolve("worldarchive/archives")),
                        WORLD_ID,
                        json(world));
        Files.createDirectories(configFile().getParent());
        Files.writeString(configFile(), original, StandardCharsets.UTF_8);

        WorldArchiveConfig upgraded = store().load();

        WorldConfig upgradedWorld = upgraded.worlds().getFirst();
        assertEquals(StoragePolicy.defaults(), upgradedWorld.storagePolicy());
        assertEquals(Optional.of("git@github.com:player/world.git"), upgradedWorld.remoteUrl());
        assertEquals(original, Files.readString(backup(6), StandardCharsets.UTF_8));
        JsonObject written = written();
        assertEquals(WorldArchiveConfig.CURRENT_SCHEMA_VERSION, written.get("schemaVersion").getAsInt());
        assertFalse(written.getAsJsonObject("destinations").getAsJsonObject("git").has("repositoryRoot"));
        assertEquals(upgraded, store().load());
    }

    @Test
    void upgradesAVersion013RemoteTemplateToEachWorld() throws IOException {
        Path world = Files.createDirectories(temporaryDirectory.resolve("saves/World"));
        Files.createDirectories(configFile().getParent());
        Files.writeString(configFile(), """
                {
                  "schemaVersion": 4,
                  "triggers": {"manualEnabled": true, "worldExitEnabled": true, "scheduledEnabled": false,
                    "scheduleIntervalMinutes": 30},
                  "destinations": {
                    "git": {
                      "enabled": true,
                      "remoteName": "origin",
                      "remoteUrlTemplate": "https://example.invalid/world-{worldId}.git",
                      "triggers": {"manualEnabled": true, "worldExitEnabled": true, "scheduledEnabled": true},
                      "lfsPatterns": ["*.mca"],
                      "health": {"status": "UNCONFIGURED", "message": "Not checked", "checkedAt": "1970-01-01T00:00:00Z"}
                    },
                    "zip": {
                      "enabled": true,
                      "triggers": {"manualEnabled": true, "worldExitEnabled": true, "scheduledEnabled": true},
                      "health": {"status": "UNCONFIGURED", "message": "Not checked", "checkedAt": "1970-01-01T00:00:00Z"}
                    }
                  },
                  "worlds": [{"worldId": "%s", "enabled": true, "path": "%s"}]
                }
                """.formatted(WORLD_ID, json(world)), StandardCharsets.UTF_8);

        WorldArchiveConfig upgraded = store().load();

        assertEquals(
                Optional.of("https://example.invalid/world-" + WORLD_ID + ".git"),
                upgraded.worlds().getFirst().remoteUrl());
        assertTrue(Files.isRegularFile(backup(4)));
    }

    @Test
    void refusesFilesItCannotReadAndLeavesThemAsTheyAre() throws IOException {
        Files.createDirectories(configFile().getParent());
        List<String> unreadable = List.of(
                "{not-json",
                "{}",
                "{\"schemaVersion\":1.5}",
                "{\"schemaVersion\":7,\"triggers\":{},\"destinations\":{},\"worlds\":[]}",
                "{\"schemaVersion\":7,\"accessToken\":\"never\"}",
                validFileWithoutStoragePolicy(),
                validFileWithoutStoragePolicy().replace("\"worlds\"", "\"unused\""));
        for (String text : unreadable) {
            Files.writeString(configFile(), text, StandardCharsets.UTF_8);

            assertThrows(UnreadableConfigurationException.class, () -> store().load(), text);
            assertThrows(UnreadableConfigurationException.class, () -> store().update(config -> config, List.of()), text);
            assertEquals(text, Files.readString(configFile(), StandardCharsets.UTF_8));
        }
    }

    @Test
    void refusesNewerAndPreReleaseSchemasWithoutChangingThem() throws IOException {
        Files.createDirectories(configFile().getParent());
        for (int schemaVersion : new int[] {3, 99}) {
            String text = "{\"schemaVersion\": " + schemaVersion + "}";
            Files.writeString(configFile(), text, StandardCharsets.UTF_8);

            UnreadableConfigurationException failure = assertThrows(UnreadableConfigurationException.class,
                    () -> store().load());

            UnsupportedSchemaVersionException schema = assertInstanceOf(
                    UnsupportedSchemaVersionException.class, failure.getCause());
            assertEquals(schemaVersion > WorldArchiveConfig.CURRENT_SCHEMA_VERSION, schema.newer());
            assertEquals(text, Files.readString(configFile(), StandardCharsets.UTF_8));
            assertFalse(Files.exists(backup(schemaVersion)));
        }
    }

    @Test
    void concurrentStoresPublishOnlyCompleteFilesAndKeepEachChange() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(8);
        try {
            List<Future<?>> writes = new ArrayList<>();
            for (int index = 1; index <= 32; index++) {
                int minutes = index;
                writes.add(executor.submit(() -> store().update(
                        config -> new WorldArchiveConfig(
                                new TriggerConfig(true, true, true,
                                        Math.max(config.triggers().scheduleIntervalMinutes(), minutes)),
                                config.git(),
                                config.zip(),
                                config.worlds()),
                        List.of())));
            }
            for (Future<?> write : writes) {
                write.get();
            }
        } finally {
            executor.shutdownNow();
        }

        assertEquals(32, store().load().triggers().scheduleIntervalMinutes());
    }

    private static void link(Path link, Path target) throws IOException {
        try {
            Files.createSymbolicLink(link, target);
        } catch (UnsupportedOperationException | IOException exception) {
            Assumptions.abort("Symbolic links are unavailable: " + exception.getMessage());
        }
    }

    private WorldArchiveConfigStore store() {
        return new WorldArchiveConfigStore(configFile(), new DefaultDestinations(temporaryDirectory.resolve("worldarchive")));
    }

    private Path configFile() {
        return temporaryDirectory.resolve("config/worldarchive.json");
    }

    private Path backup(int schemaVersion) {
        return configFile().resolveSibling("worldarchive.json.schema" + schemaVersion + ".bak");
    }

    private JsonObject written() throws IOException {
        return JsonParser.parseString(Files.readString(configFile(), StandardCharsets.UTF_8)).getAsJsonObject();
    }

    private String validFileWithoutStoragePolicy() {
        return """
                {"schemaVersion": 7,
                 "triggers": {"manualEnabled": true, "worldExitEnabled": true, "scheduledEnabled": false,
                   "scheduleIntervalMinutes": 30},
                 "destinations": {
                   "git": {"enabled": true, "remoteName": "origin",
                     "triggers": {"manualEnabled": true, "worldExitEnabled": true, "scheduledEnabled": true},
                     "lfsPatterns": ["*.mca"]},
                   "zip": {"enabled": true,
                     "triggers": {"manualEnabled": true, "worldExitEnabled": true, "scheduledEnabled": true}}},
                 "worlds": [{"worldId": "%s", "enabled": true, "path": "%s"}]}
                """.formatted(WORLD_ID, json(temporaryDirectory.resolve("saves/World")));
    }

    private static String json(Path path) {
        return path.toString().replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
