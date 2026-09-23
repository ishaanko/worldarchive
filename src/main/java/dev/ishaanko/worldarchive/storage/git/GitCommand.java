package dev.ishaanko.worldarchive.storage.git;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * One Git process to run: the program and its arguments (never a shell), the working directory,
 * extra environment, standard input, and an optional idle limit. A command with an idle limit
 * is stopped when it writes nothing for that long; a command without one runs until it exits or
 * its caller is interrupted.
 */
public record GitCommand(
        List<String> arguments,
        Path workingDirectory,
        Map<String, String> environment,
        Input standardInput,
        Optional<Duration> idleTimeout,
        int maximumOutputBytes) {
    public GitCommand {
        arguments = List.copyOf(Objects.requireNonNull(arguments, "arguments"));
        if (arguments.isEmpty()) {
            throw new IllegalArgumentException("A Git command needs a program");
        }
        workingDirectory = Objects.requireNonNull(workingDirectory, "workingDirectory")
                .toAbsolutePath()
                .normalize();
        environment = Map.copyOf(Objects.requireNonNull(environment, "environment"));
        Objects.requireNonNull(standardInput, "standardInput");
        Objects.requireNonNull(idleTimeout, "idleTimeout");
        if (idleTimeout.filter(limit -> limit.isZero() || limit.isNegative()).isPresent()) {
            throw new IllegalArgumentException("A Git idle limit must be positive");
        }
        if (maximumOutputBytes < 1_024) {
            throw new IllegalArgumentException("A Git output limit must be at least 1 KiB");
        }
    }

    /** Writes a command's standard input. The runner calls it once, on a thread of its own. */
    @FunctionalInterface
    public interface Input {
        Input NONE = output -> {
        };

        void writeTo(OutputStream output) throws IOException;

        static Input of(byte[] bytes) {
            byte[] copy = bytes.clone();
            return output -> output.write(copy);
        }

        static Input utf8(String text) {
            return of(text.getBytes(StandardCharsets.UTF_8));
        }
    }
}
