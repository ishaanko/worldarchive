package dev.ishaanko.worldarchive.catalog;

import java.io.IOException;

/**
 * A catalog or registry file that WorldArchive wrote but can no longer decode. Unlike other
 * {@link IOException}s, reading it again will not help, so the file is moved aside.
 */
final class DamagedFileException extends IOException {
    DamagedFileException(String message, Throwable cause) {
        super(message, cause);
    }
}
