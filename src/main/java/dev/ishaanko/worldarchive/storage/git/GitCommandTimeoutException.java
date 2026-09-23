package dev.ishaanko.worldarchive.storage.git;

import java.io.IOException;
import java.time.Duration;
import java.util.Objects;

/** Signals that a Git command with an idle limit wrote nothing for that long and was stopped. */
public final class GitCommandTimeoutException extends IOException {
    public GitCommandTimeoutException(Duration idleLimit) {
        super("Git made no progress for " + describe(Objects.requireNonNull(idleLimit, "idleLimit"))
                + " and was stopped. Check the network connection and try again.");
    }

    private static String describe(Duration limit) {
        long seconds = Math.max(1, limit.toSeconds());
        if (seconds % 60 == 0) {
            long minutes = seconds / 60;
            return minutes == 1 ? "1 minute" : minutes + " minutes";
        }
        return seconds == 1 ? "1 second" : seconds + " seconds";
    }
}
