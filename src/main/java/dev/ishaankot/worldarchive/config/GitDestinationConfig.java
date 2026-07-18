package dev.ishaankot.worldarchive.config;

import dev.ishaankot.worldarchive.model.DestinationHealth;
import dev.ishaankot.worldarchive.model.DestinationType;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/** Configuration for the external shared bare Git repository. */
public record GitDestinationConfig(
        boolean enabled,
        Optional<Path> repository,
        String remoteName,
        Optional<String> remoteUrl,
        DestinationTriggerConfig triggers,
        List<String> lfsPatterns,
        DestinationHealth health) {
    public static final String DEFAULT_REMOTE_NAME = "origin";

    public static final List<String> DEFAULT_LFS_PATTERNS = List.of(
            "*.mca",
            "*.mcr",
            "*.dat",
            "*.dat_old",
            "*.nbt",
            "*.zip");

    private static final Pattern REMOTE_NAME = Pattern.compile("[A-Za-z0-9._-]{1,64}");

    public GitDestinationConfig {
        repository = Objects.requireNonNull(repository, "repository")
                .map(path -> path.toAbsolutePath().normalize());
        Objects.requireNonNull(remoteName, "remoteName");
        if (!REMOTE_NAME.matcher(remoteName).matches()
                || remoteName.startsWith("-")
                || remoteName.equals(".")
                || remoteName.equals("..")) {
            throw new IllegalArgumentException("Git remote name is unsafe");
        }
        remoteUrl = Objects.requireNonNull(remoteUrl, "remoteUrl").map(RemoteUrlPolicy::validate);
        Objects.requireNonNull(triggers, "triggers");
        lfsPatterns = validatePatterns(lfsPatterns);
        Objects.requireNonNull(health, "health");
        if (health.destination() != DestinationType.GIT) {
            throw new IllegalArgumentException("Git health state must describe the Git destination");
        }
    }

    /** Compatibility constructor for callers that predate persisted destination health. */
    public GitDestinationConfig(
            boolean enabled,
            Optional<Path> repository,
            String remoteName,
            Optional<String> remoteUrl,
            DestinationTriggerConfig triggers,
            List<String> lfsPatterns) {
        this(
                enabled,
                repository,
                remoteName,
                remoteUrl,
                triggers,
                lfsPatterns,
                DestinationHealth.notChecked(DestinationType.GIT));
    }

    /** Compatibility constructor for callers that predate per-destination settings. */
    public GitDestinationConfig(
            boolean enabled,
            Optional<Path> repository,
            String remoteName,
            Optional<String> remoteUrl) {
        this(enabled, repository, remoteName, remoteUrl, DestinationTriggerConfig.defaults(), DEFAULT_LFS_PATTERNS);
    }

    public static GitDestinationConfig defaults() {
        return new GitDestinationConfig(
                true,
                Optional.empty(),
                DEFAULT_REMOTE_NAME,
                Optional.empty(),
                DestinationTriggerConfig.defaults(),
                DEFAULT_LFS_PATTERNS,
                DestinationHealth.notChecked(DestinationType.GIT));
    }

    private static List<String> validatePatterns(List<String> patterns) {
        Objects.requireNonNull(patterns, "lfsPatterns");
        if (patterns.isEmpty() || patterns.size() > 128) {
            throw new IllegalArgumentException("At least one and no more than 128 LFS patterns are required");
        }
        Set<String> unique = new LinkedHashSet<>();
        for (String pattern : patterns) {
            Objects.requireNonNull(pattern, "lfsPattern");
            if (pattern.isBlank()
                    || pattern.length() > 256
                    || pattern.startsWith("!")
                    || pattern.startsWith("-")
                    || pattern.contains("\\")
                    || pattern.contains("..")
                    || pattern.chars().anyMatch(Character::isWhitespace)
                    || pattern.chars().anyMatch(character -> Character.isISOControl(character))) {
                throw new IllegalArgumentException("Unsafe Git LFS pattern: " + pattern);
            }
            if (!unique.add(pattern)) {
                throw new IllegalArgumentException("Duplicate Git LFS pattern: " + pattern);
            }
        }
        return List.copyOf(unique);
    }
}
