package dev.ishaanko.worldarchive.storage.git;

import dev.ishaanko.worldarchive.model.SafeText;
import java.util.Objects;

/** What deleting one backup's Git snapshot did; a failed delete says why and what to do. */
public record GitDeletion(Outcome outcome, String message) {
    public GitDeletion {
        Objects.requireNonNull(outcome, "outcome");
        message = SafeText.require(message, "message", 2_048);
    }

    /** The four ways one delete can end. */
    public enum Outcome {
        /** This computer's copy, the remote copy, or both were removed; none is left. */
        DELETED,
        /** Neither this computer nor the world's remote has the backup. */
        NOT_FOUND,
        /**
         * This computer has no copy and the world has no remote, so a copy on a remote could not
         * be checked. For a backup that was synced, the remote copy may still exist.
         */
        REMOTE_NOT_CONFIGURED,
        /** Nothing more was removed than the message says; the backup still exists somewhere. */
        FAILED
    }

    public boolean failed() {
        return outcome == Outcome.FAILED;
    }

    static GitDeletion deleted() {
        return new GitDeletion(Outcome.DELETED, "Deleted");
    }

    static GitDeletion notFound() {
        return new GitDeletion(Outcome.NOT_FOUND, "Neither this computer nor the remote has this backup");
    }

    static GitDeletion remoteNotConfigured() {
        return new GitDeletion(
                Outcome.REMOTE_NOT_CONFIGURED,
                "This computer has no copy, and the world has no remote to check for one");
    }

    static GitDeletion failed(String message) {
        return new GitDeletion(Outcome.FAILED, SafeText.of(message, "The backup could not be deleted", 1_024));
    }
}
