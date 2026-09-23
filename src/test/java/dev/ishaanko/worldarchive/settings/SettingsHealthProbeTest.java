package dev.ishaanko.worldarchive.settings;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.ishaanko.worldarchive.storage.git.GitCommandResult;
import dev.ishaanko.worldarchive.storage.git.GitCommandRunner;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SettingsHealthProbeTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void missingLfsDoesNotHideAHealthyGitOrFolderAndToolsAreStartedOnce() throws Exception {
        AtomicInteger started = new AtomicInteger();
        GitCommandRunner runner = command -> {
            started.incrementAndGet();
            return command.arguments().contains("lfs")
                    ? result(1, "Git LFS is not installed")
                    : result(0, "git version 2.50.1");
        };
        SettingsHealthProbe probe = new SettingsHealthProbe(temporaryDirectory, runner);
        SettingsProbeRequest request = new SettingsProbeRequest(
                true,
                Optional.of(temporaryDirectory.resolve("repository.git")),
                true,
                Optional.of(temporaryDirectory.resolve("archives")));

        SettingsHealthSnapshot health = probe.probe(request);
        probe.probe(request);

        assertEquals(SettingsHealthStatus.HEALTHY, health.gitTool().status());
        assertEquals(SettingsHealthStatus.TOOL_MISSING, health.lfsTool().status());
        assertEquals(SettingsHealthStatus.HEALTHY, health.repository().status());
        assertEquals(SettingsHealthStatus.HEALTHY, health.zipFolder().status());
        assertEquals(2, started.get());
    }

    @Test
    void disabledDestinationsDoNotStartGitProcesses() throws Exception {
        AtomicInteger started = new AtomicInteger();
        GitCommandRunner runner = command -> {
            started.incrementAndGet();
            return result(0, "unexpected");
        };

        SettingsHealthSnapshot health = new SettingsHealthProbe(temporaryDirectory, runner)
                .probe(new SettingsProbeRequest(false, Optional.empty(), false, Optional.empty()));

        assertEquals(0, started.get());
        assertEquals(SettingsHealthStatus.DISABLED, health.gitTool().status());
        assertEquals(SettingsHealthStatus.DISABLED, health.zipFolder().status());
    }

    private static GitCommandResult result(int exitCode, String output) {
        return new GitCommandResult(exitCode, output, "", false, false);
    }
}
