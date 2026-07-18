package dev.ishaankot.worldarchive.recovery;

/** Expected maintenance failure that is safe to present without exposing credentials. */
public final class BackupRecoveryException extends RuntimeException {
    public BackupRecoveryException(String message) {
        super(message);
    }

    public BackupRecoveryException(String message, Throwable cause) {
        super(message, cause);
    }
}
