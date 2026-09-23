package dev.ishaanko.worldarchive.storage.git;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** How the runner starts, feeds, limits, reads and stops real processes. */
class SystemGitCommandRunnerTest {
    @TempDir
    Path temporaryDirectory;

    private final SystemGitCommandRunner runner = new SystemGitCommandRunner();

    @Test
    void aSilentCommandStopsAtItsIdleLimitWhileOneThatReportsProgressRuns() throws Exception {
        Duration idle = Duration.ofMillis(600);
        GitCommand silent = command(List.of("sleep", "20000"), Optional.of(idle), GitCommand.Input.NONE);
        GitCommand notReading = command(List.of("sleep", "20000"), Optional.of(idle), GitCommand.Input.of(new byte[4_000_000]));
        GitCommand reporting = command(List.of("ticks", "8", "250"), Optional.of(idle), GitCommand.Input.NONE);
        long started = System.nanoTime();

        assertThrows(GitCommandTimeoutException.class, () -> runner.run(silent));
        assertThrows(GitCommandTimeoutException.class, () -> runner.run(notReading));
        assertTrue(Duration.ofNanos(System.nanoTime() - started).compareTo(Duration.ofSeconds(8)) < 0);
        assertTrue(runner.run(reporting).successful(), "two seconds of progress outlast a 0.6 second idle limit");
    }

    @Test
    void interruptionStopsTheCommandAndTheProcessesItStarted() throws Exception {
        Path childPid = temporaryDirectory.resolve("child.pid");
        GitCommand command = command(
                List.of("spawn-inherited", childPid.toString(), "20000", "20000"), Optional.empty(), GitCommand.Input.NONE);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread worker = Thread.ofPlatform().start(() -> {
            try {
                runner.run(command);
            } catch (Throwable throwable) {
                failure.set(throwable);
            }
        });
        waitFor(childPid);

        worker.interrupt();
        worker.join(5_000);

        assertFalse(worker.isAlive());
        assertInstanceOf(InterruptedException.class, failure.get());
        assertEventuallyDead(Long.parseLong(Files.readString(childPid)));
    }

    @Test
    void aProcessThatKeepsTheOutputOpenNeverHoldsBackAFinishedCommand() throws Exception {
        Path childPid = temporaryDirectory.resolve("orphan.pid");
        GitCommand command = command(
                List.of("spawn-inherited", childPid.toString(), "20000", "10"), Optional.empty(), GitCommand.Input.NONE);
        long started = System.nanoTime();
        try {
            assertTrue(runner.run(command).successful());
            assertTrue(Duration.ofNanos(System.nanoTime() - started).compareTo(Duration.ofSeconds(10)) < 0);
        } finally {
            ProcessHandle.of(Long.parseLong(Files.readString(childPid))).ifPresent(ProcessHandle::destroyForcibly);
        }
    }

    @Test
    void commandsNeverPromptAndSetOnlyWorldArchivesOwnGitVariables() throws Exception {
        assertEquals("0", environment("GIT_TERMINAL_PROMPT", Map.of()));
        assertEquals("Never", environment("GCM_INTERACTIVE", Map.of()));
        assertEquals("1", environment("GIT_LFS_FORCE_PROGRESS", Map.of("GIT_LFS_FORCE_PROGRESS", "1")));
        assertThrows(IllegalArgumentException.class, () -> environment("GIT_DIR", Map.of("GIT_DIR", "/elsewhere")));
        assertThrows(IllegalArgumentException.class,
                () -> environment("GIT_TERMINAL_PROMPT", Map.of("GIT_TERMINAL_PROMPT", "1")));
    }

    @Test
    void outputPastTheLimitIsCutAndFlagged() throws Exception {
        GitCommand command = new GitCommand(fixture(List.of("output", "abc", "2000")), temporaryDirectory, Map.of(),
                GitCommand.Input.NONE, Optional.empty(), 1_024);

        GitCommandResult result = runner.run(command);

        assertTrue(result.successful());
        assertTrue(result.standardOutputTruncated());
        assertEquals(1_024, result.standardOutput().length());
    }

    @Test
    void whenGitFailsWhileItsOutputIsReadGitsOwnErrorIsReported() {
        GitCommand command = command(
                List.of("fail", "partial", "fatal: loose object 1234 is corrupt"), Optional.empty(), GitCommand.Input.NONE);

        GitStorageException failure = assertThrows(GitStorageException.class, () -> runner.stream(command, output -> {
            output.readAllBytes();
            throw new GitStorageException("Git returned incomplete object data");
        }));

        assertTrue(failure.getMessage().contains("loose object 1234 is corrupt"), failure.getMessage());
    }

    private String environment(String name, Map<String, String> environment) throws IOException, InterruptedException {
        GitCommand command = new GitCommand(fixture(List.of("environment", name)), temporaryDirectory, environment,
                GitCommand.Input.NONE, Optional.empty(), 1_024);
        GitCommandResult result = runner.run(command);
        assertTrue(result.successful());
        return result.standardOutput();
    }

    private GitCommand command(List<String> fixtureArguments, Optional<Duration> idleLimit, GitCommand.Input input) {
        return new GitCommand(fixture(fixtureArguments), temporaryDirectory, Map.of(), input, idleLimit, 4_096);
    }

    private static List<String> fixture(List<String> fixtureArguments) {
        String program = System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
        List<String> arguments = new ArrayList<>(List.of(
                Path.of(System.getProperty("java.home"), "bin", program).toString(),
                "-cp",
                System.getProperty("java.class.path"),
                NativeProcessFixture.class.getName()));
        arguments.addAll(fixtureArguments);
        return arguments;
    }

    private static void waitFor(Path file) throws Exception {
        for (int attempt = 0; attempt < 200 && (!Files.exists(file) || Files.size(file) == 0); attempt++) {
            Thread.sleep(25);
        }
    }

    private static void assertEventuallyDead(long pid) throws InterruptedException {
        for (int attempt = 0; attempt < 80 && ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false); attempt++) {
            Thread.sleep(25);
        }
        assertFalse(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false));
    }
}
