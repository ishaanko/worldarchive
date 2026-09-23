package dev.ishaanko.worldarchive.storage.zip;

/**
 * A ZIP file that is not a whole WorldArchive backup: damaged, cut short, changed after it was
 * made, or never a WorldArchive backup. Other {@link java.io.IOException}s mean the file could
 * not be read at all, so a later attempt may still succeed; this one will not.
 */
public final class ZipArchiveDamagedException extends ZipBackupException {
    public ZipArchiveDamagedException(String message) {
        super(message);
    }

    public ZipArchiveDamagedException(String message, Throwable cause) {
        super(message, cause);
    }
}
