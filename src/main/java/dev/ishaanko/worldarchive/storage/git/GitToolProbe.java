package dev.ishaanko.worldarchive.storage.git;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Probes Git and Git LFS independently so partial installations are never reported healthy. */
public final class GitToolProbe {
    private final GitBackendSettings settings;

    private final GitCommandRunner runner;

    public GitToolProbe(GitBackendSettings settings, GitCommandRunner runner) {
        this.settings = Objects.requireNonNull(settings, "settings");
        this.runner = Objects.requireNonNull(runner, "runner");
    }

    /** The oldest Git whose flags every command here uses ({@code --no-write-fetch-head}, {@code --object-format}). */
    static final int[] MINIMUM_GIT_VERSION = {2, 29};

    private static final Pattern GIT_VERSION = Pattern.compile("git version (\\d+)\\.(\\d+)(?:\\.(\\d+))?");

    public GitToolHealth probe() throws InterruptedException {
        ProbeResult git = requireSupportedVersion(run(List.of(settings.executable(), "--version")));
        ProbeResult lfs = run(List.of(settings.executable(), "lfs", "version"));
        return new GitToolHealth(
                git.available(),
                lfs.available(),
                git.version(),
                lfs.version(),
                git.failure(),
                lfs.failure());
    }

    private ProbeResult run(List<String> arguments) throws InterruptedException {
        Path workingDirectory = settings.repository().getParent();
        if (workingDirectory == null || !Files.isDirectory(workingDirectory)) {
            workingDirectory = Path.of("").toAbsolutePath().normalize();
        }
        GitCommand command = GitCommand.of(
                arguments,
                workingDirectory,
                settings.commandTimeout(),
                settings.maximumOutputBytes());
        try {
            GitCommandResult result = runner.run(command);
            if (!result.successful()) {
                return ProbeResult.failure(safeMessage(result));
            }
            String version = firstNonBlank(result.standardOutput(), result.standardError());
            return ProbeResult.available(version);
        } catch (IOException exception) {
            return ProbeResult.failure("Tool process could not be started or completed");
        }
    }

    /**
     * An old Git fails mid-backup on an unknown flag; report it up front instead. Real Git
     * always prints "git version X.Y", so output that does not is not a Git this mod can trust.
     */
    private static ProbeResult requireSupportedVersion(ProbeResult git) {
        if (!git.available()) {
            return git;
        }
        String reported = git.version().orElseThrow();
        Matcher matcher = GIT_VERSION.matcher(reported);
        if (!matcher.find()) {
            return ProbeResult.failure("Git version could not be read from: " + reported);
        }
        int major = Integer.parseInt(matcher.group(1));
        int minor = Integer.parseInt(matcher.group(2));
        if (major > MINIMUM_GIT_VERSION[0]
                || major == MINIMUM_GIT_VERSION[0] && minor >= MINIMUM_GIT_VERSION[1]) {
            return git;
        }
        return ProbeResult.failure("Git " + MINIMUM_GIT_VERSION[0] + "." + MINIMUM_GIT_VERSION[1]
                + " or newer is required (found " + major + "." + minor + ")");
    }

    private static String firstNonBlank(String first, String second) {
        String value = first.isBlank() ? second : first;
        value = value.replaceAll("\\p{Cntrl}+", " ").trim();
        return value.isEmpty() ? "version reported" : value;
    }

    private static String safeMessage(GitCommandResult result) {
        String value = SystemGitCommandRunner.redactPatterns(
                firstNonBlank(result.standardError(), result.standardOutput()));
        return value.length() > 512 ? value.substring(0, 512) : value;
    }

    private record ProbeResult(boolean available, Optional<String> version, Optional<String> failure) {
        private static ProbeResult available(String version) {
            return new ProbeResult(true, Optional.of(version), Optional.empty());
        }

        private static ProbeResult failure(String failure) {
            return new ProbeResult(false, Optional.empty(), Optional.of(failure));
        }
    }
}
