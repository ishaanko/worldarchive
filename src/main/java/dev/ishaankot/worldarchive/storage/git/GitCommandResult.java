package dev.ishaankot.worldarchive.storage.git;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.Objects;
import java.util.regex.Pattern;

/** Bounded and redacted output from one Git process. */
public record GitCommandResult(
        int exitCode,
        String standardOutput,
        String standardError,
        boolean standardOutputTruncated,
        boolean standardErrorTruncated,
        long standardOutputBytes,
        String standardOutputSha256) {
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");

    public GitCommandResult {
        Objects.requireNonNull(standardOutput, "standardOutput");
        Objects.requireNonNull(standardError, "standardError");
        if (standardOutputBytes < 0) {
            throw new IllegalArgumentException("Git output byte count must not be negative");
        }
        Objects.requireNonNull(standardOutputSha256, "standardOutputSha256");
        if (!SHA256.matcher(standardOutputSha256).matches()) {
            throw new IllegalArgumentException("Git output digest must be lowercase SHA-256");
        }
    }

    public GitCommandResult(
            int exitCode,
            String standardOutput,
            String standardError,
            boolean standardOutputTruncated,
            boolean standardErrorTruncated) {
        this(
                exitCode,
                standardOutput,
                standardError,
                standardOutputTruncated,
                standardErrorTruncated,
                standardOutput.getBytes(StandardCharsets.UTF_8).length,
                HexFormat.of().formatHex(GitInventory.sha256()
                        .digest(standardOutput.getBytes(StandardCharsets.UTF_8))));
    }

    public boolean successful() {
        return exitCode == 0;
    }
}
