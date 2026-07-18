package dev.ishaankot.worldarchive.storage.git;

import java.io.IOException;
import java.time.Duration;
import java.util.Objects;

/** Signals that a native Git command exceeded its bounded runtime. */
public final class GitCommandTimeoutException extends IOException {
    public GitCommandTimeoutException(Duration timeout) {
        super("Git command timed out after " + Objects.requireNonNull(timeout, "timeout"));
    }
}
