package dev.ishaanko.worldarchive.settings;

import dev.ishaanko.worldarchive.config.FolderOrigin;
import dev.ishaanko.worldarchive.config.GitDestinationConfig;
import dev.ishaanko.worldarchive.config.PathSafety;
import dev.ishaanko.worldarchive.storage.git.GitBackendSettings;
import dev.ishaanko.worldarchive.storage.git.GitCommandRunner;
import dev.ishaanko.worldarchive.storage.git.GitToolHealth;
import dev.ishaanko.worldarchive.storage.git.GitToolProbe;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * Checks what the settings footer shows: whether Git and Git LFS run, and whether the
 * repository and ZIP folders can be used. A folder that is offline or not writable is shown as
 * unavailable, as a warning; it does not stop the settings from being saved. Git and Git LFS
 * are started once per probe, and each settings screen uses its own probe, so reopening the
 * screen checks them again. Blocking: call it off the render thread.
 */
public final class SettingsHealthProbe {
    private static final Duration TOOL_TIMEOUT = Duration.ofSeconds(10);

    private static final int OUTPUT_LIMIT = 64 * 1_024;

    private final Path workingDirectory;

    private final GitCommandRunner gitRunner;

    private volatile GitToolHealth tools;

    public SettingsHealthProbe(Path workingDirectory, GitCommandRunner gitRunner) {
        this.workingDirectory = Objects.requireNonNull(workingDirectory, "workingDirectory")
                .toAbsolutePath()
                .normalize();
        this.gitRunner = Objects.requireNonNull(gitRunner, "gitRunner");
    }

    public SettingsHealthSnapshot probe(SettingsProbeRequest request) throws InterruptedException {
        Objects.requireNonNull(request, "request");
        checkInterrupted();
        SettingsHealthItem gitTool = SettingsHealthItem.disabled();
        SettingsHealthItem lfsTool = SettingsHealthItem.disabled();
        SettingsHealthItem repository = SettingsHealthItem.disabled();
        if (request.gitEnabled()) {
            GitToolHealth health = tools();
            gitTool = toolItem(health.gitVersion(), health.gitFailure(), "Git");
            lfsTool = toolItem(health.lfsVersion(), health.lfsFailure(), "Git LFS");
            repository = folderItem(request.gitRepository(), "repository");
        }
        SettingsHealthItem zip = request.zipEnabled()
                ? folderItem(request.zipFolder(), "archive folder")
                : SettingsHealthItem.disabled();
        checkInterrupted();
        return new SettingsHealthSnapshot(gitTool, lfsTool, repository, zip);
    }

    /** The tools do not change while a screen is open, so they are started only for the first probe. */
    private GitToolHealth tools() throws InterruptedException {
        GitToolHealth checked = tools;
        if (checked == null) {
            GitBackendSettings settings = new GitBackendSettings(
                    true,
                    workingDirectory.resolve("worldarchive-health-probe.git"),
                    FolderOrigin.DEFAULT,
                    "git",
                    GitDestinationConfig.DEFAULT_REMOTE_NAME,
                    Optional.empty(),
                    GitDestinationConfig.DEFAULT_LFS_PATTERNS,
                    TOOL_TIMEOUT,
                    OUTPUT_LIMIT);
            checked = new GitToolProbe(settings, gitRunner).probe();
            tools = checked;
        }
        return checked;
    }

    private static SettingsHealthItem toolItem(Optional<String> version, Optional<String> failure, String label) {
        if (version.isPresent()) {
            return new SettingsHealthItem(SettingsHealthStatus.HEALTHY, version.get());
        }
        String message = failure.filter(value -> !value.isBlank()).orElse(label + " is not installed");
        return new SettingsHealthItem(SettingsHealthStatus.TOOL_MISSING, message);
    }

    /**
     * A folder is usable when it, or the nearest folder above it that exists, can be read and
     * written. Free space is shown when the file system reports it; network and FUSE drives
     * often report none, so none is not an error.
     */
    private static SettingsHealthItem folderItem(Optional<Path> folder, String label) throws InterruptedException {
        if (folder.isEmpty()) {
            return SettingsHealthItem.unconfigured();
        }
        checkInterrupted();
        Optional<Path> existing = PathSafety.nearestExisting(folder.get());
        try {
            if (existing.isEmpty()) {
                return unavailable(label + " has no accessible parent folder");
            }
            Path directory = existing.get();
            if (!Files.isDirectory(directory)) {
                return unavailable(label + " or its nearest existing parent is not a folder");
            }
            if (!Files.isReadable(directory)) {
                return unavailable(label + " is not readable");
            }
            if (!Files.isWritable(directory)) {
                return unavailable(label + " is not writable");
            }
            long usableBytes = Files.getFileStore(directory).getUsableSpace();
            return new SettingsHealthItem(SettingsHealthStatus.HEALTHY, usableBytes > 0
                    ? label + " is ready (" + formatAvailableSpace(usableBytes) + " available)"
                    : label + " is ready");
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
