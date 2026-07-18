package dev.ishaankot.worldarchive.settings;

import dev.ishaankot.worldarchive.config.WorldArchiveConfig;
import dev.ishaankot.worldarchive.config.DestinationTriggerConfig;
import dev.ishaankot.worldarchive.config.GitDestinationConfig;
import dev.ishaankot.worldarchive.config.PathSafety;
import dev.ishaankot.worldarchive.config.TriggerConfig;
import dev.ishaankot.worldarchive.config.WorldConfig;
import dev.ishaankot.worldarchive.config.ZipDestinationConfig;
import dev.ishaankot.worldarchive.model.DestinationHealth;
import dev.ishaankot.worldarchive.model.SensitiveDataRedactor;
import dev.ishaankot.worldarchive.model.WorldId;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Mutable presentation model that validates every settings entry point identically. */
public final class SettingsDraft {
    private final WorldArchiveConfig base;

    private final Map<WorldId, Boolean> worldEnabled;

    private boolean manualEnabled;

    private boolean worldExitEnabled;

    private boolean scheduledEnabled;

    private String scheduleInterval;

    private boolean gitEnabled;

    private String gitRepository;

    private String gitRemoteName;

    private String gitRemoteUrl;

    private boolean gitManualEnabled;

    private boolean gitWorldExitEnabled;

    private boolean gitScheduledEnabled;

    private String gitLfsPatterns;

    private boolean zipEnabled;

    private String zipDestination;

    private boolean zipManualEnabled;

    private boolean zipWorldExitEnabled;

    private boolean zipScheduledEnabled;

    private DestinationHealth gitHealth;

    private DestinationHealth zipHealth;

    private SettingsDraft(WorldArchiveConfig config) {
        base = Objects.requireNonNull(config, "config");
        manualEnabled = config.triggers().manualEnabled();
        worldExitEnabled = config.triggers().worldExitEnabled();
        scheduledEnabled = config.triggers().scheduledEnabled();
        scheduleInterval = Integer.toString(config.triggers().scheduleIntervalMinutes());
        gitEnabled = config.git().enabled();
        gitRepository = config.git().repository().map(Path::toString).orElse("");
        gitRemoteName = config.git().remoteName();
        gitRemoteUrl = config.git().remoteUrl().orElse("");
        gitManualEnabled = config.git().triggers().manualEnabled();
        gitWorldExitEnabled = config.git().triggers().worldExitEnabled();
        gitScheduledEnabled = config.git().triggers().scheduledEnabled();
        gitLfsPatterns = String.join(", ", config.git().lfsPatterns());
        zipEnabled = config.zip().enabled();
        zipDestination = config.zip().destination().map(Path::toString).orElse("");
        zipManualEnabled = config.zip().triggers().manualEnabled();
        zipWorldExitEnabled = config.zip().triggers().worldExitEnabled();
        zipScheduledEnabled = config.zip().triggers().scheduledEnabled();
        gitHealth = config.git().health();
        zipHealth = config.zip().health();
        worldEnabled = new LinkedHashMap<>();
        config.worlds().forEach(world -> worldEnabled.put(world.worldId(), world.enabled()));
    }

    private SettingsDraft(SettingsDraft source) {
        base = source.base;
        worldEnabled = new LinkedHashMap<>(source.worldEnabled);
        manualEnabled = source.manualEnabled;
        worldExitEnabled = source.worldExitEnabled;
        scheduledEnabled = source.scheduledEnabled;
        scheduleInterval = source.scheduleInterval;
        gitEnabled = source.gitEnabled;
        gitRepository = source.gitRepository;
        gitRemoteName = source.gitRemoteName;
        gitRemoteUrl = source.gitRemoteUrl;
        gitManualEnabled = source.gitManualEnabled;
        gitWorldExitEnabled = source.gitWorldExitEnabled;
        gitScheduledEnabled = source.gitScheduledEnabled;
        gitLfsPatterns = source.gitLfsPatterns;
        zipEnabled = source.zipEnabled;
        zipDestination = source.zipDestination;
        zipManualEnabled = source.zipManualEnabled;
        zipWorldExitEnabled = source.zipWorldExitEnabled;
        zipScheduledEnabled = source.zipScheduledEnabled;
        gitHealth = source.gitHealth;
        zipHealth = source.zipHealth;
    }

    public static SettingsDraft from(WorldArchiveConfig config) {
        return new SettingsDraft(config);
    }

    public SettingsDraft copy() {
        return new SettingsDraft(this);
    }

    /** Restores product defaults without losing discovered per-world identities and paths. */
    public static SettingsDraft defaultsKeepingWorlds(WorldArchiveConfig current) {
        Objects.requireNonNull(current, "current");
        WorldArchiveConfig defaults = WorldArchiveConfig.defaults();
        List<WorldConfig> worlds = current.worlds().stream()
                .map(world -> new WorldConfig(world.worldId(), true, world.path()))
                .toList();
        return new SettingsDraft(new WorldArchiveConfig(
                WorldArchiveConfig.CURRENT_SCHEMA_VERSION,
                defaults.triggers(),
                new GitDestinationConfig(
                        defaults.git().enabled(),
                        current.git().repository(),
                        defaults.git().remoteName(),
                        defaults.git().remoteUrl(),
                        defaults.git().triggers(),
                        defaults.git().lfsPatterns(),
                        defaults.git().health()),
                new ZipDestinationConfig(
                        defaults.zip().enabled(),
                        current.zip().destination(),
                        defaults.zip().triggers(),
                        defaults.zip().health()),
                worlds));
    }

    public static SettingsDraft defaultsKeepingWorlds(
            WorldArchiveConfig current,
            SettingsDefaults defaults) {
        Objects.requireNonNull(current, "current");
        Objects.requireNonNull(defaults, "defaults");
        List<WorldConfig> worlds = current.worlds().stream()
                .map(world -> new WorldConfig(world.worldId(), true, world.path()))
                .toList();
        return new SettingsDraft(defaults.defaultsKeepingWorlds(worlds));
    }

    /** Builds a config only when fields, permissions, and recursive-destination rules all pass. */
    public SettingsValidation validate(Collection<Path> knownWorldPaths) {
        Objects.requireNonNull(knownWorldPaths, "knownWorldPaths");
        Map<SettingsField, String> issues = new LinkedHashMap<>();
        int interval = parseScheduleInterval(issues);
        List<Path> sourceWorlds = collectWorldPaths(knownWorldPaths);
        Optional<Path> repository = validatePath(
                gitRepository,
                gitEnabled,
                "Git repository",
                SettingsField.GIT_REPOSITORY,
                sourceWorlds,
                issues);
        Optional<Path> archiveDirectory = validatePath(
                zipDestination,
                zipEnabled,
                "ZIP folder",
                SettingsField.ZIP_DESTINATION,
                sourceWorlds,
                issues);
        List<String> patterns = parseLfsPatterns(issues);

        GitDestinationConfig git = null;
        if (!issues.containsKey(SettingsField.GIT_REPOSITORY)
                && !issues.containsKey(SettingsField.GIT_LFS_PATTERNS)) {
            git = buildGitConfig(repository, patterns, issues);
        }
        ZipDestinationConfig zip = new ZipDestinationConfig(
                zipEnabled,
                archiveDirectory,
                new DestinationTriggerConfig(
                        zipManualEnabled,
                        zipWorldExitEnabled,
                        zipScheduledEnabled),
                zipHealth);

        if (!issues.isEmpty() || git == null) {
            return new SettingsValidation(Optional.empty(), issues);
        }
        WorldArchiveConfig candidate = new WorldArchiveConfig(
                WorldArchiveConfig.CURRENT_SCHEMA_VERSION,
                new TriggerConfig(manualEnabled, worldExitEnabled, scheduledEnabled, interval),
                git,
                zip,
                updatedWorlds());
        try {
            return new SettingsValidation(
                    Optional.of(candidate.validateDestinations(sourceWorlds)),
                    Map.of());
        } catch (IOException exception) {
            issues.put(SettingsField.DESTINATIONS, safePathMessage(exception));
            return new SettingsValidation(Optional.empty(), issues);
        }
    }

    private GitDestinationConfig buildGitConfig(
            Optional<Path> repository,
            List<String> patterns,
            Map<SettingsField, String> issues) {
        String remoteName = gitRemoteName;
        Optional<String> remoteUrl = gitRemoteUrl.isBlank()
                ? Optional.empty()
                : Optional.of(gitRemoteUrl);
        try {
            return new GitDestinationConfig(
                    gitEnabled,
                    repository,
                    remoteName,
                    remoteUrl,
                    new DestinationTriggerConfig(
                            gitManualEnabled,
                            gitWorldExitEnabled,
                            gitScheduledEnabled),
                    patterns,
                    gitHealth);
        } catch (IllegalArgumentException exception) {
            SettingsField field = exception.getMessage().contains("remote name")
                    ? SettingsField.GIT_REMOTE_NAME
                    : SettingsField.GIT_REMOTE_URL;
            issues.put(field, exception.getMessage());
            return null;
        }
    }

    private int parseScheduleInterval(Map<SettingsField, String> issues) {
        try {
            int interval = Integer.parseInt(scheduleInterval);
            if (interval < 1 || interval > TriggerConfig.MAXIMUM_SCHEDULE_INTERVAL_MINUTES) {
                throw new NumberFormatException("out of range");
            }
            return interval;
        } catch (NumberFormatException exception) {
            issues.put(SettingsField.SCHEDULE_INTERVAL, "Use a whole number from 1 to 10080 minutes");
            return TriggerConfig.DEFAULT_SCHEDULE_INTERVAL_MINUTES;
        }
    }

    private List<Path> collectWorldPaths(Collection<Path> knownWorldPaths) {
        List<Path> paths = new ArrayList<>(knownWorldPaths.size() + base.worlds().size());
        for (Path path : knownWorldPaths) {
            paths.add(Objects.requireNonNull(path, "knownWorldPath"));
        }
        base.worlds().forEach(world -> paths.add(world.path()));
        return List.copyOf(paths);
    }

    private static Optional<Path> validatePath(
            String value,
            boolean enabled,
            String label,
            SettingsField field,
            Collection<Path> worldPaths,
            Map<SettingsField, String> issues) {
        if (value.isBlank()) {
            if (enabled) {
                issues.put(field, label + " is required while this destination is enabled");
            }
            return Optional.empty();
        }
        try {
            Path path = Path.of(value);
            if (!path.isAbsolute()) {
                issues.put(field, label + " must be an absolute path");
                return Optional.empty();
            }
            Path destination = PathSafety.requireOutsideWorlds(path, worldPaths);
            Optional<String> accessIssue = directoryAccessIssue(destination);
            if (accessIssue.isPresent()) {
                issues.put(field, label + " " + accessIssue.get());
                return Optional.empty();
            }
            return Optional.of(destination);
        } catch (InvalidPathException exception) {
            issues.put(field, label + " is not a valid filesystem path");
        } catch (IOException exception) {
            issues.put(field, safePathMessage(exception));
        }
        return Optional.empty();
    }

    private static Optional<String> directoryAccessIssue(Path destination) throws IOException {
        Path existing = destination;
        while (existing != null && !Files.exists(existing, LinkOption.NOFOLLOW_LINKS)) {
            existing = existing.getParent();
        }
        if (existing == null) {
            return Optional.of("has no accessible parent folder");
        }
        Path realExisting = existing.toRealPath();
        if (!Files.isDirectory(realExisting)) {
            return Optional.of("or its nearest existing parent is not a folder");
        }
        if (!Files.isReadable(realExisting)) {
            return Optional.of("is not readable");
        }
        if (!Files.isWritable(realExisting)) {
            return Optional.of("is not writable");
        }
        return Optional.empty();
    }

    private List<String> parseLfsPatterns(Map<SettingsField, String> issues) {
        List<String> patterns = List.of(gitLfsPatterns.split("[,\\r\\n]+"));
        patterns = patterns.stream().map(String::strip).filter(pattern -> !pattern.isEmpty()).toList();
        try {
            new GitDestinationConfig(
                    false,
                    Optional.empty(),
                    GitDestinationConfig.DEFAULT_REMOTE_NAME,
                    Optional.empty(),
                    DestinationTriggerConfig.defaults(),
                    patterns,
                    gitHealth);
            return patterns;
        } catch (IllegalArgumentException exception) {
            issues.put(SettingsField.GIT_LFS_PATTERNS, exception.getMessage());
            return GitDestinationConfig.DEFAULT_LFS_PATTERNS;
        }
    }

    private List<WorldConfig> updatedWorlds() {
        return base.worlds().stream()
                .map(world -> new WorldConfig(
                        world.worldId(),
                        worldEnabled.getOrDefault(world.worldId(), world.enabled()),
                        world.path()))
                .toList();
    }

    private static String safePathMessage(IOException exception) {
        String message = exception.getMessage();
        if (message == null || message.isBlank()) {
            return "The destination path could not be validated";
        }
        String redacted = SensitiveDataRedactor.redact(message).strip();
        if (redacted.isEmpty() || redacted.chars().anyMatch(Character::isISOControl)) {
            return "The destination path could not be validated";
        }
        return redacted.length() <= 512 ? redacted : redacted.substring(0, 512);
    }

    public WorldArchiveConfig base() {
        return base;
    }

    public boolean manualEnabled() {
        return manualEnabled;
    }

    public void setManualEnabled(boolean enabled) {
        manualEnabled = enabled;
    }

    public boolean worldExitEnabled() {
        return worldExitEnabled;
    }

    public void setWorldExitEnabled(boolean enabled) {
        worldExitEnabled = enabled;
    }

    public boolean scheduledEnabled() {
        return scheduledEnabled;
    }

    public void setScheduledEnabled(boolean enabled) {
        scheduledEnabled = enabled;
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
        if (gitEnabled != enabled) {
            gitEnabled = enabled;
            resetGitHealth();
        }
    }

    public String gitRepository() {
        return gitRepository;
    }

    public void setGitRepository(String value) {
        String next = Objects.requireNonNull(value, "value");
        if (!gitRepository.equals(next)) {
            gitRepository = next;
            resetGitHealth();
        }
    }

    public String gitRemoteName() {
        return gitRemoteName;
    }

    public void setGitRemoteName(String value) {
        String next = Objects.requireNonNull(value, "value");
        if (!gitRemoteName.equals(next)) {
            gitRemoteName = next;
            resetGitHealth();
        }
    }

    public String gitRemoteUrl() {
        return gitRemoteUrl;
    }

    public void setGitRemoteUrl(String value) {
        String next = Objects.requireNonNull(value, "value");
        if (!gitRemoteUrl.equals(next)) {
            gitRemoteUrl = next;
            resetGitHealth();
        }
    }

    public boolean gitManualEnabled() {
        return gitManualEnabled;
    }

    public void setGitManualEnabled(boolean enabled) {
        gitManualEnabled = enabled;
    }

    public boolean gitWorldExitEnabled() {
        return gitWorldExitEnabled;
    }

    public void setGitWorldExitEnabled(boolean enabled) {
        gitWorldExitEnabled = enabled;
    }

    public boolean gitScheduledEnabled() {
        return gitScheduledEnabled;
    }

    public void setGitScheduledEnabled(boolean enabled) {
        gitScheduledEnabled = enabled;
    }

    public String gitLfsPatterns() {
        return gitLfsPatterns;
    }

    public void setGitLfsPatterns(String value) {
        String next = Objects.requireNonNull(value, "value");
        if (!gitLfsPatterns.equals(next)) {
            gitLfsPatterns = next;
            resetGitHealth();
        }
    }

    public boolean zipEnabled() {
        return zipEnabled;
    }

    public void setZipEnabled(boolean enabled) {
        if (zipEnabled != enabled) {
            zipEnabled = enabled;
            resetZipHealth();
        }
    }

    public String zipDestination() {
        return zipDestination;
    }

    public void setZipDestination(String value) {
        String next = Objects.requireNonNull(value, "value");
        if (!zipDestination.equals(next)) {
            zipDestination = next;
            resetZipHealth();
        }
    }

    public boolean zipManualEnabled() {
        return zipManualEnabled;
    }

    public void setZipManualEnabled(boolean enabled) {
        zipManualEnabled = enabled;
    }

    public boolean zipWorldExitEnabled() {
        return zipWorldExitEnabled;
    }

    public void setZipWorldExitEnabled(boolean enabled) {
        zipWorldExitEnabled = enabled;
    }

    public boolean zipScheduledEnabled() {
        return zipScheduledEnabled;
    }

    public void setZipScheduledEnabled(boolean enabled) {
        zipScheduledEnabled = enabled;
    }

    public boolean worldEnabled(WorldId worldId) {
        return worldEnabled.getOrDefault(Objects.requireNonNull(worldId, "worldId"), true);
    }

    public void setWorldEnabled(WorldId worldId, boolean enabled) {
        if (!worldEnabled.containsKey(Objects.requireNonNull(worldId, "worldId"))) {
            throw new IllegalArgumentException("Unknown world configuration: " + worldId);
        }
        worldEnabled.put(worldId, enabled);
    }

    public SettingsProbeRequest probeRequest() {
        return new SettingsProbeRequest(
                gitEnabled,
                "git",
                parseAbsolutePath(gitRepository),
                !gitRemoteUrl.isBlank(),
                zipEnabled,
                parseAbsolutePath(zipDestination));
    }

    public void resetHealth() {
        resetGitHealth();
        resetZipHealth();
    }

    public void applyHealth(SettingsHealthSnapshot health, Instant checkedAt) {
        Objects.requireNonNull(health, "health");
        Objects.requireNonNull(checkedAt, "checkedAt");
        gitHealth = health.gitDestinationHealth(checkedAt);
        zipHealth = health.zipDestinationHealth(checkedAt);
    }

    public DestinationHealth gitHealth() {
        return gitHealth;
    }

    public DestinationHealth zipHealth() {
        return zipHealth;
    }

    private void resetGitHealth() {
        gitHealth = SettingsHealthSnapshot.unchecked(probeRequest())
                .gitDestinationHealth(Instant.EPOCH);
    }

    private void resetZipHealth() {
        zipHealth = SettingsHealthSnapshot.unchecked(probeRequest())
                .zipDestinationHealth(Instant.EPOCH);
    }

    private static Optional<Path> parseAbsolutePath(String value) {
        if (value.isBlank()) {
            return Optional.empty();
        }
        try {
            Path path = Path.of(value);
            return path.isAbsolute() ? Optional.of(path.normalize()) : Optional.empty();
        } catch (InvalidPathException exception) {
            return Optional.empty();
        }
    }
}
