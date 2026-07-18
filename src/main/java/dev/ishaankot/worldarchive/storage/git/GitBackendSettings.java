package dev.ishaankot.worldarchive.storage.git;

import dev.ishaankot.worldarchive.config.GitDestinationConfig;
import dev.ishaankot.worldarchive.config.DestinationTriggerConfig;
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
        int maximumOutputBytes) {
    public static final List<String> DEFAULT_LFS_PATTERNS = GitDestinationConfig.DEFAULT_LFS_PATTERNS;

    public static final Duration DEFAULT_COMMAND_TIMEOUT = Duration.ofMinutes(5);

    public static final int DEFAULT_MAXIMUM_OUTPUT_BYTES = 8 * 1_024 * 1_024;

    public GitBackendSettings {
        repository = Objects.requireNonNull(repository, "repository")
                .toAbsolutePath()
                .normalize();
        executable = requireText(executable, "executable");
        GitDestinationConfig validated = new GitDestinationConfig(
                enabled,
                Optional.of(repository),
                remoteName,
                remoteUrl,
                DestinationTriggerConfig.defaults(),
                lfsPatterns);
        remoteName = validated.remoteName();
        remoteUrl = validated.remoteUrl();
        lfsPatterns = validated.lfsPatterns();
        commandTimeout = Objects.requireNonNull(commandTimeout, "commandTimeout");
        if (commandTimeout.isZero() || commandTimeout.isNegative()) {
            throw new IllegalArgumentException("Git command timeout must be positive");
        }
        if (maximumOutputBytes < 1_024 || maximumOutputBytes > 64 * 1_024 * 1_024) {
            throw new IllegalArgumentException("Git output limit must be between 1 KiB and 64 MiB");
        }
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
                DEFAULT_MAXIMUM_OUTPUT_BYTES);
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank() || value.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(name + " is blank or contains control characters");
        }
        return value;
    }
}
