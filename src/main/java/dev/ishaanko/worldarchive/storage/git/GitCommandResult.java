package dev.ishaanko.worldarchive.storage.git;

import java.util.Objects;

/** The exit code and the bounded standard output and error of one Git process. */
public record GitCommandResult(
        int exitCode,
        String standardOutput,
        String standardError,
        boolean standardOutputTruncated,
        boolean standardErrorTruncated) {
    public GitCommandResult {
        Objects.requireNonNull(standardOutput, "standardOutput");
        Objects.requireNonNull(standardError, "standardError");
    }

    public boolean successful() {
        return exitCode == 0;
    }
}
