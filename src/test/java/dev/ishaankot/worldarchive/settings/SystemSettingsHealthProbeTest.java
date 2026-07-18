package dev.ishaankot.worldarchive.settings;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.ishaankot.worldarchive.storage.git.GitCommandResult;
import dev.ishaankot.worldarchive.storage.git.GitCommandRunner;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SystemSettingsHealthProbeTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void reportsToolsAndDestinationFoldersIndependently() throws Exception {
        GitCommandRunner runner = command -> command.arguments().contains("lfs")
                ? result(0, "git-lfs/3.7.0")
                : result(0, "git version 2.50.1");
        SystemSettingsHealthProbe probe = new SystemSettingsHealthProbe(
                temporaryDirectory,
                runner);
        SettingsProbeRequest request = new SettingsProbeRequest(
                true,
                "git",
                Optional.of(temporaryDirectory.resolve("repository.git")),
                true,
                true,
                Optional.of(temporaryDirectory.resolve("archives")));

        SettingsHealthSnapshot health = probe.probe(request);

        assertEquals(SettingsHealthStatus.HEALTHY, health.gitTool().status());
        assertEquals(SettingsHealthStatus.HEALTHY, health.lfsTool().status());
        assertEquals(SettingsHealthStatus.HEALTHY, health.repository().status());
        assertEquals(SettingsHealthStatus.HEALTHY, health.remote().status());
        assertEquals(SettingsHealthStatus.HEALTHY, health.zipDirectory().status());
    }

    @Test
    void missingLfsDoesNotHideAHealthyGitExecutableOrZipFolder() throws Exception {
        GitCommandRunner runner = command -> command.arguments().contains("lfs")
                ? result(1, "Git LFS is not installed")
                : result(0, "git version 2.50.1");
        SettingsHealthSnapshot health = new SystemSettingsHealthProbe(
                        temporaryDirectory,
                        runner)
                .probe(new SettingsProbeRequest(
                        true,
                        "git",
                        Optional.of(temporaryDirectory.resolve("repository.git")),
                        false,
                        true,
                        Optional.of(temporaryDirectory.resolve("archives"))));

        assertEquals(SettingsHealthStatus.HEALTHY, health.gitTool().status());
        assertEquals(SettingsHealthStatus.TOOL_MISSING, health.lfsTool().status());
        assertEquals(SettingsHealthStatus.HEALTHY, health.repository().status());
        assertEquals(SettingsHealthStatus.UNCONFIGURED, health.remote().status());
        assertEquals(SettingsHealthStatus.HEALTHY, health.zipDirectory().status());
    }

    @Test
    void disabledDestinationsDoNotStartGitProcesses() throws Exception {
        AtomicInteger invocations = new AtomicInteger();
        GitCommandRunner runner = command -> {
            invocations.incrementAndGet();
            return result(0, "unexpected");
        };

        SettingsHealthSnapshot health = new SystemSettingsHealthProbe(
                        temporaryDirectory,
                        runner)
                .probe(new SettingsProbeRequest(
                        false,
                        "git",
                        Optional.empty(),
                        false,
                        false,
                        Optional.empty()));

        assertEquals(0, invocations.get());
        assertEquals(SettingsHealthStatus.DISABLED, health.gitTool().status());
        assertEquals(SettingsHealthStatus.DISABLED, health.zipDirectory().status());
    }

    private static GitCommandResult result(int exitCode, String output) {
        return new GitCommandResult(exitCode, output, "", false, false);
    }
}
