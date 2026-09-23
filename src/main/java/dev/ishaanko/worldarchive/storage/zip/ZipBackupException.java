package dev.ishaanko.worldarchive.storage.zip;

import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileSystemException;
import java.nio.file.NoSuchFileException;
import java.util.Objects;

/** A failed ZIP operation whose message the player can read: what happened and what to do. */
public class ZipBackupException extends IOException {
    public ZipBackupException(String message) {
        super(message);
    }

    public ZipBackupException(String message, Throwable cause) {
        super(message, cause);
    }

    /**
     * A short reason for a file system failure to put in a message, such as "access was
     * denied". Java names only the file in most of these exceptions, not what went wrong.
     */
    static String reason(IOException failure) {
        if (failure instanceof AccessDeniedException) {
            return "access was denied";
        }
        if (failure instanceof NoSuchFileException) {
            return "a file or folder is missing";
        }
        if (failure instanceof FileAlreadyExistsException) {
            return "a file is in the way";
        }
        if (failure instanceof FileSystemException system && system.getReason() != null) {
            return system.getReason();
        }
        return Objects.requireNonNullElse(failure.getMessage(), failure.getClass().getSimpleName());
    }
}
