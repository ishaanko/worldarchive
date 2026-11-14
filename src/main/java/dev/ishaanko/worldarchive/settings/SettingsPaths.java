package dev.ishaanko.worldarchive.settings;

import java.nio.file.Files;
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
     * Windows "Copy as path" adds, and with a leading "~" replaced by the home directory. A path
     * that names a folder as written stays as written, and so does the text inside quotes,
     * because a folder name can end in a space outside Windows.
     */
    static String typed(String value) {
        String literal = expandHome(value);
        if (isFolder(literal)) {
            return literal;
        }
        String stripped = value.strip();
        if (stripped.length() >= 2 && stripped.startsWith("\"") && stripped.endsWith("\"")) {
            return expandHome(stripped.substring(1, stripped.length() - 1));
        }
        return expandHome(stripped);
    }

    private static boolean isFolder(String value) {
        try {
            return Files.isDirectory(Path.of(value));
        } catch (InvalidPathException exception) {
            return false;
        }
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
