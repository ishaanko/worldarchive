package dev.ishaanko.worldarchive.settings;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Optional;

/** Shared parsing rule for a user-typed absolute path string. */
final class SettingsPaths {
    private SettingsPaths() {
    }

    /** Blank, relative, or unparsable input is treated as "no path", not an error. */
    static Optional<Path> parseAbsolute(String value) {
        if (value.isBlank()) {
            return Optional.empty();
        }
        try {
            Path path = Path.of(value);
            return path.isAbsolute() ? Optional.of(path.normalize()) : Optional.empty();
        } catch (InvalidPathException exception) {
            return Optional.empty();
        }
    }
}
