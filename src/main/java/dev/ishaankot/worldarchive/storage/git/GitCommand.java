package dev.ishaankot.worldarchive.storage.git;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** One argument-safe Git process invocation. */
public record GitCommand(
        List<String> arguments,
        Path workingDirectory,
        Map<String, String> environment,
        byte[] standardInput,
        Set<String> secrets,
        Duration timeout,
        int maximumOutputBytes) {
    public GitCommand {
        arguments = List.copyOf(Objects.requireNonNull(arguments, "arguments"));
        if (arguments.isEmpty() || arguments.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("A Git command requires non-null arguments");
        }
        workingDirectory = Objects.requireNonNull(workingDirectory, "workingDirectory")
                .toAbsolutePath()
                .normalize();
        environment = Map.copyOf(Objects.requireNonNull(environment, "environment"));
        standardInput = Objects.requireNonNull(standardInput, "standardInput").clone();
        secrets = Set.copyOf(Objects.requireNonNull(secrets, "secrets"));
        timeout = Objects.requireNonNull(timeout, "timeout");
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("Git command timeout must be positive");
        }
        if (maximumOutputBytes < 1_024) {
            throw new IllegalArgumentException("Git command output limit must be at least 1024 bytes");
        }
    }

    public static GitCommand of(
            List<String> arguments,
            Path workingDirectory,
            Duration timeout,
            int maximumOutputBytes) {
        return new GitCommand(
                arguments,
                workingDirectory,
                Map.of(),
                new byte[0],
                Set.of(),
                timeout,
                maximumOutputBytes);
    }

    public static byte[] utf8Input(String value) {
        return Objects.requireNonNull(value, "value").getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public byte[] standardInput() {
        return standardInput.clone();
    }
}
