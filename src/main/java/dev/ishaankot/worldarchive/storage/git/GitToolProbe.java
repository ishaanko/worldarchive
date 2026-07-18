package dev.ishaankot.worldarchive.storage.git;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Probes Git and Git LFS independently so partial installations are never reported healthy. */
public final class GitToolProbe {
    private final GitBackendSettings settings;

    private final GitCommandRunner runner;

    public GitToolProbe(GitBackendSettings settings, GitCommandRunner runner) {
        this.settings = Objects.requireNonNull(settings, "settings");
        this.runner = Objects.requireNonNull(runner, "runner");
    }

    public GitToolHealth probe() throws InterruptedException {
        ProbeResult git = run(List.of(settings.executable(), "--version"));
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

    private static String firstNonBlank(String first, String second) {
        String value = first.isBlank() ? second : first;
        value = value.replaceAll("\\p{Cntrl}+", " ").trim();
        return value.isEmpty() ? "version reported" : value;
    }

    private static String safeMessage(GitCommandResult result) {
        String value = firstNonBlank(result.standardError(), result.standardOutput());
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
