package dev.ishaanko.worldarchive.settings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.ishaanko.worldarchive.config.DefaultDestinations;
import dev.ishaanko.worldarchive.config.TriggerConfig;
import dev.ishaanko.worldarchive.config.UnreadableConfigurationException;
import dev.ishaanko.worldarchive.config.UnsupportedSchemaVersionException;
import dev.ishaanko.worldarchive.config.WorldArchiveConfig;
import dev.ishaanko.worldarchive.config.WorldArchiveConfigStore;
import dev.ishaanko.worldarchive.config.WorldConfig;
import dev.ishaanko.worldarchive.config.WorldIdentityStore;
import dev.ishaanko.worldarchive.model.WorldId;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The settings service on real folders, a real settings file and real world identity files. */
class SettingsServiceTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-22T15:45:00Z"), ZoneOffset.UTC);

    private static final String REMOTE = "https://example.com/player/world.git";

    private final WorldIdentityStore identities = new WorldIdentityStore();

    @TempDir
    Path temporaryDirectory;

    @Test
    void unreadableSettingsAreNeverOverwrittenUntilReset() throws IOException {
        Path game = Files.createDirectory(temporaryDirectory.resolve("minecraft"));
        Path world = world(Files.createDirectory(game.resolve("saves")), "World");
        Path configFile = Files.createDirectory(game.resolve("config")).resolve("worldarchive.json");
        String newerSettings = "{\"schemaVersion\": 99, \"destinations\": {\"zip\": {\"destination\": \"E:\\\\Backups\"}}}\n";
        Files.writeString(configFile, newerSettings, StandardCharsets.UTF_8);
        SettingsService settings = service(game);

        UnreadableConfigurationException failure = failure(UnreadableConfigurationException.class, settings.load());
        assertTrue(assertInstanceOf(UnsupportedSchemaVersionException.class, failure.getCause()).newer());
        failure(UnreadableConfigurationException.class, settings.registerWorld(identities.loadOrCreate(world), world));
        failure(UnreadableConfigurationException.class, settings.update(config -> new WorldArchiveConfig(
                new TriggerConfig(false, true, false, 45), config.git(), config.zip(), config.worlds())));
        failure(UnreadableConfigurationException.class, settings.saveSettings(UnaryOperator.identity()));
        assertEquals(newerSettings, Files.readString(configFile, StandardCharsets.UTF_8));

        Path keptCopy = await(settings.reset()).orElseThrow();

        assertEquals(configFile.resolveSibling("worldarchive.json.unreadable-20260922T154500Z"), keptCopy);
        assertEquals(newerSettings, Files.readString(keptCopy, StandardCharsets.UTF_8));
        assertTrue(settings.unreadable().isEmpty());
        assertEquals(List.of(world.toRealPath()), paths(await(service(game).load())));
    }

    /** Another game window fixed the settings file after this one found it unreadable: Reset keeps the file. */
    @Test
    void resetKeepsASettingsFileThatReadsAgain() throws IOException {
        Path game = Files.createDirectory(temporaryDirectory.resolve("minecraft"));
        world(Files.createDirectory(game.resolve("saves")), "World");
        Path configFile = Files.createDirectory(game.resolve("config")).resolve("worldarchive.json");
        Files.writeString(configFile, "{\"schemaVersion\": 7,", StandardCharsets.UTF_8);
        SettingsService settings = service(game);
        failure(UnreadableConfigurationException.class, settings.load());
        Files.delete(configFile);
        await(service(game).update(config -> new WorldArchiveConfig(
                new TriggerConfig(true, true, true, 45), config.git(), config.zip(), config.worlds())));

        Optional<Path> keptCopy = await(settings.reset());

        assertEquals(Optional.empty(), keptCopy);
        assertTrue(settings.unreadable().isEmpty());
        assertEquals(45, settings.current().triggers().scheduleIntervalMinutes());
        assertEquals(45, await(service(game).load()).triggers().scheduleIntervalMinutes());
        try (Stream<Path> files = Files.list(configFile.getParent())) {
            assertTrue(files.noneMatch(file -> file.getFileName().toString().contains(".unreadable-")));
        }
    }

    @Test
    void aFixedFileIsReadAgainAndReachesListeners() throws IOException {
        Path game = Files.createDirectory(temporaryDirectory.resolve("minecraft"));
        Path world = world(Files.createDirectory(game.resolve("saves")), "World");
        Path configFile = Files.createDirectory(game.resolve("config")).resolve("worldarchive.json");
        Files.writeString(configFile, "{\"schemaVersion\": 7,", StandardCharsets.UTF_8);
        SettingsService settings = service(game);
        List<WorldArchiveConfig> published = new ArrayList<>();
        settings.addConfigurationListener(published::add);
        failure(UnreadableConfigurationException.class, settings.load());

        Files.delete(configFile);
        WorldArchiveConfig loaded = await(settings.load());

        assertTrue(settings.unreadable().isEmpty());
        assertEquals(List.of(world.toRealPath()), paths(loaded));
        assertEquals(List.of(loaded), published);
    }

    @Test
    void copiedWorldKeepsSettingsOnTheOriginalFolder() throws IOException {
        Path game = Files.createDirectory(temporaryDirectory.resolve("minecraft"));
        Path saves = Files.createDirectory(game.resolve("saves"));
        Path original = world(saves, "World");
        WorldId worldId = identities.loadOrCreate(original);
        SettingsService before = service(game);
        await(before.load());
        await(before.update(config -> config.withWorld(worldId, world -> world.withRemoteUrl(Optional.of(REMOTE)))));
        Path copy = world(saves, "A copy of World");
        Files.createDirectory(copy.resolve(".worldarchive"));
        Files.copy(original.resolve(".worldarchive/world.json"), copy.resolve(".worldarchive/world.json"));

        SettingsService restarted = service(game);
        WorldArchiveConfig loaded = await(restarted.load());

        WorldConfig kept = loaded.world(worldId).orElseThrow();
        assertEquals(original.toRealPath(), kept.path());
        assertEquals(Optional.of(REMOTE), kept.remoteUrl());
        WorldId copyId = identities.loadOrCreate(copy);
        assertNotEquals(worldId, copyId);
        assertEquals(Optional.empty(), loaded.world(copyId).orElseThrow().remoteUrl());
        assertEquals(
                List.of(new WorldNotice.CopyGotOwnIdentity(copy.toRealPath(), original.toRealPath())),
                restarted.notices());
    }

    @Test
    void staleWriterDoesNotEraseAnotherWritersChange() throws IOException {
        Path game = Files.createDirectory(temporaryDirectory.resolve("minecraft"));
        Path saves = Files.createDirectory(game.resolve("saves"));
        WorldId firstId = identities.loadOrCreate(world(saves, "First"));
        SettingsService windowA = service(game);
        SettingsService windowB = service(game);
        await(windowA.load());
        await(windowB.load());
        SettingsDraft screenInA = SettingsDraft.from(windowA.current());

        await(windowB.update(config -> config.withWorld(firstId, world -> world.withRemoteUrl(Optional.of(REMOTE)))));
        Path second = world(saves, "Second");
        WorldId secondId = identities.loadOrCreate(second);
        await(windowA.registerWorld(secondId, second.toRealPath()));
        screenInA.setScheduleInterval("45");
        await(windowA.saveSettings(screenInA.changes(screenInA.validate(List.of()).config().orElseThrow())));

        WorldArchiveConfig onDisk = await(service(game).load());
        assertEquals(Optional.of(REMOTE), onDisk.world(firstId).orElseThrow().remoteUrl());
        assertTrue(onDisk.world(secondId).isPresent());
        assertEquals(45, onDisk.triggers().scheduleIntervalMinutes());
    }

    @Test
    void worldsNamedLikeCredentialsCanBeSaved() throws IOException {
        Path game = Files.createDirectory(temporaryDirectory.resolve("Basic Survival Pack"));
        Path saves = Files.createDirectory(game.resolve("saves"));
        Path basic = world(saves, "Basic Survival");
        Path secret = world(saves, "Secret=Base");

        WorldArchiveConfig loaded = await(service(game).load());

        assertEquals(List.of(basic.toRealPath(), secret.toRealPath()), paths(loaded));
        assertEquals(loaded, await(service(game).load()));
    }

    @Test
    void destinationInsideAWorldCreatedAfterLoadingIsRefusedAndNothingChanges() throws IOException {
        Path game = Files.createDirectory(temporaryDirectory.resolve("minecraft"));
        Path saves = Files.createDirectory(game.resolve("saves"));
        world(saves, "Old World");
        SettingsService settings = service(game);
        await(settings.load());
        Path configFile = game.resolve("config/worldarchive.json");
        String saved = Files.readString(configFile, StandardCharsets.UTF_8);
        Path newWorld = world(saves, "New World");
        SettingsDraft screen = SettingsDraft.from(settings.current());
        screen.setZipDestination(newWorld.resolve("backups").toString());
        WorldArchiveConfig edited = screen.validate(List.of()).config().orElseThrow();

        IOException refused = failure(IOException.class, settings.saveSettings(screen.changes(edited)));

        assertTrue(refused.getMessage().contains("must not be inside a source world"), refused.getMessage());
        assertEquals(saved, Files.readString(configFile, StandardCharsets.UTF_8));
        assertFalse(Files.exists(newWorld.resolve("backups")));
    }

    /** Backups never create a folder the player chose, so the save that names a new one creates it. */
    @Test
    void savingANewZipFolderCreatesIt() throws IOException {
        Path game = Files.createDirectory(temporaryDirectory.resolve("minecraft"));
        world(Files.createDirectory(game.resolve("saves")), "World");
        SettingsService settings = service(game);
        await(settings.load());
        Path chosen = temporaryDirectory.resolve("usb/Backups");
        SettingsDraft screen = SettingsDraft.from(settings.current());
        screen.setZipDestination(chosen.toString());

        await(settings.saveSettings(screen.changes(screen.validate(List.of()).config().orElseThrow())));

        assertTrue(Files.isDirectory(chosen));
    }

    /**
     * A restore writes the world into a staging folder in the saves folder. A scan while it runs,
     * as a settings save starts, neither gives that folder an identity nor lists it as a world.
     */
    @Test
    void aRestoreStagingFolderIsNeitherGivenAnIdentityNorListedAsAWorld() throws IOException {
        Path game = Files.createDirectory(temporaryDirectory.resolve("minecraft"));
        Path staging = Files.createDirectories(game.resolve("saves/.worldarchive-restore-123456"));
        Files.writeString(staging.resolve("level.dat"), "level data being restored", StandardCharsets.UTF_8);

        WorldArchiveConfig loaded = await(service(game).load());

        assertEquals(List.of(), paths(loaded));
        assertFalse(Files.exists(staging.resolve(".worldarchive")));
    }

    @Test
    void importedRemoteFillsOnlyAWorldWithoutOne() throws IOException {
        Path game = Files.createDirectory(temporaryDirectory.resolve("minecraft"));
        Path saves = Files.createDirectory(game.resolve("saves"));
        WorldId connected = identities.loadOrCreate(world(saves, "Connected"));
        WorldId unconnected = identities.loadOrCreate(world(saves, "Unconnected"));
        SettingsService settings = service(game);
        await(settings.load());
        await(settings.update(config -> config.withWorld(connected, world -> world.withRemoteUrl(Optional.of(REMOTE)))));
        String usbCopy = temporaryDirectory.resolve("usb/world.git").toString();

        WorldArchiveConfig saved = await(settings.connectWorldRemotes(Map.of(connected, usbCopy, unconnected, usbCopy)));

        assertEquals(Optional.of(REMOTE), saved.world(connected).orElseThrow().remoteUrl());
        assertEquals(Optional.of(usbCopy), saved.world(unconnected).orElseThrow().remoteUrl());
    }

    private SettingsService service(Path game) {
        return new SettingsService(
                new WorldArchiveConfigStore(
                        game.resolve("config/worldarchive.json"),
                        new DefaultDestinations(game.resolve("worldarchive"))),
                game.resolve("saves"),
                identities,
                Runnable::run,
                CLOCK);
    }

    private static Path world(Path saves, String name) throws IOException {
        Path world = Files.createDirectory(saves.resolve(name));
        Files.writeString(world.resolve("level.dat"), "level data", StandardCharsets.UTF_8);
        return world;
    }

    private static List<Path> paths(WorldArchiveConfig config) {
        return config.worlds().stream().map(WorldConfig::path).toList();
    }

    private static <T> T await(CompletionStage<T> stage) {
        return stage.toCompletableFuture().join();
    }

    private static <E extends Throwable> E failure(Class<E> type, CompletionStage<?> stage) {
        CompletionException failure = assertThrows(CompletionException.class, () -> await(stage));
        return assertInstanceOf(type, failure.getCause());
    }
}
