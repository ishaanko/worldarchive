package dev.ishaanko.worldarchive.storage.git;

import dev.ishaanko.worldarchive.config.FolderOrigin;
import dev.ishaanko.worldarchive.config.GitDestinationConfig;
import dev.ishaanko.worldarchive.config.RemoteUrlPolicy;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Git storage settings with every default resolved.
 *
 * @param enabled whether Git backups run at all
 * @param repository the folder that holds one bare repository per world, or for a single
 *        repository (a world's, an import preview's) that repository itself
 * @param folderOrigin who chose the folder that holds the repositories: WorldArchive's default
 *        folder is created when a backup needs it, and a folder the player chose never is
 * @param executable the Git program, normally {@code git}
 * @param remoteName the name the remote gets in each repository's configuration
 * @param remoteUrl the remote of a single repository; the store's own settings have none,
 *        because each world's remote is passed to the store separately
 * @param lfsPatterns the Git attribute patterns whose files are stored as Git LFS objects
 * @param commandTimeout how long a network command or a tool check may run without printing
 *        anything before it is stopped; local commands have no limit because Cancel stops them
 * @param maximumOutputBytes the most standard output or error kept from one command
 */
public record GitBackendSettings(
        boolean enabled,
        Path repository,
        FolderOrigin folderOrigin,
        String executable,
        String remoteName,
        Optional<String> remoteUrl,
        List<String> lfsPatterns,
        Duration commandTimeout,
        int maximumOutputBytes) {
    public static final List<String> DEFAULT_LFS_PATTERNS = GitDestinationConfig.DEFAULT_LFS_PATTERNS;

    public static final Duration DEFAULT_COMMAND_TIMEOUT = Duration.ofMinutes(5);

    public static final int DEFAULT_MAXIMUM_OUTPUT_BYTES = 8 * 1_024 * 1_024;

    private static final Pattern REMOTE_NAME = Pattern.compile("[A-Za-z0-9._][A-Za-z0-9._-]{0,63}");

    public GitBackendSettings {
        repository = Objects.requireNonNull(repository, "repository").toAbsolutePath().normalize();
        Objects.requireNonNull(folderOrigin, "folderOrigin");
        Objects.requireNonNull(executable, "executable");
        if (executable.isBlank() || executable.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("Git executable is blank or contains control characters");
        }
        Objects.requireNonNull(remoteName, "remoteName");
        if (!REMOTE_NAME.matcher(remoteName).matches() || remoteName.equals(".") || remoteName.equals("..")) {
            throw new IllegalArgumentException("Git remote name is unsafe");
        }
        remoteUrl = Objects.requireNonNull(remoteUrl, "remoteUrl").map(RemoteUrlPolicy::validatePlain);
        lfsPatterns = GitSnapshotManifest.validatePatterns(lfsPatterns);
        Objects.requireNonNull(commandTimeout, "commandTimeout");
        if (commandTimeout.isZero() || commandTimeout.isNegative()) {
            throw new IllegalArgumentException("Git command timeout must be positive");
        }
        if (maximumOutputBytes < 1_024 || maximumOutputBytes > 64 * 1_024 * 1_024) {
            throw new IllegalArgumentException("Git output limit must be between 1 KiB and 64 MiB");
        }
    }

    /**
     * The store settings for a configuration whose Git folder is filled in, as
     * {@link dev.ishaanko.worldarchive.config.DefaultDestinations#resolve} does. They name no
     * remote: each world's remote comes from that world's settings and is given to the store separately.
     */
    public static GitBackendSettings from(GitDestinationConfig config, FolderOrigin folderOrigin) {
        Objects.requireNonNull(config, "config");
        return new GitBackendSettings(
                config.enabled(),
                config.repository().orElseThrow(() -> new IllegalArgumentException("The Git folder is not set")),
                folderOrigin,
                "git",
                config.remoteName(),
                Optional.empty(),
                config.lfsPatterns(),
                DEFAULT_COMMAND_TIMEOUT,
                DEFAULT_MAXIMUM_OUTPUT_BYTES);
    }

    /** The same settings for one repository and its remote. */
    GitBackendSettings withRepository(Path singleRepository, Optional<String> singleRemoteUrl) {
        return new GitBackendSettings(
                enabled,
                singleRepository,
                folderOrigin,
                executable,
                remoteName,
                singleRemoteUrl,
                lfsPatterns,
                commandTimeout,
                maximumOutputBytes);
    }
}
