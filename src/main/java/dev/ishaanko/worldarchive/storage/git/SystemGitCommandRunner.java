package dev.ishaanko.worldarchive.storage.git;

import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;

/**
 * Runs Git with {@link ProcessBuilder}; no shell is ever involved. Standard output and error go
 * to private temporary files, so a helper process that keeps a handle open (an SSH control
 * master, a credential cache) can never hold a finished command back, and nothing polls the
 * process while it runs. Commands never prompt: terminal and askpass prompts are off, and every
 * inherited {@code GIT_} variable is removed. When the caller is interrupted, or a command with
 * an idle limit stays silent that long, the whole process tree is stopped: politely first, so
 * Git can remove its lock files, then forcibly.
 */
public final class SystemGitCommandRunner implements GitCommandRunner {
    private static final long TERMINATION_GRACE_MILLIS = 500;

    private static final long IDLE_CHECK_MILLIS = 250;

    private static final long FAILED_EXIT_WAIT_MILLIS = 100;

    /** The only {@code GIT_} variables a command may set; WorldArchive's own code sets them. */
    private static final Set<String> COMMAND_VARIABLES = Set.of(
            "GIT_AUTHOR_DATE",
            "GIT_AUTHOR_EMAIL",
            "GIT_AUTHOR_NAME",
            "GIT_COMMITTER_DATE",
            "GIT_COMMITTER_EMAIL",
            "GIT_COMMITTER_NAME",
            "GIT_LFS_FORCE_PROGRESS");

    @Override
    public GitCommandResult run(GitCommand command) throws IOException, InterruptedException {
        Objects.requireNonNull(command, "command");
        try (OutputFile output = OutputFile.create(); OutputFile error = OutputFile.create()) {
            ProcessBuilder builder = builder(command)
                    .redirectOutput(output.path().toFile())
                    .redirectError(error.path().toFile());
            int exitCode = execute(builder, command, () -> output.size() + error.size(), process -> {
            });
            int limit = command.maximumOutputBytes();
            return new GitCommandResult(
                    exitCode, output.read(limit), error.read(limit), output.size() > limit, error.size() > limit);
        }
    }

    @Override
    public GitCommandResult stream(GitCommand command, OutputReader reader)
            throws IOException, InterruptedException, GitStorageException {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(reader, "reader");
        int limit = command.maximumOutputBytes();
        try (OutputFile error = OutputFile.create()) {
            ProcessBuilder builder = builder(command).redirectError(error.path().toFile());
            int exitCode = execute(builder, command, error::size, process -> {
                InputStream output = process.getInputStream();
                try {
                    reader.read(output);
                } catch (IOException | GitStorageException exception) {
                    Optional<GitStorageException> gitFailure = gitFailure(process, error, limit);
                    if (gitFailure.isEmpty()) {
                        throw exception;
                    }
                    gitFailure.get().addSuppressed(exception);
                    throw gitFailure.get();
                } finally {
                    output.close();
                }
            });
            return new GitCommandResult(exitCode, "", error.read(limit), false, error.size() > limit);
        }
    }

    /**
     * Git's own failure when the process has already ended with an error: output that stops in
     * the middle is then Git's doing, and its message explains more than the reader can.
     */
    private static Optional<GitStorageException> gitFailure(Process process, OutputFile error, int limit)
            throws IOException, InterruptedException {
        if (!process.waitFor(FAILED_EXIT_WAIT_MILLIS, TimeUnit.MILLISECONDS) || process.exitValue() == 0) {
            return Optional.empty();
        }
        GitCommandResult result = new GitCommandResult(process.exitValue(), "", error.read(limit), false, false);
        return Optional.of(new GitStorageException(GitRepository.failureMessage(result)));
    }

    /**
     * Starts the process, feeds its input, runs the body while it works and waits for its exit.
     * Whatever ends this method early also ends the process tree.
     */
    private static <E extends Exception> int execute(
            ProcessBuilder builder,
            GitCommand command,
            LongSupplier written,
            ProcessBody<E> body) throws IOException, InterruptedException, E {
        Process process = start(builder);
        boolean exited = false;
        try {
            InputFeed input = InputFeed.start(process, command.standardInput());
            body.accept(process);
            awaitExit(process, command.idleTimeout(), written);
            exited = true;
            input.finish(process.exitValue());
            return process.exitValue();
        } finally {
            if (!exited) {
                terminate(process);
            }
        }
    }

    private static Process start(ProcessBuilder builder) throws IOException {
        try {
            return builder.start();
        } catch (IOException exception) {
            throw new IOException("Git could not be started. Install Git and make sure it is on PATH.", exception);
        }
    }

    private static ProcessBuilder builder(GitCommand command) {
        List<String> arguments = new ArrayList<>(command.arguments());
        arguments.set(0, KnownGitToolDirectories.program(arguments.getFirst()));
        ProcessBuilder builder = new ProcessBuilder(arguments).directory(command.workingDirectory().toFile());
        Map<String, String> environment = builder.environment();
        environment.keySet().removeIf(name -> name.toUpperCase(Locale.ROOT).startsWith("GIT_"));
        environment.remove("SSH_ASKPASS_REQUIRE");
        environment.put("GIT_TERMINAL_PROMPT", "0");
        environment.put("GCM_INTERACTIVE", "Never");
        environment.put("GIT_ASKPASS", "");
        environment.put("SSH_ASKPASS", "");
        command.environment().forEach((name, value) -> {
            if (!COMMAND_VARIABLES.contains(name)) {
                throw new IllegalArgumentException("Git command sets an unsupported variable: " + name);
            }
            environment.put(name, value);
        });
        KnownGitToolDirectories.extendPath(environment);
        return builder;
    }

    /** Waits for the exit; with an idle limit, gives up once the output stops growing that long. */
    private static void awaitExit(Process process, Optional<Duration> idleLimit, LongSupplier written)
            throws InterruptedException, GitCommandTimeoutException {
        if (idleLimit.isEmpty()) {
            process.waitFor();
            return;
        }
        long limit = idleLimit.orElseThrow().toNanos();
        long lastWritten = written.getAsLong();
        long lastChange = System.nanoTime();
        while (!process.waitFor(IDLE_CHECK_MILLIS, TimeUnit.MILLISECONDS)) {
            long current = written.getAsLong();
            if (current != lastWritten) {
                lastWritten = current;
                lastChange = System.nanoTime();
            } else if (System.nanoTime() - lastChange >= limit) {
                throw new GitCommandTimeoutException(idleLimit.orElseThrow());
            }
        }
    }

    /**
     * Stops the process and every descendant it started. The tree is read once, while the root
     * still runs, because Git for Windows' launcher and a transport helper both outlive a killed
     * parent. Deeper processes are asked first; after a short grace period the rest is killed.
     */
    private static void terminate(Process process) {
        List<ProcessHandle> tree = new ArrayList<>(process.descendants().toList());
        tree.sort(Comparator.comparingInt((ProcessHandle handle) -> depth(handle, process.pid())).reversed());
        tree.forEach(ProcessHandle::destroy);
        process.destroy();
        boolean interrupted = Thread.interrupted();
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(TERMINATION_GRACE_MILLIS);
        while (!interrupted
                && (process.isAlive() || tree.stream().anyMatch(ProcessHandle::isAlive))
                && System.nanoTime() < deadline) {
            try {
                Thread.sleep(10);
            } catch (InterruptedException exception) {
                interrupted = true;
            }
        }
        tree.stream().filter(ProcessHandle::isAlive).forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static int depth(ProcessHandle handle, long rootPid) {
        int depth = 0;
        Optional<ProcessHandle> current = Optional.of(handle);
        while (current.isPresent() && current.get().pid() != rootPid && depth < 64) {
            depth++;
            current = current.get().parent();
        }
        return depth;
    }

    /** Work done while the process runs, such as reading its streamed output. */
    @FunctionalInterface
    private interface ProcessBody<E extends Exception> {
        void accept(Process process) throws IOException, InterruptedException, E;
    }

    /** One private temporary file that receives a process stream and is deleted afterwards. */
    private record OutputFile(Path path) implements AutoCloseable {
        static OutputFile create() throws IOException {
            return new OutputFile(Files.createTempFile("worldarchive-git-", ".log"));
        }

        long size() {
            try {
                return Files.size(path);
            } catch (IOException exception) {
                return 0;
            }
        }

        String read(int maximumBytes) throws IOException {
            try (InputStream input = Files.newInputStream(path)) {
                return new String(input.readNBytes(maximumBytes), StandardCharsets.UTF_8);
            }
        }

        @Override
        public void close() {
            try {
                Files.deleteIfExists(path);
            } catch (IOException ignored) {
                // A descendant on Windows may still hold the file; it is small and in temp.
            }
        }
    }

    /**
     * Writes standard input on a virtual thread, so the caller can wait for or read from the
     * process at the same time. A failure of the pipe itself is Git's doing and shows in its
     * exit code; any other failure, such as an unreadable source file, is the command's.
     */
    private static final class InputFeed {
        private final PipeOutput pipe;

        private final Thread writer;

        private final AtomicReference<IOException> failure;

        private InputFeed(PipeOutput pipe, Thread writer, AtomicReference<IOException> failure) {
            this.pipe = pipe;
            this.writer = writer;
            this.failure = failure;
        }

        static InputFeed start(Process process, GitCommand.Input input) {
            PipeOutput pipe = new PipeOutput(process.getOutputStream());
            AtomicReference<IOException> failure = new AtomicReference<>();
            if (input == GitCommand.Input.NONE) {
                pipe.closeQuietly();
                return new InputFeed(pipe, null, failure);
            }
            Thread writer = Thread.ofVirtual().name("worldarchive-git-input").start(() -> {
                try (pipe) {
                    input.writeTo(pipe);
                } catch (IOException exception) {
                    failure.set(exception);
                }
            });
            return new InputFeed(pipe, writer, failure);
        }

        void finish(int exitCode) throws IOException, InterruptedException {
            if (writer == null) {
                return;
            }
            writer.join();
            IOException failed = failure.get();
            if (failed == null || pipe.broken && exitCode != 0) {
                return;
            }
            throw pipe.broken
                    ? new IOException("Git stopped reading its input before it finished", failed)
                    : failed;
        }
    }

    /** Standard input of the process; remembers whether writing to it failed. */
    private static final class PipeOutput extends FilterOutputStream {
        private volatile boolean broken;

        PipeOutput(OutputStream pipe) {
            super(pipe);
        }

        @Override
        public void write(byte[] bytes, int offset, int length) throws IOException {
            try {
                out.write(bytes, offset, length);
            } catch (IOException exception) {
                broken = true;
                throw exception;
            }
        }

        @Override
        public void write(int value) throws IOException {
            write(new byte[] {(byte) value}, 0, 1);
        }

        @Override
        public void close() throws IOException {
            try {
                out.close();
            } catch (IOException exception) {
                broken = true;
                throw exception;
            }
        }

        void closeQuietly() {
            try {
                close();
            } catch (IOException ignored) {
                // Git reads no input; a closed pipe changes nothing.
            }
        }
    }
}
