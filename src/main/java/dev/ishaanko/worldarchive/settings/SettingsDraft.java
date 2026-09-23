package dev.ishaanko.worldarchive.settings;

import dev.ishaanko.worldarchive.config.DestinationTriggerConfig;
import dev.ishaanko.worldarchive.config.GitDestinationConfig;
import dev.ishaanko.worldarchive.config.PathSafety;
import dev.ishaanko.worldarchive.config.RemoteUrlPolicy;
import dev.ishaanko.worldarchive.config.TriggerConfig;
import dev.ishaanko.worldarchive.config.WorldArchiveConfig;
import dev.ishaanko.worldarchive.config.WorldConfig;
import dev.ishaanko.worldarchive.config.ZipDestinationConfig;
import dev.ishaanko.worldarchive.model.BackupTrigger;
import dev.ishaanko.worldarchive.model.DestinationType;
import dev.ishaanko.worldarchive.model.SafeText;
import dev.ishaanko.worldarchive.model.WorldId;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.UnaryOperator;

/**
 * What the settings screen edits: every setting as the player typed it. {@link #validate}
 * checks the text and builds the configuration to save; {@link #changes} turns that into a
 * change that applies only what the player edited, so a change saved meanwhile by a world
 * registration or another game window is kept. Not thread-safe: hand a {@link #copy} to
 * another thread.
 */
public final class SettingsDraft {
    private static final int MESSAGE_LIMIT = 512;

    private static final String GIT_REPOSITORY_LABEL = "Git repository root";

    private static final String ZIP_FOLDER_LABEL = "ZIP folder";

    private static final String REQUIRED_WHILE_ENABLED = " is required while this destination is enabled";

    private static final String UNCHECKED_DESTINATION = "The destination path could not be validated";

    private final WorldArchiveConfig base;

    private final Map<DestinationType, EnumSet<BackupTrigger>> triggers = new EnumMap<>(DestinationType.class);

    private final Map<WorldId, WorldEdit> worlds = new LinkedHashMap<>();

    private String scheduleInterval;

    private boolean gitEnabled;

    private String gitRepository;

    private String gitRemoteName;

    private String gitLfsPatterns;

    private boolean zipEnabled;

    private String zipDestination;

    /**
     * @param base the settings the screen opened with
     * @param values the settings the fields start from
     */
    private SettingsDraft(WorldArchiveConfig base, WorldArchiveConfig values) {
        this.base = Objects.requireNonNull(base, "base");
        scheduleInterval = Integer.toString(values.triggers().scheduleIntervalMinutes());
        gitEnabled = values.git().enabled();
        gitRepository = pathText(values.git().repository());
        gitRemoteName = values.git().remoteName();
        gitLfsPatterns = String.join(", ", values.git().lfsPatterns());
        zipEnabled = values.zip().enabled();
        zipDestination = pathText(values.zip().destination());
        triggers.put(DestinationType.GIT, enabledTriggers(values.triggers(), values.git().triggers()));
        triggers.put(DestinationType.ZIP, enabledTriggers(values.triggers(), values.zip().triggers()));
        values.worlds().forEach(world -> worlds.put(world.worldId(), new WorldEdit(world)));
    }

    private SettingsDraft(SettingsDraft source) {
        base = source.base;
        scheduleInterval = source.scheduleInterval;
        gitEnabled = source.gitEnabled;
        gitRepository = source.gitRepository;
        gitRemoteName = source.gitRemoteName;
        gitLfsPatterns = source.gitLfsPatterns;
        zipEnabled = source.zipEnabled;
        zipDestination = source.zipDestination;
        source.triggers.forEach((destination, enabled) -> triggers.put(destination, EnumSet.copyOf(enabled)));
        source.worlds.forEach((worldId, edit) -> worlds.put(worldId, new WorldEdit(edit)));
    }

    public static SettingsDraft from(WorldArchiveConfig config) {
        return new SettingsDraft(config, config);
    }

    /**
     * Product defaults for every setting except where backups are kept: the Git repository
     * folder, the ZIP folder, and each world's remote and ZIP folder stay as saved, so existing
     * backups stay reachable. Every world is switched back on.
     */
    public SettingsDraft withDefaults() {
        WorldArchiveConfig defaults = WorldArchiveConfig.defaults();
        return new SettingsDraft(base, defaults
                .withGit(defaults.git().withRepository(base.git().repository()))
                .withZip(defaults.zip().withDestination(base.zip().destination()))
                .withWorlds(base.worlds().stream().map(world -> world.withEnabled(true)).toList()));
    }

    public SettingsDraft copy() {
        return new SettingsDraft(this);
    }

    /** The settings the screen opened with. */
    public WorldArchiveConfig base() {
        return base;
    }

    /**
     * Checks every field and builds the configuration to save. A folder must be absolute, must
     * not be a file, and must not be inside a world: a configured world or one of
     * {@code knownWorldPaths}. Whether a folder can be reached right now is not checked here;
     * the health footer warns about that, and saving still works.
     */
    public SettingsValidation validate(Collection<Path> knownWorldPaths) {
        Map<SettingsField, String> issues = new EnumMap<>(SettingsField.class);
        List<Path> sourceWorlds = sourceWorlds(knownWorldPaths, issues);
        int interval = scheduleIntervalMinutes(issues);
        Optional<Path> repository = folder(gitRepository, GIT_REPOSITORY_LABEL, sourceWorlds,
                issue -> issues.put(SettingsField.GIT_REPOSITORY, issue));
        if (gitEnabled && gitRepository.isBlank()) {
            issues.put(SettingsField.GIT_REPOSITORY, GIT_REPOSITORY_LABEL + REQUIRED_WHILE_ENABLED);
        }
        Optional<Path> zipFolder = folder(zipDestination, ZIP_FOLDER_LABEL, sourceWorlds,
                issue -> issues.put(SettingsField.ZIP_DESTINATION, issue));
        if (zipEnabled && zipDestination.isBlank()) {
            issues.put(SettingsField.ZIP_DESTINATION, ZIP_FOLDER_LABEL + REQUIRED_WHILE_ENABLED);
        }
        problem(() -> GitDestinationConfig.validateRemoteName(gitRemoteName))
                .ifPresent(issue -> issues.put(SettingsField.GIT_REMOTE_NAME, issue));
        List<String> patterns = lfsPatterns();
        problem(() -> GitDestinationConfig.validateLfsPatterns(patterns))
                .ifPresent(issue -> issues.put(SettingsField.GIT_LFS_PATTERNS, issue));
        Map<WorldId, Map<SettingsField, String>> worldIssues = new LinkedHashMap<>();
        List<WorldConfig> editedWorlds = editedWorlds(sourceWorlds, worldIssues);
        if (!issues.isEmpty() || !worldIssues.isEmpty()) {
            return SettingsValidation.invalid(issues, worldIssues);
        }
        return SettingsValidation.valid(new WorldArchiveConfig(
                new TriggerConfig(
                        anyDestination(BackupTrigger.MANUAL),
                        anyDestination(BackupTrigger.WORLD_EXIT),
                        anyDestination(BackupTrigger.SCHEDULED),
                        interval),
                new GitDestinationConfig(
                        gitEnabled, repository, gitRemoteName, destinationTriggers(DestinationType.GIT), patterns),
                new ZipDestinationConfig(zipEnabled, zipFolder, destinationTriggers(DestinationType.ZIP)),
                editedWorlds));
    }

    /**
     * The player's edits as a change to the settings as they are when the save runs: each value
     * that differs from what the screen opened with replaces the current one, and every other
     * value stays as it is now. {@code edited} is the configuration {@link #validate} built.
     */
    public UnaryOperator<WorldArchiveConfig> changes(WorldArchiveConfig edited) {
        Objects.requireNonNull(edited, "edited");
        WorldArchiveConfig opened = base;
        return current -> merge(opened, edited, current);
    }

    private static WorldArchiveConfig merge(
            WorldArchiveConfig opened,
            WorldArchiveConfig edited,
            WorldArchiveConfig current) {
        TriggerConfig openedTriggers = opened.triggers();
        TriggerConfig editedTriggers = edited.triggers();
        TriggerConfig currentTriggers = current.triggers();
        GitDestinationConfig openedGit = opened.git();
        GitDestinationConfig editedGit = edited.git();
        GitDestinationConfig currentGit = current.git();
        ZipDestinationConfig openedZip = opened.zip();
        ZipDestinationConfig editedZip = edited.zip();
        ZipDestinationConfig currentZip = current.zip();
        return new WorldArchiveConfig(
                new TriggerConfig(
                        pick(openedTriggers, editedTriggers, currentTriggers, TriggerConfig::manualEnabled),
                        pick(openedTriggers, editedTriggers, currentTriggers, TriggerConfig::worldExitEnabled),
                        pick(openedTriggers, editedTriggers, currentTriggers, TriggerConfig::scheduledEnabled),
                        pick(openedTriggers, editedTriggers, currentTriggers, TriggerConfig::scheduleIntervalMinutes)),
                new GitDestinationConfig(
                        pick(openedGit, editedGit, currentGit, GitDestinationConfig::enabled),
                        pick(openedGit, editedGit, currentGit, GitDestinationConfig::repository),
                        pick(openedGit, editedGit, currentGit, GitDestinationConfig::remoteName),
                        pick(openedGit, editedGit, currentGit, GitDestinationConfig::triggers),
                        pick(openedGit, editedGit, currentGit, GitDestinationConfig::lfsPatterns)),
                new ZipDestinationConfig(
                        pick(openedZip, editedZip, currentZip, ZipDestinationConfig::enabled),
                        pick(openedZip, editedZip, currentZip, ZipDestinationConfig::destination),
                        pick(openedZip, editedZip, currentZip, ZipDestinationConfig::triggers)),
                current.worlds().stream().map(world -> mergeWorld(opened, edited, world)).toList());
    }

    /** A world keeps its current folder and storage policy; only the fields the screen edits can change. */
    private static WorldConfig mergeWorld(WorldArchiveConfig opened, WorldArchiveConfig edited, WorldConfig current) {
        Optional<WorldConfig> before = opened.world(current.worldId());
        Optional<WorldConfig> after = edited.world(current.worldId());
        if (before.isEmpty() || after.isEmpty()) {
            return current;
        }
        return current
                .withEnabled(pick(before.get(), after.get(), current, WorldConfig::enabled))
                .withRemoteUrl(pick(before.get(), after.get(), current, WorldConfig::remoteUrl))
                .withZipDestination(pick(before.get(), after.get(), current, WorldConfig::zipDestination));
    }

    /** The edited value when the player changed it, otherwise the current one. */
    private static <R, T> T pick(R opened, R edited, R current, Function<R, T> field) {
        T mine = field.apply(edited);
        return mine.equals(field.apply(opened)) ? field.apply(current) : mine;
    }

    /** The folders destinations must stay out of, canonical and each once. */
    private List<Path> sourceWorlds(Collection<Path> knownWorldPaths, Map<SettingsField, String> issues) {
        List<Path> sourceWorlds = new ArrayList<>(knownWorldPaths);
        base.worlds().forEach(world -> sourceWorlds.add(world.path()));
        try {
            return PathSafety.canonicalizeAll(sourceWorlds);
        } catch (IOException exception) {
            issues.put(SettingsField.DESTINATIONS, SafeText.from(exception, UNCHECKED_DESTINATION, MESSAGE_LIMIT));
            return List.of();
        }
    }

    private int scheduleIntervalMinutes(Map<SettingsField, String> issues) {
        Optional<Integer> interval = parsedInterval().filter(TriggerConfig::isValidInterval);
        if (interval.isEmpty()) {
            issues.put(SettingsField.SCHEDULE_INTERVAL, "Use a whole number from 1 to "
                    + TriggerConfig.MAXIMUM_SCHEDULE_INTERVAL_MINUTES + " minutes");
            return TriggerConfig.DEFAULT_SCHEDULE_INTERVAL_MINUTES;
        }
        return interval.get();
    }

    private Optional<Integer> parsedInterval() {
        try {
            return Optional.of(Integer.parseInt(scheduleInterval.strip()));
        } catch (NumberFormatException exception) {
            return Optional.empty();
        }
    }

    private List<String> lfsPatterns() {
        return Arrays.stream(gitLfsPatterns.split("[,\\r\\n]+"))
                .map(String::strip)
                .filter(pattern -> !pattern.isEmpty())
                .toList();
    }

    private List<WorldConfig> editedWorlds(
            List<Path> sourceWorlds,
            Map<WorldId, Map<SettingsField, String>> worldIssues) {
        List<WorldConfig> edited = new ArrayList<>(base.worlds().size());
        for (WorldConfig world : base.worlds()) {
            WorldEdit edit = edit(world.worldId());
            String code = world.worldId().displayCode();
            Map<SettingsField, String> issues = new EnumMap<>(SettingsField.class);
            Optional<Path> zipFolder = folder(edit.zipDestination, "ZIP override for world " + code, sourceWorlds,
                    issue -> issues.put(SettingsField.WORLD_ZIP_DESTINATION, issue));
            Optional<String> remoteUrl = remoteUrl(edit.remoteUrl, code,
                    issue -> issues.put(SettingsField.WORLD_REMOTE_URL, issue));
            if (!issues.isEmpty()) {
                worldIssues.put(world.worldId(), issues);
            }
            edited.add(world.withEnabled(edit.enabled).withRemoteUrl(remoteUrl).withZipDestination(zipFolder));
        }
        return edited;
    }

    /**
     * The canonical folder a field names; empty when the field is blank or has a problem. A
     * problem is reported to {@code issue}.
     */
    private static Optional<Path> folder(String value, String label, List<Path> sourceWorlds, Consumer<String> issue) {
        if (value.isBlank()) {
            return Optional.empty();
        }
        Path path;
        try {
            path = Path.of(SettingsPaths.expandHome(value));
        } catch (InvalidPathException exception) {
            issue.accept(label + " is not a valid filesystem path");
            return Optional.empty();
        }
        if (!path.isAbsolute()) {
            issue.accept(label + " must be an absolute path");
            return Optional.empty();
        }
        try {
            Path destination = PathSafety.requireOutsideWorlds(path, sourceWorlds);
            if (PathSafety.nearestExisting(destination).filter(existing -> !Files.isDirectory(existing)).isPresent()) {
                issue.accept(label + " or its nearest existing parent is not a folder");
                return Optional.empty();
            }
            return Optional.of(destination);
        } catch (IOException exception) {
            issue.accept(SafeText.from(exception, UNCHECKED_DESTINATION, MESSAGE_LIMIT));
            return Optional.empty();
        }
    }

    private static Optional<String> remoteUrl(String value, String worldCode, Consumer<String> issue) {
        if (value.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(RemoteUrlPolicy.validateConfiguredPlain(value));
        } catch (IllegalArgumentException exception) {
            issue.accept("Git remote for world " + worldCode + " is invalid: " + exception.getMessage());
            return Optional.empty();
        }
    }

    private static Optional<String> problem(Runnable check) {
        try {
            check.run();
            return Optional.empty();
        } catch (IllegalArgumentException exception) {
            return Optional.of(exception.getMessage());
        }
    }

    /** A trigger shows as on for a destination only when both the global and the destination switch allow it. */
    private static EnumSet<BackupTrigger> enabledTriggers(TriggerConfig global, DestinationTriggerConfig destination) {
        EnumSet<BackupTrigger> enabled = EnumSet.noneOf(BackupTrigger.class);
        for (BackupTrigger trigger : BackupTrigger.values()) {
            if (global.enabledFor(trigger) && destination.enabledFor(trigger)) {
                enabled.add(trigger);
            }
        }
        return enabled;
    }

    private DestinationTriggerConfig destinationTriggers(DestinationType destination) {
        return new DestinationTriggerConfig(
                trigger(destination, BackupTrigger.MANUAL),
                trigger(destination, BackupTrigger.WORLD_EXIT),
                trigger(destination, BackupTrigger.SCHEDULED));
    }

    /** The global switch of a trigger is on while any destination uses it. */
    private boolean anyDestination(BackupTrigger trigger) {
        return triggers.values().stream().anyMatch(enabled -> enabled.contains(trigger));
    }

    public String scheduleInterval() {
        return scheduleInterval;
    }

    public void setScheduleInterval(String value) {
        scheduleInterval = Objects.requireNonNull(value, "value");
    }

    public boolean gitEnabled() {
        return gitEnabled;
    }

    public void setGitEnabled(boolean enabled) {
        gitEnabled = enabled;
    }

    public String gitRepository() {
        return gitRepository;
    }

    public void setGitRepository(String value) {
        gitRepository = Objects.requireNonNull(value, "value");
    }

    public String gitRemoteName() {
        return gitRemoteName;
    }

    public void setGitRemoteName(String value) {
        gitRemoteName = Objects.requireNonNull(value, "value");
    }

    public String gitLfsPatterns() {
        return gitLfsPatterns;
    }

    public void setGitLfsPatterns(String value) {
        gitLfsPatterns = Objects.requireNonNull(value, "value");
    }

    public boolean zipEnabled() {
        return zipEnabled;
    }

    public void setZipEnabled(boolean enabled) {
        zipEnabled = enabled;
    }

    public String zipDestination() {
        return zipDestination;
    }

    public void setZipDestination(String value) {
        zipDestination = Objects.requireNonNull(value, "value");
    }

    /** Whether a trigger starts backups to a destination. */
    public boolean trigger(DestinationType destination, BackupTrigger trigger) {
        return triggers.get(Objects.requireNonNull(destination, "destination"))
                .contains(Objects.requireNonNull(trigger, "trigger"));
    }

    public void setTrigger(DestinationType destination, BackupTrigger trigger, boolean enabled) {
        EnumSet<BackupTrigger> destinationTriggers = triggers.get(Objects.requireNonNull(destination, "destination"));
        if (enabled) {
            destinationTriggers.add(Objects.requireNonNull(trigger, "trigger"));
        } else {
            destinationTriggers.remove(Objects.requireNonNull(trigger, "trigger"));
        }
    }

    public boolean worldEnabled(WorldId worldId) {
        return edit(worldId).enabled;
    }

    public void setWorldEnabled(WorldId worldId, boolean enabled) {
        edit(worldId).enabled = enabled;
    }

    public String worldRemoteUrl(WorldId worldId) {
        return edit(worldId).remoteUrl;
    }

    public void setWorldRemoteUrl(WorldId worldId, String remoteUrl) {
        edit(worldId).remoteUrl = Objects.requireNonNull(remoteUrl, "remoteUrl");
    }

    /** Whether the world uses its own ZIP folder instead of the one on the ZIP tab. */
    public boolean worldZipOverride(WorldId worldId) {
        return edit(worldId).zipOverride;
    }

    /**
     * Ticking the box starts from the folder typed before it was last unticked, or else from the
     * ZIP tab's folder. Unticking it clears the world's folder and remembers it.
     */
    public void setWorldZipOverride(WorldId worldId, boolean override) {
        WorldEdit edit = edit(worldId);
        if (override == edit.zipOverride) {
            return;
        }
        edit.zipOverride = override;
        if (override) {
            edit.zipDestination = edit.rememberedZipDestination.isEmpty()
                    ? zipDestination
                    : edit.rememberedZipDestination;
        } else {
            edit.rememberedZipDestination = edit.zipDestination;
            edit.zipDestination = "";
        }
    }

    public String worldZipDestination(WorldId worldId) {
        return edit(worldId).zipDestination;
    }

    public void setWorldZipDestination(WorldId worldId, String destination) {
        edit(worldId).zipDestination = Objects.requireNonNull(destination, "destination");
    }

    /** What the health footer checks for the fields as they are now. */
    public SettingsProbeRequest probeRequest() {
        return new SettingsProbeRequest(
                gitEnabled,
                SettingsPaths.parseAbsolute(gitRepository),
                zipEnabled,
                SettingsPaths.parseAbsolute(zipDestination));
    }

    private WorldEdit edit(WorldId worldId) {
        WorldEdit edit = worlds.get(Objects.requireNonNull(worldId, "worldId"));
        if (edit == null) {
            throw new IllegalArgumentException("Unknown world configuration: " + worldId);
        }
        return edit;
    }

    private static String pathText(Optional<Path> path) {
        return path.map(Path::toString).orElse("");
    }

    /** One world's fields as typed. */
    private static final class WorldEdit {
        private boolean enabled;

        private String remoteUrl;

        private boolean zipOverride;

        private String zipDestination;

        private String rememberedZipDestination = "";

        private WorldEdit(WorldConfig world) {
            enabled = world.enabled();
            remoteUrl = world.remoteUrl().orElse("");
            zipDestination = pathText(world.zipDestination());
            zipOverride = !zipDestination.isEmpty();
        }

        private WorldEdit(WorldEdit source) {
            enabled = source.enabled;
            remoteUrl = source.remoteUrl;
            zipOverride = source.zipOverride;
            zipDestination = source.zipDestination;
            rememberedZipDestination = source.rememberedZipDestination;
        }
    }
}
