package dev.ishaankot.worldarchive.settings;

import dev.ishaankot.worldarchive.config.GitDestinationConfig;
import dev.ishaankot.worldarchive.storage.git.GitBackendSettings;
import dev.ishaankot.worldarchive.storage.git.GitCommandRunner;
import dev.ishaankot.worldarchive.storage.git.GitToolHealth;
import dev.ishaankot.worldarchive.storage.git.GitToolProbe;
import dev.ishaankot.worldarchive.storage.git.SystemGitCommandRunner;
import java.io.IOException;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/** Concrete Git, Git LFS, and filesystem readiness probe for client settings. */
public final class SystemSettingsHealthProbe implements SettingsHealthProbe {
    private static final Duration TOOL_TIMEOUT = Duration.ofSeconds(10);

    private static final int OUTPUT_LIMIT = 64 * 1_024;

    private final Path workingDirectory;

    private final GitCommandRunner gitRunner;

    public SystemSettingsHealthProbe(Path workingDirectory) {
        this(workingDirectory, new SystemGitCommandRunner());
    }

    SystemSettingsHealthProbe(Path workingDirectory, GitCommandRunner gitRunner) {
        this.workingDirectory = Objects.requireNonNull(workingDirectory, "workingDirectory")
                .toAbsolutePath()
                .normalize();
        this.gitRunner = Objects.requireNonNull(gitRunner, "gitRunner");
    }

    @Override
    public SettingsHealthSnapshot probe(SettingsProbeRequest request) throws InterruptedException {
        Objects.requireNonNull(request, "request");
        checkInterrupted();
        SettingsHealthItem gitTool = SettingsHealthItem.disabled();
        SettingsHealthItem lfsTool = SettingsHealthItem.disabled();
        SettingsHealthItem repository = SettingsHealthItem.disabled();
        SettingsHealthItem remote = SettingsHealthItem.disabled();
        if (request.gitEnabled()) {
            GitToolHealth tools = probeGitTools(request);
            gitTool = toolItem(
                    tools.gitAvailable(),
                    tools.gitVersion(),
                    tools.gitFailure(),
                    "Git");
            lfsTool = toolItem(
                    tools.lfsAvailable(),
                    tools.lfsVersion(),
                    tools.lfsFailure(),
                    "Git LFS");
            repository = probeDirectory(request.gitRepository(), "repository");
            remote = request.remoteConfigured()
                    ? new SettingsHealthItem(
                            SettingsHealthStatus.HEALTHY,
                            "configured; connectivity is checked during sync")
                    : new SettingsHealthItem(SettingsHealthStatus.UNCONFIGURED, "not configured");
        }

        SettingsHealthItem zip = request.zipEnabled()
                ? probeDirectory(request.zipDirectory(), "archive folder")
                : SettingsHealthItem.disabled();
        checkInterrupted();
        return new SettingsHealthSnapshot(gitTool, lfsTool, repository, remote, zip);
    }

    private GitToolHealth probeGitTools(SettingsProbeRequest request) throws InterruptedException {
        Path repository = request.gitRepository()
                .orElseGet(() -> workingDirectory.resolve("worldarchive-health-probe.git"));
        GitBackendSettings settings = new GitBackendSettings(
                true,
                repository,
                request.gitExecutable(),
                GitDestinationConfig.DEFAULT_REMOTE_NAME,
                Optional.empty(),
                GitDestinationConfig.DEFAULT_LFS_PATTERNS,
                TOOL_TIMEOUT,
                OUTPUT_LIMIT);
        return new GitToolProbe(settings, gitRunner).probe();
    }

    private static SettingsHealthItem toolItem(
            boolean available,
            Optional<String> version,
            Optional<String> failure,
            String label) {
        if (available) {
            return new SettingsHealthItem(
                    SettingsHealthStatus.HEALTHY,
                    version.orElse(label + " is ready"));
        }
        String message = failure.filter(value -> !value.isBlank()).orElse(label + " is not installed");
        return new SettingsHealthItem(SettingsHealthStatus.TOOL_MISSING, message);
    }

    private static SettingsHealthItem probeDirectory(
            Optional<Path> configuredPath,
            String label) throws InterruptedException {
        if (configuredPath.isEmpty()) {
            return new SettingsHealthItem(SettingsHealthStatus.UNCONFIGURED, "not configured");
        }
        checkInterrupted();
        Path destination = configuredPath.orElseThrow().toAbsolutePath().normalize();
        try {
            Path existing = destination;
            while (existing != null && !Files.exists(existing, LinkOption.NOFOLLOW_LINKS)) {
                existing = existing.getParent();
            }
            if (existing == null) {
                return unavailable(label + " has no accessible parent folder");
            }
            if (Files.isSymbolicLink(existing)) {
                return unavailable(label + " uses a symbolic-link path component");
            }
            Path realExisting = existing.toRealPath();
            if (!Files.isDirectory(realExisting, LinkOption.NOFOLLOW_LINKS)) {
                return unavailable(label + " or its nearest existing parent is not a folder");
            }
            if (!Files.isReadable(realExisting)) {
                return unavailable(label + " is not readable");
            }
            if (!Files.isWritable(realExisting)) {
                return unavailable(label + " is not writable");
            }
            FileStore fileStore = Files.getFileStore(realExisting);
            long usableBytes = fileStore.getUsableSpace();
            if (usableBytes <= 0) {
                return unavailable(label + " has no usable space");
            }
            return new SettingsHealthItem(
                    SettingsHealthStatus.HEALTHY,
                    label + " is ready (" + formatAvailableSpace(usableBytes) + " available)");
        } catch (IOException | SecurityException exception) {
            return unavailable(label + " is unavailable");
        }
    }

    private static SettingsHealthItem unavailable(String message) {
        return new SettingsHealthItem(SettingsHealthStatus.UNAVAILABLE, message);
    }

    private static String formatAvailableSpace(long bytes) {
        double gibibytes = bytes / (1024.0 * 1024.0 * 1024.0);
        if (gibibytes >= 0.1) {
            return String.format(Locale.ROOT, "%.1f GiB", gibibytes);
        }
        double mebibytes = bytes / (1024.0 * 1024.0);
        return String.format(Locale.ROOT, "%.1f MiB", mebibytes);
    }

    private static void checkInterrupted() throws InterruptedException {
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedException("Settings health probe was cancelled");
        }
    }
}
