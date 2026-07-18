package dev.ishaankot.worldarchive.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Arrays;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.io.TempDir;

final class WorldArchiveConfigStoreTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void defaultsMatchProductBehavior() throws IOException {
        WorldArchiveConfig config = new WorldArchiveConfigStore(temporaryDirectory.resolve("missing.json"))
                .load(java.util.List.of());

        assertTrue(config.triggers().manualEnabled());
        assertTrue(config.triggers().worldExitEnabled());
        assertFalse(config.triggers().scheduledEnabled());
        assertEquals(30, config.triggers().scheduleIntervalMinutes());
        assertTrue(config.git().enabled());
        assertTrue(config.zip().enabled());
        assertEquals(
                dev.ishaankot.worldarchive.model.DestinationHealthStatus.UNCONFIGURED,
                config.git().health().status());
        assertEquals(
                dev.ishaankot.worldarchive.model.DestinationHealthStatus.UNCONFIGURED,
                config.zip().health().status());
    }

    @Test
    void roundTripsUtf8ConfigurationAtomically() throws IOException {
        Path gitRepository = Files.createDirectory(temporaryDirectory.resolve("git-世界"));
        Path zipDestination = Files.createDirectory(temporaryDirectory.resolve("zip-é"));
        Path file = temporaryDirectory.resolve("worldarchive.json");
        WorldArchiveConfigStore store = new WorldArchiveConfigStore(file);
        WorldArchiveConfig expected = new WorldArchiveConfig(
                WorldArchiveConfig.CURRENT_SCHEMA_VERSION,
                new TriggerConfig(true, false, true, 45),
                new GitDestinationConfig(
                        true,
                        Optional.of(gitRepository),
                        "backup-origin",
                        Optional.of("ssh://example.invalid/backups.git")),
                new ZipDestinationConfig(true, Optional.of(zipDestination)));

        store.save(expected, java.util.List.of());
        assertEquals(expected, store.load(java.util.List.of()));
        assertEquals(expected, store.load(java.util.List.of()));
        String serialized = Files.readString(file, StandardCharsets.UTF_8);
        assertTrue(serialized.contains("git-世界"));
        assertFalse(serialized.toLowerCase().contains("password"));
    }

    @Test
    void refusesMalformedAndFutureConfiguration() throws IOException {
        Path file = temporaryDirectory.resolve("worldarchive.json");
        WorldArchiveConfigStore store = new WorldArchiveConfigStore(file);

        Files.writeString(file, "{not-json", StandardCharsets.UTF_8);
        assertThrows(ConfigurationException.class, () -> store.load(java.util.List.of()));

        Files.writeString(file, "{}", StandardCharsets.UTF_8);
        assertThrows(ConfigurationException.class, () -> store.load(java.util.List.of()));

        Files.writeString(file, "{\"schemaVersion\":999}", StandardCharsets.UTF_8);
        UnsupportedSchemaVersionException exception = assertThrows(
                UnsupportedSchemaVersionException.class,
                () -> store.load(java.util.List.of()));
        assertEquals(999, exception.schemaVersion());

        Files.writeString(file, "{\"schemaVersion\":1.5}", StandardCharsets.UTF_8);
        assertThrows(ConfigurationException.class, () -> store.load(java.util.List.of()));
    }

    @Test
    void migratesLegacyConfigurationAndPersistsCurrentSchema() throws IOException {
        Path zipDestination = Files.createDirectory(temporaryDirectory.resolve("legacy-zips"));
        Path file = temporaryDirectory.resolve("worldarchive.json");
        String legacy = """
                {
                  "manualBackups": false,
                  "exitBackups": true,
                  "scheduleEnabled": true,
                  "scheduleMinutes": 60,
                  "gitEnabled": false,
                  "zipEnabled": true,
                  "zipDestination": "%s"
                }
                """.formatted(jsonPath(zipDestination));
        Files.writeString(file, legacy, StandardCharsets.UTF_8);

        WorldArchiveConfig migrated = new WorldArchiveConfigStore(file).load(java.util.List.of());

        assertEquals(WorldArchiveConfig.CURRENT_SCHEMA_VERSION, migrated.schemaVersion());
        assertFalse(migrated.triggers().manualEnabled());
        assertEquals(60, migrated.triggers().scheduleIntervalMinutes());
        assertFalse(migrated.git().enabled());
        assertEquals(zipDestination.toRealPath(), migrated.zip().destination().orElseThrow());
        assertTrue(Files.readString(file, StandardCharsets.UTF_8).contains("\"schemaVersion\": 3"));
    }

    @Test
    void ignoresOrphanedPartialWriteAndKeepsLastPublishedValue() throws IOException {
        Path file = temporaryDirectory.resolve("worldarchive.json");
        WorldArchiveConfigStore store = new WorldArchiveConfigStore(file);
        store.save(WorldArchiveConfig.defaults(), java.util.List.of());
        Files.writeString(
                temporaryDirectory.resolve(".worldarchive.json.interrupted.tmp"),
                "{\"schemaVersion\":999}",
                StandardCharsets.UTF_8);

        assertEquals(WorldArchiveConfig.defaults(), store.load(java.util.List.of()));
    }

    @Test
    void rejectsCredentialBearingConfiguration() throws IOException {
        assertThrows(IllegalArgumentException.class, () -> new GitDestinationConfig(
                true,
                Optional.empty(),
                "origin",
                Optional.of("https://user:password@example.invalid/repository.git")));

        Path file = temporaryDirectory.resolve("worldarchive.json");
        Files.writeString(
                file,
                "{\"schemaVersion\":1,\"accessToken\":\"never\"}",
                StandardCharsets.UTF_8);
        assertThrows(
                ConfigurationException.class,
                () -> new WorldArchiveConfigStore(file).load(java.util.List.of()));

        Files.writeString(
                file,
                "{\"schemaVersion\":2,\"extensions\":[{\"accessToken\":\"never\"}]}",
                StandardCharsets.UTF_8);
        assertThrows(
                ConfigurationException.class,
                () -> new WorldArchiveConfigStore(file).load(java.util.List.of()));
    }

    @Test
    void saveRejectsDestinationInsideKnownWorld() throws IOException {
        Path world = Files.createDirectory(temporaryDirectory.resolve("world"));
        WorldArchiveConfig config = new WorldArchiveConfig(
                WorldArchiveConfig.CURRENT_SCHEMA_VERSION,
                TriggerConfig.defaults(),
                new GitDestinationConfig(
                        true,
                        Optional.of(world.resolve("repository")),
                        "origin",
                        Optional.empty()),
                ZipDestinationConfig.defaults());

        WorldArchiveConfigStore store = new WorldArchiveConfigStore(temporaryDirectory.resolve("worldarchive.json"));
        assertThrows(IOException.class, () -> store.save(config, java.util.List.of(world)));
        assertFalse(Files.exists(store.file()));
    }

    @Test
    void noUnsafeSaveOverloadExists() {
        assertFalse(Arrays.stream(WorldArchiveConfigStore.class.getMethods())
                .anyMatch(method -> method.getName().equals("save") && method.getParameterCount() == 1));
    }

    @Test
    void roundTripsPerDestinationAndPerWorldSettings() throws IOException {
        Path world = Files.createDirectory(temporaryDirectory.resolve("configured-world"));
        Path git = Files.createDirectory(temporaryDirectory.resolve("configured-git"));
        Path zip = Files.createDirectory(temporaryDirectory.resolve("configured-zip"));
        WorldArchiveConfig expected = new WorldArchiveConfig(
                WorldArchiveConfig.CURRENT_SCHEMA_VERSION,
                TriggerConfig.defaults(),
                new GitDestinationConfig(
                        true,
                        Optional.of(git),
                        "origin",
                        Optional.empty(),
                        new DestinationTriggerConfig(true, false, false),
                        java.util.List.of("*.mca", "*.nbt"),
                        new dev.ishaankot.worldarchive.model.DestinationHealth(
                                dev.ishaankot.worldarchive.model.DestinationType.GIT,
                                dev.ishaankot.worldarchive.model.DestinationHealthStatus.HEALTHY,
                                "Git and LFS are ready",
                                Instant.parse("2026-07-17T12:00:00Z"))),
                new ZipDestinationConfig(
                        true,
                        Optional.of(zip),
                        new DestinationTriggerConfig(false, true, false),
                        new dev.ishaankot.worldarchive.model.DestinationHealth(
                                dev.ishaankot.worldarchive.model.DestinationType.ZIP,
                                dev.ishaankot.worldarchive.model.DestinationHealthStatus.DEGRADED,
                                "Desktop sync is paused",
                                Instant.parse("2026-07-17T12:01:00Z"))),
                java.util.List.of(new WorldConfig(
                        dev.ishaankot.worldarchive.model.WorldId.create(),
                        false,
                        world)));
        WorldArchiveConfigStore store = new WorldArchiveConfigStore(temporaryDirectory.resolve("settings.json"));

        store.save(expected, java.util.List.of(world));

        assertEquals(expected, store.load(java.util.List.of(world)));
    }

    @Test
    void migratesSchemaTwoWithDefaultHealthForBothDestinations() throws IOException {
        Path file = temporaryDirectory.resolve("schema-two.json");
        Files.writeString(file, """
                {
                  "schemaVersion": 2,
                  "triggers": {
                    "manualEnabled": true,
                    "worldExitEnabled": true,
                    "scheduledEnabled": false,
                    "scheduleIntervalMinutes": 30
                  },
                  "destinations": {
                    "git": {
                      "enabled": true,
                      "remoteName": "origin",
                      "triggers": {
                        "manualEnabled": true,
                        "worldExitEnabled": true,
                        "scheduledEnabled": true
                      },
                      "lfsPatterns": ["*.mca"]
                    },
                    "zip": {
                      "enabled": true,
                      "triggers": {
                        "manualEnabled": true,
                        "worldExitEnabled": true,
                        "scheduledEnabled": true
                      }
                    }
                  },
                  "worlds": []
                }
                """, StandardCharsets.UTF_8);

        WorldArchiveConfig migrated = new WorldArchiveConfigStore(file).load(java.util.List.of());

        assertEquals(WorldArchiveConfig.CURRENT_SCHEMA_VERSION, migrated.schemaVersion());
        assertEquals(
                dev.ishaankot.worldarchive.model.DestinationHealth.notChecked(
                        dev.ishaankot.worldarchive.model.DestinationType.GIT),
                migrated.git().health());
        assertEquals(
                dev.ishaankot.worldarchive.model.DestinationHealth.notChecked(
                        dev.ishaankot.worldarchive.model.DestinationType.ZIP),
                migrated.zip().health());
        assertTrue(Files.readString(file, StandardCharsets.UTF_8).contains("\"health\""));
    }

    @Test
    void concurrentStoreInstancesPublishOnlyCompleteJson() throws Exception {
        Path file = temporaryDirectory.resolve("concurrent.json");
        WorldArchiveConfigStore firstStore = new WorldArchiveConfigStore(file);
        WorldArchiveConfigStore secondStore = new WorldArchiveConfigStore(file);
        WorldArchiveConfig first = WorldArchiveConfig.defaults();
        WorldArchiveConfig second = new WorldArchiveConfig(
                WorldArchiveConfig.CURRENT_SCHEMA_VERSION,
                new TriggerConfig(false, true, false, 30),
                GitDestinationConfig.defaults(),
                ZipDestinationConfig.defaults());
        ExecutorService executor = Executors.newFixedThreadPool(8);
        try {
            java.util.List<Future<?>> writes = new java.util.ArrayList<>();
            for (int index = 0; index < 32; index++) {
                WorldArchiveConfig value = index % 2 == 0 ? first : second;
                WorldArchiveConfigStore target = index % 2 == 0 ? firstStore : secondStore;
                writes.add(executor.submit(() -> {
                    target.save(value, java.util.List.of());
                    return null;
                }));
            }
            for (Future<?> write : writes) {
                write.get();
            }
        } finally {
            executor.shutdownNow();
        }

        WorldArchiveConfig loaded = firstStore.load(java.util.List.of());
        assertTrue(loaded.equals(first) || loaded.equals(second));
    }

    @Test
    void rejectsSymbolicLinkLockFile() throws IOException {
        Path file = temporaryDirectory.resolve("locked.json");
        WorldArchiveConfigStore store = new WorldArchiveConfigStore(file);
        Path target = Files.createFile(temporaryDirectory.resolve("lock-target"));
        Path lock = file.resolveSibling(file.getFileName() + ".lock");
        try {
            Files.createSymbolicLink(lock, target);
        } catch (UnsupportedOperationException | IOException exception) {
            Assumptions.assumeTrue(false, "Symbolic links unavailable: " + exception.getMessage());
        }

        assertThrows(ConfigurationException.class, () -> store.load(java.util.List.of()));
    }

    private static String jsonPath(Path path) {
        return path.toString().replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
