package dev.ishaanko.worldarchive.config;

import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Settings of the Git destination: one repository per world inside {@code repository}, each
 * pushed to its world's own remote. An empty repository means the default folder
 * ({@link DefaultDestinations}).
 */
public record GitDestinationConfig(
        boolean enabled,
        Optional<Path> repository,
        String remoteName,
        DestinationTriggerConfig triggers,
        List<String> lfsPatterns) {
    public static final String DEFAULT_REMOTE_NAME = "origin";

    public static final List<String> DEFAULT_LFS_PATTERNS = List.of(
            "*.mca",
            "*.mcr",
            "*.dat",
            "*.dat_old",
            "*.nbt",
            "*.zip");

    private static final Pattern REMOTE_NAME = Pattern.compile("[A-Za-z0-9._-]{1,64}");

    private static final int MAXIMUM_LFS_PATTERNS = 128;

    private static final int MAXIMUM_LFS_PATTERN_LENGTH = 256;

    public GitDestinationConfig {
        repository = Objects.requireNonNull(repository, "repository")
                .map(path -> path.toAbsolutePath().normalize());
        validateRemoteName(remoteName);
        Objects.requireNonNull(triggers, "triggers");
        lfsPatterns = validateLfsPatterns(lfsPatterns);
    }

    public static GitDestinationConfig defaults() {
        return new GitDestinationConfig(
                true,
                Optional.empty(),
                DEFAULT_REMOTE_NAME,
                DestinationTriggerConfig.defaults(),
                DEFAULT_LFS_PATTERNS);
    }

    public GitDestinationConfig withRepository(Optional<Path> repository) {
        return new GitDestinationConfig(enabled, repository, remoteName, triggers, lfsPatterns);
    }

    /**
     * Checks a name for the remote inside each world's repository.
     *
     * @return the name, unchanged
     * @throws IllegalArgumentException when Git could read the name as an option or a path
     */
    public static String validateRemoteName(String remoteName) {
        Objects.requireNonNull(remoteName, "remoteName");
        if (!REMOTE_NAME.matcher(remoteName).matches()
                || remoteName.startsWith("-")
                || remoteName.equals(".")
                || remoteName.equals("..")) {
            throw new IllegalArgumentException("Git remote name is unsafe");
        }
        return remoteName;
    }

    /**
     * Checks the Git attribute patterns whose files are stored with Git LFS.
     *
     * @return the patterns in order, as an immutable list
     * @throws IllegalArgumentException naming the first unsafe or repeated pattern
     */
    public static List<String> validateLfsPatterns(List<String> patterns) {
        Objects.requireNonNull(patterns, "lfsPatterns");
        if (patterns.isEmpty() || patterns.size() > MAXIMUM_LFS_PATTERNS) {
            throw new IllegalArgumentException("At least one and no more than 128 LFS patterns are required");
        }
        Set<String> unique = new LinkedHashSet<>();
        for (String pattern : patterns) {
            if (isUnsafeLfsPattern(Objects.requireNonNull(pattern, "lfsPattern"))) {
                throw new IllegalArgumentException("Unsafe Git LFS pattern: " + pattern);
            }
            if (!unique.add(pattern)) {
                throw new IllegalArgumentException("Duplicate Git LFS pattern: " + pattern);
            }
        }
        return List.copyOf(unique);
    }

    /** A pattern Git could read as a negation or an option, or one that leaves the world folder. */
    private static boolean isUnsafeLfsPattern(String pattern) {
        return pattern.isBlank()
                || pattern.length() > MAXIMUM_LFS_PATTERN_LENGTH
                || pattern.startsWith("!")
                || pattern.startsWith("-")
                || pattern.contains("\\")
                || pattern.contains("..")
                || pattern.chars().anyMatch(character ->
                        Character.isWhitespace(character) || Character.isISOControl(character));
    }
}
