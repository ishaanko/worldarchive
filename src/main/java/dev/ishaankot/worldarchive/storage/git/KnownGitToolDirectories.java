package dev.ishaankot.worldarchive.storage.git;

import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * Appends well-known Git and Git LFS install folders to a child process {@code PATH}.
 *
 * <p>Game clients are usually launched from a GUI launcher, which on macOS and Linux inherits a
 * minimal login {@code PATH} that excludes package-manager folders such as {@code
 * /opt/homebrew/bin}. Git may then resolve while {@code git-lfs} does not, or neither resolves at
 * all. Folders are only appended, never prepended, so an operator-configured {@code PATH} always
 * wins, and only folders that exist on this machine are added.</p>
 */
final class KnownGitToolDirectories {
    private KnownGitToolDirectories() {
    }

    static void augment(Map<String, String> environment) {
        augment(
                environment,
                System.getProperty("os.name", ""),
                Path.of(System.getProperty("user.home", "")),
                KnownGitToolDirectories::isExistingDirectory);
    }

    static void augment(
            Map<String, String> environment,
            String osName,
            Path userHome,
            Predicate<Path> isDirectory) {
        Objects.requireNonNull(environment, "environment");
        Objects.requireNonNull(osName, "osName");
        Objects.requireNonNull(userHome, "userHome");
        Objects.requireNonNull(isDirectory, "isDirectory");
        boolean windows = isWindows(osName);
        String pathKey = environment.keySet().stream()
                .filter(name -> name.equalsIgnoreCase("PATH"))
                .findFirst()
                .orElse(windows ? "Path" : "PATH");
        String augmented = appendMissing(
                environment.getOrDefault(pathKey, ""),
                candidateDirectories(osName, environment, userHome),
                windows,
                isDirectory);
        environment.put(pathKey, augmented);
    }

    static List<Path> candidateDirectories(
            String osName,
            Map<String, String> environment,
            Path userHome) {
        if (isWindows(osName)) {
            return windowsCandidates(environment, userHome);
        }
        String os = osName.toLowerCase(Locale.ROOT);
        if (os.contains("mac") || os.contains("darwin")) {
            return macCandidates(userHome);
        }
        return linuxCandidates(userHome);
    }

    private static List<Path> macCandidates(Path userHome) {
        List<Path> candidates = new ArrayList<>();
        candidates.add(Path.of("/opt/homebrew/bin"));
        candidates.add(Path.of("/usr/local/bin"));
        candidates.add(Path.of("/usr/bin"));
        candidates.add(Path.of("/bin"));
        candidates.add(Path.of("/opt/local/bin"));
        candidates.add(Path.of("/sw/bin"));
        candidates.add(Path.of("/usr/local/git/bin"));
        candidates.add(Path.of("/Library/Developer/CommandLineTools/usr/bin"));
        candidates.add(Path.of("/Applications/Xcode.app/Contents/Developer/usr/bin"));
        candidates.add(userHome.resolve(".local/bin"));
        candidates.addAll(nixCandidates(userHome));
        return List.copyOf(candidates);
    }

    private static List<Path> linuxCandidates(Path userHome) {
        List<Path> candidates = new ArrayList<>();
        candidates.add(Path.of("/usr/local/bin"));
        candidates.add(Path.of("/usr/bin"));
        candidates.add(Path.of("/bin"));
        candidates.add(Path.of("/snap/bin"));
        candidates.add(Path.of("/home/linuxbrew/.linuxbrew/bin"));
        candidates.add(userHome.resolve(".linuxbrew/bin"));
        candidates.add(userHome.resolve(".local/bin"));
        candidates.addAll(nixCandidates(userHome));
        return List.copyOf(candidates);
    }

    private static List<Path> nixCandidates(Path userHome) {
        return List.of(
                userHome.resolve(".nix-profile/bin"),
                Path.of("/nix/var/nix/profiles/default/bin"),
                Path.of("/run/current-system/sw/bin"));
    }

    private static List<Path> windowsCandidates(Map<String, String> environment, Path userHome) {
        Set<Path> candidates = new LinkedHashSet<>();
        Set<String> programRoots = new LinkedHashSet<>();
        variable(environment, "ProgramFiles").ifPresent(programRoots::add);
        variable(environment, "ProgramW6432").ifPresent(programRoots::add);
        variable(environment, "ProgramFiles(x86)").ifPresent(programRoots::add);
        programRoots.add("C:\\Program Files");
        programRoots.add("C:\\Program Files (x86)");
        for (String root : programRoots) {
            addGitInstallation(candidates, root);
        }
        String localAppData = variable(environment, "LOCALAPPDATA")
                .orElseGet(() -> userHome.resolve("AppData").resolve("Local").toString());
        addGitInstallation(candidates, localAppData + "\\Programs");
        String programData = variable(environment, "ProgramData")
                .or(() -> variable(environment, "ALLUSERSPROFILE"))
                .orElse("C:\\ProgramData");
        addPath(candidates, variable(environment, "ChocolateyInstall")
                .orElse(programData + "\\chocolatey") + "\\bin");
        addPath(candidates, variable(environment, "SCOOP")
                .map(scoop -> scoop + "\\shims")
                .orElseGet(() -> userHome.resolve("scoop").resolve("shims").toString()));
        addPath(candidates, variable(environment, "SCOOP_GLOBAL")
                .orElse(programData + "\\scoop") + "\\shims");
        return List.copyOf(candidates);
    }

    /** Adds the folders one Git for Windows or Git LFS installer places under a root. */
    private static void addGitInstallation(Set<Path> candidates, String root) {
        addPath(candidates, root + "\\Git\\cmd");
        addPath(candidates, root + "\\Git\\mingw64\\bin");
        addPath(candidates, root + "\\Git\\bin");
        addPath(candidates, root + "\\Git LFS");
    }

    private static void addPath(Set<Path> candidates, String value) {
        try {
            candidates.add(Path.of(value));
        } catch (InvalidPathException ignored) {
            // An unparsable environment value never becomes a candidate.
        }
    }

    private static Optional<String> variable(Map<String, String> environment, String name) {
        return environment.entrySet().stream()
                .filter(entry -> entry.getKey().equalsIgnoreCase(name))
                .map(Map.Entry::getValue)
                .filter(value -> value != null && !value.isBlank())
                .findFirst();
    }

    private static String appendMissing(
            String currentPath,
            List<Path> candidates,
            boolean windows,
            Predicate<Path> isDirectory) {
        String separator = windows ? ";" : ":";
        List<String> entries = new ArrayList<>();
        Set<String> present = new HashSet<>();
        for (String entry : currentPath.split(Pattern.quote(separator))) {
            if (!entry.isBlank()) {
                entries.add(entry);
                present.add(normalize(entry, windows));
            }
        }
        for (Path candidate : candidates) {
            String value = candidate.toString();
            if (present.add(normalize(value, windows)) && isDirectory.test(candidate)) {
                entries.add(value);
            }
        }
        return String.join(separator, entries);
    }

    private static String normalize(String entry, boolean windows) {
        String value = entry.trim();
        while (value.length() > 1 && (value.endsWith("/") || value.endsWith("\\"))) {
            value = value.substring(0, value.length() - 1);
        }
        return windows ? value.toLowerCase(Locale.ROOT) : value;
    }

    private static boolean isExistingDirectory(Path path) {
        try {
            return Files.isDirectory(path);
        } catch (SecurityException exception) {
            return false;
        }
    }

    private static boolean isWindows(String osName) {
        return osName.toLowerCase(Locale.ROOT).startsWith("windows");
    }
}
