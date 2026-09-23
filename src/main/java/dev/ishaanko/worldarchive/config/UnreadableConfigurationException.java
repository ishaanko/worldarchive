package dev.ishaanko.worldarchive.config;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;

/**
 * The settings file exists but cannot be read or understood. Nothing is written over such a
 * file; resetting the settings keeps it under a new name. The cause says what is wrong.
 */
public final class UnreadableConfigurationException extends IOException {
    private final Path file;

    public UnreadableConfigurationException(Path file, Throwable cause) {
        super("WorldArchive settings in " + file + " could not be read: "
                + Objects.requireNonNullElse(cause.getMessage(), cause.getClass().getSimpleName()), cause);
        this.file = file;
    }

    public Path file() {
        return file;
    }
}
