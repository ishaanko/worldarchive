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
        String typed = typed(value);
        if (typed.isBlank()) {
            return Optional.empty();
        }
        try {
            Path path = Path.of(typed);
            return path.isAbsolute() ? Optional.of(path.normalize()) : Optional.empty();
        } catch (InvalidPathException exception) {
            return Optional.empty();
        }
    }

    /**
     * The path the player meant: without spaces around it, without the double quotes that
     * Windows "Copy as path" adds, and with a leading "~" replaced by the home directory.
     */
    static String typed(String value) {
        String stripped = value.strip();
        if (stripped.length() >= 2 && stripped.startsWith("\"") && stripped.endsWith("\"")) {
            stripped = stripped.substring(1, stripped.length() - 1).strip();
        }
        return expandHome(stripped);
    }

    /** Replaces a leading "~" with the home directory, as a shell would. */
    private static String expandHome(String value) {
        String home = System.getProperty("user.home");
        if (home == null || home.isBlank()) {
            return value;
        }
        if (value.equals("~")) {
            return home;
        }
        if (value.startsWith("~/") || value.startsWith("~\\")) {
            return home + value.substring(1);
        }
        return value;
    }
}
