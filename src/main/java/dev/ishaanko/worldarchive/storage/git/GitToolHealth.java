package dev.ishaanko.worldarchive.storage.git;

import java.util.Objects;
import java.util.Optional;

/**
 * Whether Git and the Git LFS extension work, checked independently. A tool that works reports
 * its version; one that does not reports why.
 */
public record GitToolHealth(
        Optional<String> gitVersion,
        Optional<String> lfsVersion,
        Optional<String> gitFailure,
        Optional<String> lfsFailure) {
    public GitToolHealth {
        Objects.requireNonNull(gitVersion, "gitVersion");
        Objects.requireNonNull(lfsVersion, "lfsVersion");
        Objects.requireNonNull(gitFailure, "gitFailure");
        Objects.requireNonNull(lfsFailure, "lfsFailure");
        if (gitVersion.isPresent() == gitFailure.isPresent() || lfsVersion.isPresent() == lfsFailure.isPresent()) {
            throw new IllegalArgumentException("Each Git tool reports either its version or its failure");
        }
    }

    public boolean gitAvailable() {
        return gitVersion.isPresent();
    }

    public boolean lfsAvailable() {
        return lfsVersion.isPresent();
    }

    /**
     * True when Git backups can run. Git LFS is required although a local backup writes its LFS
     * objects without it: sync, restore from the remote and import need it, and one rule for
     * every Git backup is easier to understand than one that changes when a remote is added.
     */
    public boolean available() {
        return gitAvailable() && lfsAvailable();
    }

    public String summary() {
        if (!gitAvailable() && !lfsAvailable()) {
            return "Git and Git LFS are not installed. Install Git with Git LFS and restart the game.";
        }
        if (!gitAvailable()) {
            return "Git is not installed or could not be started. Install Git and make sure it is on PATH.";
        }
        if (!lfsAvailable()) {
            return "Git LFS is not installed. Install Git LFS and restart the game.";
        }
        return "Git and Git LFS are available";
    }
}
