package dev.ishaanko.worldarchive.storage.git;

import dev.ishaanko.worldarchive.config.FolderOrigin;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The tool check reports Git and Git LFS separately and refuses a Git that is too old. */
final class GitToolProbeTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void reportsAnOldOrUnreadableGitAsUnavailable() throws Exception {
        GitToolHealth old = probe(versions("git version 2.20.1.windows.1"));
        GitToolHealth current = probe(versions("git version 2.53.0"));
        GitToolHealth unparsed = probe(versions("git version test"));

        assertFalse(old.gitAvailable());
        assertTrue(old.gitFailure().orElseThrow().contains("found 2.20"));
        assertTrue(old.lfsAvailable());
        assertTrue(current.available());
        assertFalse(unparsed.gitAvailable());
        assertTrue(unparsed.gitFailure().orElseThrow().contains("git version test"));
    }

    @Test
    void checksGitAndLfsIndependently() throws Exception {
        GitToolHealth lfsMissing = probe(command -> command.arguments().contains("lfs")
                ? new GitCommandResult(1, "", "git: 'lfs' is not a git command", false, false)
                : new GitCommandResult(0, "git version 2.53.0", "", false, false));
        AtomicInteger starts = new AtomicInteger();
        GitToolHealth gitMissing = probe(command -> {
            starts.incrementAndGet();
            throw new IOException("Git could not be started");
        });

        assertTrue(lfsMissing.gitAvailable());
        assertFalse(lfsMissing.lfsAvailable());
        assertTrue(lfsMissing.lfsFailure().orElseThrow().contains("not a git command"));
        assertFalse(gitMissing.gitAvailable());
        assertFalse(gitMissing.lfsAvailable());
        assertEquals(2, starts.get(), "LFS is still checked when Git itself cannot start");
    }

    private static GitCommandRunner versions(String gitVersion) {
        return command -> new GitCommandResult(
                0, command.arguments().contains("lfs") ? "git-lfs/3.7.1" : gitVersion, "", false, false);
    }

    private GitToolHealth probe(GitCommandRunner runner) throws InterruptedException {
        GitBackendSettings settings = new GitBackendSettings(
                true,
                temporaryDirectory.resolve("repository.git"),
                FolderOrigin.DEFAULT,
                "git",
                "origin",
                Optional.empty(),
                GitBackendSettings.DEFAULT_LFS_PATTERNS,
                Duration.ofSeconds(5),
                1_024 * 1_024);
        return new GitToolProbe(settings, runner).probe();
    }
}
