package dev.ishaanko.worldarchive.settings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.ishaanko.worldarchive.config.DefaultDestinations;
import dev.ishaanko.worldarchive.config.StoragePolicy;
import dev.ishaanko.worldarchive.config.TriggerConfig;
import dev.ishaanko.worldarchive.config.WorldArchiveConfig;
import dev.ishaanko.worldarchive.config.WorldConfig;
import dev.ishaanko.worldarchive.model.BackupTrigger;
import dev.ishaanko.worldarchive.model.DestinationType;
import dev.ishaanko.worldarchive.model.WorldId;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SettingsDraftTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void untouchedDefaultsAndPausedDestinationsRemainValid() {
        WorldArchiveConfig resolvedDefaults = resolvedDefaults();
        SettingsDraft draft = SettingsDraft.from(resolvedDefaults);
        assertTrue(draft.validate(List.of()).isValid());
        assertTrue(draft.trigger(DestinationType.GIT, BackupTrigger.MANUAL));
        assertTrue(draft.trigger(DestinationType.ZIP, BackupTrigger.WORLD_EXIT));
        assertFalse(draft.trigger(DestinationType.ZIP, BackupTrigger.SCHEDULED));
        assertEquals("30", draft.scheduleInterval());

        draft.setGitEnabled(false);
        draft.setZipEnabled(false);

        WorldArchiveConfig config = draft.validate(List.of()).config().orElseThrow();
        assertFalse(config.git().enabled());
        assertFalse(config.zip().enabled());
        assertEquals(resolvedDefaults.git().repository(), config.git().repository());
        assertEquals(resolvedDefaults.zip().destination(), config.zip().destination());
    }

    @Test
    void foldsGlobalGatesIntoEachDestinationAndDerivesTheGlobals() {
        WorldArchiveConfig defaults = resolvedDefaults();
        SettingsDraft draft = SettingsDraft.from(new WorldArchiveConfig(
                new TriggerConfig(false, true, false, 45), defaults.git(), defaults.zip(), defaults.worlds()));

        assertFalse(draft.trigger(DestinationType.GIT, BackupTrigger.MANUAL));
        assertTrue(draft.trigger(DestinationType.GIT, BackupTrigger.WORLD_EXIT));
        assertFalse(draft.trigger(DestinationType.ZIP, BackupTrigger.MANUAL));

        draft.setTrigger(DestinationType.ZIP, BackupTrigger.MANUAL, true);
        draft.setTrigger(DestinationType.ZIP, BackupTrigger.WORLD_EXIT, false);
        draft.setTrigger(DestinationType.ZIP, BackupTrigger.SCHEDULED, true);
        WorldArchiveConfig config = draft.validate(List.of()).config().orElseThrow();

        assertEquals(new TriggerConfig(true, true, true, 45), config.triggers());
        assertFalse(config.git().triggers().manualEnabled());
        assertTrue(config.git().triggers().worldExitEnabled());
        assertTrue(config.zip().triggers().manualEnabled());
        assertFalse(config.zip().triggers().worldExitEnabled());
        assertTrue(config.zip().triggers().scheduledEnabled());
    }

    @Test
    void rejectsADestinationInsideAWorldEvenWhileItIsOff() throws IOException {
        Path world = Files.createDirectory(temporaryDirectory.resolve("world"));
        SettingsDraft draft = SettingsDraft.from(resolvedDefaults());
        draft.setZipEnabled(false);
        draft.setZipDestination(world.resolve("backups").toString());

        SettingsValidation validation = draft.validate(List.of(world));

        assertFalse(validation.isValid());
        assertTrue(validation.issue(SettingsField.ZIP_DESTINATION).orElseThrow().contains("must not be inside"));
    }

    @Test
    void acceptsExternalFoldersAWorldRemoteAndAFolderThatIsNotThereYet() throws IOException {
        Path world = Files.createDirectory(temporaryDirectory.resolve("world"));
        Path external = Files.createDirectory(temporaryDirectory.resolve("OneDrive"));
        SettingsDraft draft = draftWithWorld(world);
        WorldId worldId = draft.base().worlds().getFirst().worldId();
        draft.setGitRepository(external.resolve("git-store").toString());
        draft.setZipDestination(temporaryDirectory.resolve("unplugged-drive/archives").toString());
        draft.setGitRemoteName("backup-origin");
        draft.setWorldRemoteUrl(worldId, "https://example.com/user/forever-world.git");
        draft.setGitLfsPatterns("*.mca, *.dat, *.nbt");

        SettingsValidation validation = draft.validate(List.of(world));

        assertTrue(validation.isValid(), () -> validation.issues().toString());
        WorldArchiveConfig config = validation.config().orElseThrow();
        assertEquals(List.of("*.mca", "*.dat", "*.nbt"), config.git().lfsPatterns());
        assertEquals(Optional.of("https://example.com/user/forever-world.git"), config.worlds().getFirst().remoteUrl());
    }

    @Test
    void rejectsACredentialInARemoteAndAnInvalidSchedule() {
        SettingsDraft draft = draftWithWorld(temporaryDirectory.resolve("credential-world"));
        WorldId worldId = draft.base().worlds().getFirst().worldId();
        draft.setWorldRemoteUrl(worldId, "https://user:password@example.com/worlds.git");
        draft.setScheduleInterval("0");

        SettingsValidation validation = draft.validate(List.of());

        assertFalse(validation.isValid());
        assertTrue(validation.issue(worldId, SettingsField.WORLD_REMOTE_URL).isPresent());
        assertTrue(validation.issue(SettingsField.SCHEDULE_INTERVAL).isPresent());
    }

    @Test
    void eachWorldKeepsItsOwnProblems() {
        WorldId first = WorldId.create();
        WorldId second = WorldId.create();
        SettingsDraft draft = SettingsDraft.from(resolvedDefaults().withWorlds(List.of(
                WorldConfig.defaults(first, temporaryDirectory.resolve("first")),
                WorldConfig.defaults(second, temporaryDirectory.resolve("second")))));
        draft.setWorldRemoteUrl(first, "https://example.com/first.git?token=1");
        draft.setWorldZipOverride(second, true);
        draft.setWorldZipDestination(second, "relative/zips");

        SettingsValidation validation = draft.validate(List.of());

        assertTrue(validation.issue(first, SettingsField.WORLD_REMOTE_URL).isPresent());
        assertTrue(validation.issue(first, SettingsField.WORLD_ZIP_DESTINATION).isEmpty());
        assertTrue(validation.issue(second, SettingsField.WORLD_ZIP_DESTINATION).isPresent());
        assertTrue(validation.issue(second, SettingsField.WORLD_REMOTE_URL).isEmpty());
        assertEquals(Set.of(first, second), validation.invalidWorlds());
    }

    @Test
    void rejectsARegularFileAsADestination() throws IOException {
        Path file = Files.writeString(temporaryDirectory.resolve("not-a-folder"), "data");
        SettingsDraft draft = SettingsDraft.from(resolvedDefaults());
        draft.setZipDestination(file.toString());

        SettingsValidation validation = draft.validate(List.of());

        assertTrue(validation.issue(SettingsField.ZIP_DESTINATION).orElseThrow().contains("not a folder"));
    }

    @Test
    void defaultsKeepWhereBackupsAreAndSwitchEveryWorldBackOn() throws IOException {
        Path world = Files.createDirectory(temporaryDirectory.resolve("world"));
        Path customZips = Files.createDirectory(temporaryDirectory.resolve("custom-zips"));
        Path worldZips = Files.createDirectory(temporaryDirectory.resolve("world-zips"));
        WorldId worldId = WorldId.create();
        WorldArchiveConfig defaults = resolvedDefaults();
        WorldArchiveConfig saved = new WorldArchiveConfig(
                new TriggerConfig(true, false, true, 90),
                defaults.git(),
                defaults.zip().withDestination(Optional.of(customZips)),
                List.of(new WorldConfig(worldId, false, world, Optional.of("https://example.com/w.git"),
                        Optional.of(worldZips), StoragePolicy.defaults())));

        WorldArchiveConfig reset = SettingsDraft.from(saved).withDefaults()
                .validate(List.of(world)).config().orElseThrow();

        assertEquals(TriggerConfig.defaults(), reset.triggers());
        assertEquals(Optional.of(customZips.toRealPath()), reset.zip().destination());
        WorldConfig resetWorld = reset.worlds().getFirst();
        assertTrue(resetWorld.enabled());
        assertEquals(Optional.of("https://example.com/w.git"), resetWorld.remoteUrl());
        assertEquals(Optional.of(worldZips.toRealPath()), resetWorld.zipDestination());
    }

    @Test
    void theZipBoxRemembersTheWorldFolderWhenItIsUnticked() {
        WorldId worldId = WorldId.create();
        SettingsDraft draft = SettingsDraft.from(resolvedDefaults().withWorlds(List.of(
                WorldConfig.defaults(worldId, temporaryDirectory.resolve("world")))));
        String worldFolder = temporaryDirectory.resolve("world-zips").toString();

        draft.setWorldZipOverride(worldId, true);
        assertEquals(draft.zipDestination(), draft.worldZipDestination(worldId));
        draft.setWorldZipDestination(worldId, worldFolder);
        draft.setWorldZipOverride(worldId, false);
        assertEquals("", draft.worldZipDestination(worldId));
        assertTrue(draft.validate(List.of()).config().orElseThrow().worlds().getFirst().zipDestination().isEmpty());
        draft.setWorldZipOverride(worldId, true);

        assertEquals(worldFolder, draft.worldZipDestination(worldId));
    }

    private WorldArchiveConfig resolvedDefaults() {
        try {
            return new DefaultDestinations(temporaryDirectory.resolve("worldarchive"))
                    .resolve(WorldArchiveConfig.defaults())
                    .canonicalize();
        } catch (IOException exception) {
            throw new AssertionError(exception);
        }
    }

    private SettingsDraft draftWithWorld(Path world) {
        return SettingsDraft.from(resolvedDefaults().withWorlds(List.of(WorldConfig.defaults(WorldId.create(), world))));
    }
}
