package dev.ishaanko.worldarchive.settings;

import java.io.File;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Optional;

/** Shared parsing rule for a user-typed absolute path string. */
final class SettingsPaths {
    private static final boolean WINDOWS = File.separatorChar == '\\';

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
     * The path the player meant: without the double quotes that Windows "Copy as path" adds,
     * without spaces or line breaks around it, and with a leading "~" replaced by the home
     * directory. A folder name can end in a space outside Windows, so there only line breaks are
     * dropped from the end, and the text inside quotes is always kept as written.
     */
    static String typed(String value) {
        String stripped = value.strip();
        if (stripped.length() >= 2 && stripped.startsWith("\"") && stripped.endsWith("\"")) {
            return expandHome(stripped.substring(1, stripped.length() - 1));
        }
        return expandHome(WINDOWS ? stripped : value.stripLeading().replaceFirst("[\\r\\n\\t]+$", ""));
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
