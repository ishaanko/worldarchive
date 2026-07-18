package dev.ishaankot.worldarchive.storage.git;

/** Safe, user-displayable failure from Git snapshot storage. */
public final class GitStorageException extends Exception {
    public GitStorageException(String message) {
        super(message);
    }

    public GitStorageException(String message, Throwable cause) {
        super(message, cause);
    }
}
