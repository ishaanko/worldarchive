package dev.ishaankot.worldarchive.storage.git;

import dev.ishaankot.worldarchive.config.GitDestinationConfig;
import dev.ishaankot.worldarchive.config.DestinationTriggerConfig;
import dev.ishaankot.worldarchive.model.WorldId;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Complete native Git backend settings after application defaults have been resolved. */
public record GitBackendSettings(
        boolean enabled,
        Path repository,
        String executable,
        String remoteName,
        Optional<String> remoteUrl,
        List<String> lfsPatterns,
        Duration commandTimeout,
        int maximumOutputBytes,
        Optional<WorldId> isolatedWorldId) {
    public static final List<String> DEFAULT_LFS_PATTERNS = GitDestinationConfig.DEFAULT_LFS_PATTERNS;

    public static final Duration DEFAULT_COMMAND_TIMEOUT = Duration.ofMinutes(5);

    public static final int DEFAULT_MAXIMUM_OUTPUT_BYTES = 8 * 1_024 * 1_024;

    public GitBackendSettings {
        repository = Objects.requireNonNull(repository, "repository")
                .toAbsolutePath()
                .normalize();
        executable = requireText(executable, "executable");
        Optional<String> configuredRemote = Objects.requireNonNull(remoteUrl, "remoteUrl");
        Optional<String> validationRemote = configuredRemote.map(GitBackendSettings::validationUrl);
        GitDestinationConfig validated = new GitDestinationConfig(
                enabled,
                Optional.of(repository),
                remoteName,
                validationRemote,
                DestinationTriggerConfig.defaults(),
                lfsPatterns);
        remoteName = validated.remoteName();
        remoteUrl = configuredRemote;
        lfsPatterns = validated.lfsPatterns();
        commandTimeout = Objects.requireNonNull(commandTimeout, "commandTimeout");
        if (commandTimeout.isZero() || commandTimeout.isNegative()) {
            throw new IllegalArgumentException("Git command timeout must be positive");
        }
        if (maximumOutputBytes < 1_024 || maximumOutputBytes > 64 * 1_024 * 1_024) {
            throw new IllegalArgumentException("Git output limit must be between 1 KiB and 64 MiB");
        }
        isolatedWorldId = Objects.requireNonNull(isolatedWorldId, "isolatedWorldId");
    }

    /** Compatibility constructor for a repository that may contain multiple worlds. */
    public GitBackendSettings(
            boolean enabled,
            Path repository,
            String executable,
            String remoteName,
            Optional<String> remoteUrl,
            List<String> lfsPatterns,
            Duration commandTimeout,
            int maximumOutputBytes) {
        this(
                enabled,
                repository,
                executable,
                remoteName,
                remoteUrl,
                lfsPatterns,
                commandTimeout,
                maximumOutputBytes,
                Optional.empty());
    }

    public static GitBackendSettings from(GitDestinationConfig config, Path defaultRepository) {
        Objects.requireNonNull(config, "config");
        Path repository = config.repository().orElseGet(
                () -> Objects.requireNonNull(defaultRepository, "defaultRepository"));
        return new GitBackendSettings(
                config.enabled(),
                repository,
                "git",
                config.remoteName(),
                config.remoteUrl(),
                config.lfsPatterns(),
                DEFAULT_COMMAND_TIMEOUT,
                DEFAULT_MAXIMUM_OUTPUT_BYTES,
                Optional.empty());
    }

    /** Resolves the preserved shared repository settings migrated from schema 3, when present. */
    public static Optional<GitBackendSettings> legacyFrom(GitDestinationConfig config) {
        Objects.requireNonNull(config, "config");
        return config.legacyRepository().map(repository -> new GitBackendSettings(
                config.enabled(),
                repository,
                "git",
                config.remoteName(),
                config.legacyRemoteUrl(),
                config.lfsPatterns(),
                DEFAULT_COMMAND_TIMEOUT,
                DEFAULT_MAXIMUM_OUTPUT_BYTES,
                Optional.empty()));
    }

    GitBackendSettings forWorld(
            Path worldRepository,
            WorldId worldId,
            Optional<String> worldRemoteUrl) {
        return new GitBackendSettings(
                enabled,
                worldRepository,
                executable,
                remoteName,
                worldRemoteUrl,
                lfsPatterns,
                commandTimeout,
                maximumOutputBytes,
                Optional.of(Objects.requireNonNull(worldId, "worldId")));
    }

    GitBackendSettings forLegacyRepository(Path legacyRepository, Optional<String> legacyRemoteUrl) {
        return new GitBackendSettings(
                enabled,
                legacyRepository,
                executable,
                remoteName,
                legacyRemoteUrl,
                lfsPatterns,
                commandTimeout,
                maximumOutputBytes,
                Optional.empty());
    }

    GitBackendSettings withoutRemote(Path probeRepository) {
        return new GitBackendSettings(
                enabled,
                probeRepository,
                executable,
                remoteName,
                Optional.empty(),
                lfsPatterns,
                commandTimeout,
                maximumOutputBytes,
                Optional.empty());
    }

    static boolean isWorldRemoteTemplate(String remoteUrl) {
        return Objects.requireNonNull(remoteUrl, "remoteUrl").contains("{worldId}");
    }

    static String resolveWorldRemote(String template, WorldId worldId) {
        if (!isWorldRemoteTemplate(template)) {
            throw new IllegalArgumentException("Git remote URL is not a per-world template");
        }
        return template.replace("{worldId}", Objects.requireNonNull(worldId, "worldId").toString());
    }

    private static String validationUrl(String remoteUrl) {
        if (!isWorldRemoteTemplate(remoteUrl)) {
            return remoteUrl;
        }
        return resolveWorldRemote(
                remoteUrl,
                WorldId.parse("00000000-0000-0000-0000-000000000001"));
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank() || value.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(name + " is blank or contains control characters");
        }
        return value;
    }
}
