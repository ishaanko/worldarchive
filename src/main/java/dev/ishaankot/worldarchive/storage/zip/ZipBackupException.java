package dev.ishaankot.worldarchive.storage.zip;

import java.io.IOException;

/** Checked failure from a ZIP operation with a user-safe summary. */
public final class ZipBackupException extends IOException {
    public ZipBackupException(String message) {
        super(message);
    }

    public ZipBackupException(String message, Throwable cause) {
        super(message, cause);
    }
}
