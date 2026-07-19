package dev.ishaankot.worldarchive.settings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.ishaankot.worldarchive.config.WorldArchiveConfig;
import dev.ishaankot.worldarchive.config.GitDestinationConfig;
import dev.ishaankot.worldarchive.config.TriggerConfig;
import dev.ishaankot.worldarchive.config.WorldConfig;
import dev.ishaankot.worldarchive.config.ZipDestinationConfig;
import dev.ishaankot.worldarchive.model.DestinationHealth;
import dev.ishaankot.worldarchive.model.DestinationHealthStatus;
import dev.ishaankot.worldarchive.model.DestinationType;
import dev.ishaankot.worldarchive.model.WorldId;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
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
        assertTrue(draft.gitManualEnabled());
        assertTrue(draft.gitWorldExitEnabled());
        assertFalse(draft.gitScheduledEnabled());
        assertTrue(draft.zipManualEnabled());
        assertTrue(draft.zipWorldExitEnabled());
        assertFalse(draft.zipScheduledEnabled());
        assertEquals("30", draft.scheduleInterval());

        draft.setGitEnabled(false);
        draft.setZipEnabled(false);

        SettingsValidation validation = draft.validate(List.of());
        assertTrue(validation.isValid());
        assertFalse(validation.config().orElseThrow().git().enabled());
        assertFalse(validation.config().orElseThrow().zip().enabled());
        assertEquals(
                resolvedDefaults.git().repository(),
                validation.config().orElseThrow().git().repository());
        assertEquals(
                resolvedDefaults.zip().destination(),
                validation.config().orElseThrow().zip().destination());
    }

    @Test
    void foldsLegacyGlobalGatesIntoVisibleChoicesAndDerivesPersistedGlobals() {
        WorldArchiveConfig defaults = resolvedDefaults();
        WorldArchiveConfig legacyGates = new WorldArchiveConfig(
                WorldArchiveConfig.CURRENT_SCHEMA_VERSION,
                new TriggerConfig(false, true, false, 45),
                defaults.git(),
                defaults.zip(),
                defaults.worlds());
        SettingsDraft draft = SettingsDraft.from(legacyGates);

        assertFalse(draft.gitManualEnabled());
        assertTrue(draft.gitWorldExitEnabled());
        assertFalse(draft.gitScheduledEnabled());
        assertFalse(draft.zipManualEnabled());
        assertTrue(draft.zipWorldExitEnabled());
        assertFalse(draft.zipScheduledEnabled());

        draft.setScheduleInterval("45");
        draft.setGitManualEnabled(false);
        draft.setGitWorldExitEnabled(true);
        draft.setGitScheduledEnabled(false);
        draft.setZipManualEnabled(true);
        draft.setZipWorldExitEnabled(false);
        draft.setZipScheduledEnabled(true);

        WorldArchiveConfig config = draft.validate(List.of()).config().orElseThrow();

        assertTrue(config.triggers().manualEnabled());
        assertTrue(config.triggers().worldExitEnabled());
        assertTrue(config.triggers().scheduledEnabled());
        assertEquals(45, config.triggers().scheduleIntervalMinutes());
        assertFalse(config.git().triggers().manualEnabled());
        assertTrue(config.git().triggers().worldExitEnabled());
        assertFalse(config.git().triggers().scheduledEnabled());
        assertTrue(config.zip().triggers().manualEnabled());
        assertFalse(config.zip().triggers().worldExitEnabled());
        assertTrue(config.zip().triggers().scheduledEnabled());
    }

    @Test
    void rejectsDestinationNestedInsideKnownWorld() throws IOException {
        Path world = Files.createDirectory(temporaryDirectory.resolve("world"));
        Path nested = world.resolve("backups");
        SettingsDraft draft = SettingsDraft.from(resolvedDefaults());
        draft.setZipDestination(nested.toString());

        SettingsValidation validation = draft.validate(List.of(world));

        assertFalse(validation.isValid());
        assertTrue(validation.issue(SettingsField.ZIP_DESTINATION).orElseThrow()
                .contains("must not be inside"));
    }

    @Test
    void acceptsOrdinaryExternalAndDesktopSyncedFolders() throws IOException {
        Path world = Files.createDirectory(temporaryDirectory.resolve("world"));
        Path syncedFolder = Files.createDirectory(temporaryDirectory.resolve("OneDrive"));
        SettingsDraft draft = SettingsDraft.from(resolvedDefaults());
        draft.setGitRepository(syncedFolder.resolve("git-store").toString());
        draft.setZipDestination(syncedFolder.resolve("archives").toString());
        draft.setGitRemoteName("backup-origin");
        draft.setGitRemoteUrl("https://example.com/user/worlds-{worldId}.git");
        draft.setGitLfsPatterns("*.mca, *.dat, *.nbt");

        SettingsValidation validation = draft.validate(List.of(world));

        assertTrue(validation.isValid(), () -> validation.issues().toString());
        assertEquals(3, validation.config().orElseThrow().git().lfsPatterns().size());
    }

    @Test
    void rejectsCredentialBearingRemoteAndInvalidSchedule() {
        SettingsDraft draft = SettingsDraft.from(resolvedDefaults());
        draft.setGitRemoteUrl("https://user:password@example.com/worlds.git");
        draft.setScheduleInterval("0");

        SettingsValidation validation = draft.validate(List.of());

        assertFalse(validation.isValid());
        assertTrue(validation.issue(SettingsField.GIT_REMOTE_URL).isPresent());
        assertTrue(validation.issue(SettingsField.SCHEDULE_INTERVAL).isPresent());
    }

    @Test
    void rejectsPlainRemoteForNewPerWorldRepositories() {
        SettingsDraft draft = SettingsDraft.from(resolvedDefaults());
        draft.setGitRemoteUrl("https://example.com/user/shared.git");

        SettingsValidation validation = draft.validate(List.of());

        assertFalse(validation.isValid());
        assertEquals(
                "Include {worldId} so every world uses a different remote repository",
                validation.issue(SettingsField.GIT_REMOTE_URL).orElseThrow());
    }

    @Test
    void rejectsRegularFileAsDestination() throws IOException {
        Path file = Files.writeString(temporaryDirectory.resolve("not-a-folder"), "data");
        SettingsDraft draft = SettingsDraft.from(resolvedDefaults());
        draft.setZipDestination(file.toString());

        SettingsValidation validation = draft.validate(List.of());

        assertFalse(validation.isValid());
        assertTrue(validation.issue(SettingsField.ZIP_DESTINATION).orElseThrow().contains("not a folder"));
    }

    @Test
    void disabledDestinationStillRejectsAndDoesNotHideRecursivePath() throws IOException {
        Path world = Files.createDirectory(temporaryDirectory.resolve("paused-world"));
        SettingsDraft draft = SettingsDraft.from(resolvedDefaults());
        draft.setZipEnabled(false);
        draft.setZipDestination(world.resolve("archives").toString());

        SettingsValidation validation = draft.validate(List.of(world));

        assertFalse(validation.isValid());
        assertTrue(validation.issue(SettingsField.ZIP_DESTINATION).orElseThrow()
                .contains("must not be inside"));
    }

    @Test
    void destinationChangesInvalidateOnlyTheirPersistedHealth() {
        SettingsDefaults defaults = new SettingsDefaults(temporaryDirectory.resolve("worldarchive"));
        WorldArchiveConfig base = defaults.resolve(WorldArchiveConfig.defaults());
        Instant checkedAt = Instant.parse("2026-07-17T12:00:00Z");
        DestinationHealth healthyGit = new DestinationHealth(
                DestinationType.GIT,
                DestinationHealthStatus.HEALTHY,
                "Git and LFS are ready",
                checkedAt);
        DestinationHealth healthyZip = new DestinationHealth(
                DestinationType.ZIP,
                DestinationHealthStatus.HEALTHY,
                "ZIP folder is ready",
                checkedAt);
        WorldArchiveConfig checked = new WorldArchiveConfig(
                WorldArchiveConfig.CURRENT_SCHEMA_VERSION,
                base.triggers(),
                new GitDestinationConfig(
                        true,
                        base.git().repository(),
                        base.git().remoteName(),
                        Optional.empty(),
                        base.git().triggers(),
                        base.git().lfsPatterns(),
                        healthyGit),
                new ZipDestinationConfig(
                        true,
                        base.zip().destination(),
                        base.zip().triggers(),
                        healthyZip),
                List.of());
        SettingsDraft draft = SettingsDraft.from(checked);

        draft.setGitRemoteUrl("https://example.com/worlds-{worldId}.git");

        assertEquals(DestinationHealthStatus.UNCONFIGURED, draft.gitHealth().status());
        assertEquals(DestinationHealthStatus.HEALTHY, draft.zipHealth().status());

        draft.setZipDestination(temporaryDirectory.resolve("other-archives").toString());

        assertEquals(DestinationHealthStatus.UNCONFIGURED, draft.zipHealth().status());
    }

    @Test
    void validationIssuesRedactCredentialBearingDiagnostics() {
        SettingsValidation validation = new SettingsValidation(
                Optional.empty(),
                Map.of(SettingsField.DESTINATIONS, "Authorization: Bearer abcdefghijklmnop"));

        assertEquals(
                "Authorization: Bearer [REDACTED]",
                validation.firstIssue().orElseThrow());
    }

    @Test
    void retainsWorldIdentityWhileChangingEnablementAndResettingDefaults() throws IOException {
        Path world = Files.createDirectory(temporaryDirectory.resolve("world"));
        WorldId worldId = new WorldId(UUID.randomUUID());
        WorldArchiveConfig productDefaults = resolvedDefaults();
        WorldArchiveConfig config = new WorldArchiveConfig(
                WorldArchiveConfig.CURRENT_SCHEMA_VERSION,
                productDefaults.triggers(),
                productDefaults.git(),
                productDefaults.zip(),
                List.of(new WorldConfig(worldId, true, world)));
        SettingsDraft draft = SettingsDraft.from(config);
        draft.setWorldEnabled(worldId, false);

        assertFalse(draft.validate(List.of(world)).config().orElseThrow().worlds().getFirst().enabled());

        SettingsDraft defaults = SettingsDraft.defaultsKeepingWorlds(config);
        assertTrue(defaults.validate(List.of(world)).config().orElseThrow().worlds().getFirst().enabled());
        assertEquals(worldId, defaults.base().worlds().getFirst().worldId());
    }

    @Test
    void preservesHiddenLegacyGitStorageAcrossValidationAndDefaults() throws IOException {
        Path legacyRepository = Files.createDirectory(temporaryDirectory.resolve("legacy-shared.git"));
        WorldArchiveConfig resolved = resolvedDefaults();
        GitDestinationConfig git = resolved.git();
        WorldArchiveConfig migrated = new WorldArchiveConfig(
                WorldArchiveConfig.CURRENT_SCHEMA_VERSION,
                resolved.triggers(),
                new GitDestinationConfig(
                        git.enabled(),
                        git.repository(),
                        git.remoteName(),
                        git.remoteUrl(),
                        git.triggers(),
                        git.lfsPatterns(),
                        git.health(),
                        Optional.of(legacyRepository),
                        Optional.of("https://example.invalid/legacy.git")),
                resolved.zip(),
                resolved.worlds());

        WorldArchiveConfig validated = SettingsDraft.from(migrated)
                .validate(List.of())
                .config()
                .orElseThrow();
        SettingsDraft reset = SettingsDraft.defaultsKeepingWorlds(
                migrated,
                new SettingsDefaults(temporaryDirectory.resolve("new-defaults")));

        assertEquals(legacyRepository.toRealPath(), validated.git().legacyRepository().orElseThrow());
        assertEquals(
                migrated.git().legacyRemoteUrl(),
                validated.git().legacyRemoteUrl());
        assertEquals(
                migrated.git().legacyRepository(),
                reset.base().git().legacyRepository());
        assertEquals(
                migrated.git().legacyRemoteUrl(),
                reset.base().git().legacyRemoteUrl());
    }

    private WorldArchiveConfig resolvedDefaults() {
        try {
            return new SettingsDefaults(temporaryDirectory.resolve("worldarchive"))
                    .resolve(WorldArchiveConfig.defaults())
                    .validateDestinations(List.of());
        } catch (IOException exception) {
            throw new AssertionError(exception);
        }
    }
}
