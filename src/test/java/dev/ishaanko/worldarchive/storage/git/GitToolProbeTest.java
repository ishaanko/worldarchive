package dev.ishaanko.worldarchive.storage.git;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class GitToolProbeTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void reportsAnOldGitAsUnavailableWithTheVersionFound() throws Exception {
        GitToolHealth old = probe("git version 2.20.1.windows.1");
        GitToolHealth current = probe("git version 2.53.0");
        GitToolHealth unparsed = probe("git version test");

        assertFalse(old.gitAvailable());
        assertTrue(old.gitFailure().orElseThrow().contains("found 2.20"));
        assertTrue(old.lfsAvailable());
        assertTrue(current.gitAvailable());
        assertTrue(unparsed.gitAvailable());
    }

    private GitToolHealth probe(String gitVersion) throws InterruptedException {
        GitCommandRunner runner = command -> new GitCommandResult(
                0,
                command.arguments().contains("lfs") ? "git-lfs/3.7.1" : gitVersion,
                "",
                false,
                false);
        GitBackendSettings settings = new GitBackendSettings(
                true,
                temporaryDirectory.resolve("repository.git"),
                "git",
                "origin",
                Optional.empty(),
                GitBackendSettings.DEFAULT_LFS_PATTERNS,
                Duration.ofSeconds(5),
                1_024 * 1_024);
        return new GitToolProbe(settings, runner).probe();
    }
}
