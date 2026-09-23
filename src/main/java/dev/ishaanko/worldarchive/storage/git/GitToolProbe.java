package dev.ishaanko.worldarchive.storage.git;

import dev.ishaanko.worldarchive.model.SafeText;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Checks Git and Git LFS independently, so a partial installation is never reported healthy.
 * Each check may stay silent no longer than the settings' idle limit.
 */
public final class GitToolProbe {
    /** The oldest Git whose options every command here uses ({@code --no-write-fetch-head}, {@code --object-format}). */
    static final int[] MINIMUM_GIT_VERSION = {2, 29};

    private static final Pattern GIT_VERSION = Pattern.compile("git version (\\d{1,6})\\.(\\d{1,6})\\b");

    private final GitBackendSettings settings;

    private final GitCommandRunner runner;

    public GitToolProbe(GitBackendSettings settings, GitCommandRunner runner) {
        this.settings = Objects.requireNonNull(settings, "settings");
        this.runner = Objects.requireNonNull(runner, "runner");
    }

    public GitToolHealth probe() throws InterruptedException {
        Result git = requireSupportedVersion(run(List.of(settings.executable(), "--version")));
        Result lfs = run(List.of(settings.executable(), "lfs", "version"));
        return new GitToolHealth(git.version(), lfs.version(), git.failure(), lfs.failure());
    }

    private Result run(List<String> arguments) throws InterruptedException {
        Path workingDirectory = settings.repository().getParent();
        if (workingDirectory == null || !Files.isDirectory(workingDirectory)) {
            workingDirectory = Path.of("").toAbsolutePath();
        }
        GitCommand command = new GitCommand(arguments, workingDirectory, Map.of(), GitCommand.Input.NONE,
                Optional.of(settings.commandTimeout()), settings.maximumOutputBytes());
        try {
            GitCommandResult result = runner.run(command);
            String output = result.standardOutput().isBlank() ? result.standardError() : result.standardOutput();
            String text = SafeText.of(output, result.successful() ? "version reported" : "the check failed", 512);
            return result.successful() ? Result.available(text) : Result.failed(text);
        } catch (IOException exception) {
            return Result.failed(SafeText.from(exception, "the tool could not be started", 512));
        }
    }

    /**
     * An old Git fails mid-backup on an unknown option; report it up front instead. Real Git
     * always prints "git version X.Y", so output that does not is not a Git this mod can trust.
     */
    private static Result requireSupportedVersion(Result git) {
        if (git.version().isEmpty()) {
            return git;
        }
        String reported = git.version().get();
        Matcher matcher = GIT_VERSION.matcher(reported);
        if (!matcher.find()) {
            return Result.failed("Git version could not be read from: " + reported);
        }
        int major = Integer.parseInt(matcher.group(1));
        int minor = Integer.parseInt(matcher.group(2));
        if (major > MINIMUM_GIT_VERSION[0] || major == MINIMUM_GIT_VERSION[0] && minor >= MINIMUM_GIT_VERSION[1]) {
            return git;
        }
        return Result.failed("Git " + MINIMUM_GIT_VERSION[0] + "." + MINIMUM_GIT_VERSION[1]
                + " or newer is required (found " + major + "." + minor + ")");
    }

    /** One tool's version or the reason it cannot be used. */
    private record Result(Optional<String> version, Optional<String> failure) {
        static Result available(String version) {
            return new Result(Optional.of(version), Optional.empty());
        }

        static Result failed(String failure) {
            return new Result(Optional.empty(), Optional.of(failure));
        }
    }
}
