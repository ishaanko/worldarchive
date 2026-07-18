package dev.ishaankot.worldarchive.storage.git;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SystemGitCommandRunnerTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void boundsAndRedactsProcessOutput() throws Exception {
        String secret = "https://alice:hunter2@example.invalid/repository?token=visible";
        GitCommand command = command(List.of("output", secret, "200"), Duration.ofSeconds(10), 1_024, Set.of(secret));

        GitCommandResult result = new SystemGitCommandRunner().run(command);

        assertTrue(result.successful());
        assertTrue(result.standardOutputTruncated());
        assertTrue(result.standardOutput().length() <= 1_024);
        assertFalse(result.standardOutput().contains("hunter2"));
        assertFalse(result.standardOutput().contains("visible"));
        byte[] completeOutput = secret.repeat(200).getBytes(StandardCharsets.UTF_8);
        assertEquals(completeOutput.length, result.standardOutputBytes());
        assertEquals(
                HexFormat.of().formatHex(GitInventory.sha256().digest(completeOutput)),
                result.standardOutputSha256());
    }

    @Test
    void redactsAuthorizationHeadersKnownTokensJwtAndNamedCredentials() {
        String github = "ghp_ABCDEFGHIJKLMNOPQRSTUVWXYZ123456";
        String jwt = "eyJabcdefghijk.abcdefghijklmnop.abcdefghijklmnop";
        String output = "Authorization: Bearer ABCDEFGHIJKLMNOP "
                + github + " " + jwt + " api_key=visible credential=visible refresh_token=visible";

        String redacted = SystemGitCommandRunner.redact(output, Set.of());

        assertFalse(redacted.contains("ABCDEFGHIJKLMNOP"));
        assertFalse(redacted.contains(github));
        assertFalse(redacted.contains(jwt));
        assertFalse(redacted.contains("=visible"));
    }

    @Test
    void terminatesAProcessAtItsDeadline() {
        GitCommand command = command(List.of("sleep", "10000"), Duration.ofMillis(100), 1_024, Set.of());

        assertThrows(GitCommandTimeoutException.class, () -> new SystemGitCommandRunner().run(command));
    }

    @Test
    void interruptionCancelsTheNativeProcess() throws Exception {
        GitCommand command = command(List.of("sleep", "10000"), Duration.ofSeconds(30), 1_024, Set.of());
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread worker = Thread.ofPlatform().start(() -> {
            try {
                new SystemGitCommandRunner().run(command);
            } catch (Throwable throwable) {
                failure.set(throwable);
            }
        });

        Thread.sleep(200L);
        worker.interrupt();
        worker.join(5_000L);

        assertFalse(worker.isAlive());
        assertTrue(failure.get() instanceof InterruptedException);
    }

    @Test
    void timeoutKillsAChildHoldingInheritedOutputOpen() throws Exception {
        Path childPid = temporaryDirectory.resolve("child.pid");
        GitCommand command = command(
                List.of("spawn-inherited", childPid.toString(), "10000", "100"),
                Duration.ofMillis(350),
                1_024,
                Set.of());
        long started = System.nanoTime();

        assertThrows(GitCommandTimeoutException.class, () -> new SystemGitCommandRunner().run(command));

        assertTrue(Duration.ofNanos(System.nanoTime() - started).compareTo(Duration.ofSeconds(3)) < 0);
        long pid = Long.parseLong(Files.readString(childPid));
        for (int attempt = 0; attempt < 20
                && ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false); attempt++) {
            Thread.sleep(25L);
        }
        assertFalse(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false));
    }

    @Test
    void timeoutTracksAChildWhoseParentExitsQuickly() throws Exception {
        Path childPid = temporaryDirectory.resolve("quick-child.pid");
        GitCommand command = command(
                List.of("spawn-inherited", childPid.toString(), "10000", "10"),
                Duration.ofMillis(350),
                1_024,
                Set.of());

        assertThrows(GitCommandTimeoutException.class, () -> new SystemGitCommandRunner().run(command));

        long pid = Long.parseLong(Files.readString(childPid));
        for (int attempt = 0; attempt < 20
                && ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false); attempt++) {
            Thread.sleep(25L);
        }
        assertFalse(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false));
    }

    @Test
    void settingsRejectCredentialBearingRemotes() {
        assertThrows(IllegalArgumentException.class, () -> new GitBackendSettings(
                true,
                temporaryDirectory.resolve("repository.git"),
                "git",
                "origin",
                Optional.of("https://alice:secret@example.invalid/repository.git"),
                GitBackendSettings.DEFAULT_LFS_PATTERNS,
                Duration.ofSeconds(5),
                4_096));
        assertThrows(IllegalArgumentException.class, () -> new GitBackendSettings(
                true,
                temporaryDirectory.resolve("repository.git"),
                "git",
                "origin",
                Optional.of("https://example.invalid/repository.git%3Ftoken%3Dsecret"),
                GitBackendSettings.DEFAULT_LFS_PATTERNS,
                Duration.ofSeconds(5),
                4_096));
    }

    @Test
    void probesGitAndLfsIndependently() throws Exception {
        List<List<String>> invocations = new ArrayList<>();
        AtomicInteger call = new AtomicInteger();
        GitCommandRunner fakeRunner = command -> {
            invocations.add(command.arguments());
            if (call.getAndIncrement() == 0) {
                return new GitCommandResult(0, "git version test", "", false, false);
            }
            return new GitCommandResult(1, "", "git: lfs unavailable", false, false);
        };
        GitBackendSettings settings = settings("git");

        GitToolHealth health = new GitToolProbe(settings, fakeRunner).probe();

        assertTrue(health.gitAvailable());
        assertFalse(health.lfsAvailable());
        assertFalse(health.available());
        assertEquals(2, invocations.size());
        assertEquals(List.of("git", "--version"), invocations.get(0));
        assertEquals(List.of("git", "lfs", "version"), invocations.get(1));
    }

    @Test
    void stillProbesLfsWhenGitExecutableCannotStart() throws Exception {
        AtomicInteger invocations = new AtomicInteger();
        GitCommandRunner fakeRunner = command -> {
            invocations.incrementAndGet();
            throw new IOException("missing");
        };

        GitToolHealth health = new GitToolProbe(settings("missing-git"), fakeRunner).probe();

        assertFalse(health.available());
        assertEquals(2, invocations.get());
    }

    private GitCommand command(
            List<String> fixtureArguments,
            Duration timeout,
            int outputLimit,
            Set<String> secrets) {
        Path java = Path.of(System.getProperty("java.home"), "bin", isWindows() ? "java.exe" : "java");
        List<String> arguments = new ArrayList<>();
        arguments.add(java.toString());
        arguments.add("-cp");
        arguments.add(System.getProperty("java.class.path"));
        arguments.add(NativeProcessFixture.class.getName());
        arguments.addAll(fixtureArguments);
        return new GitCommand(
                arguments,
                temporaryDirectory,
                Map.of(),
                new byte[0],
                secrets,
                timeout,
                outputLimit);
    }

    private GitBackendSettings settings(String executable) {
        return new GitBackendSettings(
                true,
                temporaryDirectory.resolve("repository.git"),
                executable,
                "origin",
                Optional.empty(),
                GitBackendSettings.DEFAULT_LFS_PATTERNS,
                Duration.ofSeconds(5),
                4_096);
    }

    private static boolean isWindows() {
        return System.getProperty("os.name").startsWith("Windows");
    }
}
