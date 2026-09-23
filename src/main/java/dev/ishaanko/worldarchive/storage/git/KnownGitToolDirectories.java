package dev.ishaanko.worldarchive.storage.git;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Finds Git and Git LFS where macOS package managers put them. A game started from the Dock gets
 * a minimal {@code PATH} without {@code /opt/homebrew/bin}, and the Command Line Tools shim at
 * {@code /usr/bin/git} opens an install dialog when those tools are missing. On macOS this class
 * therefore prefers a package-manager Git and adds those folders to the end of the child's
 * {@code PATH}, so the user's own tools still win. Elsewhere it changes nothing. The folders are
 * looked up once.
 */
final class KnownGitToolDirectories {
    private static final List<Path> PREFERRED = isMac(System.getProperty("os.name", ""))
            ? Stream.of("/opt/homebrew/bin", "/usr/local/bin", "/opt/local/bin")
                    .map(Path::of)
                    .filter(Files::isDirectory)
                    .toList()
            : List.of();

    private KnownGitToolDirectories() {
    }

    /** The program to start: a package-manager Git for plain {@code git}, otherwise unchanged. */
    static String program(String executable) {
        if (!executable.equals("git")) {
            return executable;
        }
        return PREFERRED.stream()
                .map(folder -> folder.resolve("git"))
                .filter(Files::isExecutable)
                .map(Path::toString)
                .findFirst()
                .orElse(executable);
    }

    /** Adds the package-manager folders to the end of the child's {@code PATH}. */
    static void extendPath(Map<String, String> environment) {
        if (!PREFERRED.isEmpty()) {
            environment.put("PATH", appendFolders(environment.getOrDefault("PATH", ""), PREFERRED));
        }
    }

    /** The path list with each folder appended unless it is already listed. */
    static String appendFolders(String path, List<Path> folders) {
        Set<String> entries = new LinkedHashSet<>();
        for (String entry : path.split(File.pathSeparator)) {
            if (!entry.isBlank()) {
                entries.add(entry);
            }
        }
        folders.forEach(folder -> entries.add(folder.toString()));
        return String.join(File.pathSeparator, entries);
    }

    private static boolean isMac(String osName) {
        String os = osName.toLowerCase(Locale.ROOT);
        return os.contains("mac") || os.contains("darwin");
    }
}
