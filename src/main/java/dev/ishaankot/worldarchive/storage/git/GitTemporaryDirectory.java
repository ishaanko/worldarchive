package dev.ishaankot.worldarchive.storage.git;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

/** Best-effort cleanup for private Git workspaces, preserving lock-bearing directories. */
final class GitTemporaryDirectory {
    private GitTemporaryDirectory() {}

    static void deleteUnlessLocked(Path directory) {
        try (Stream<Path> paths = Files.walk(directory)) {
            if (paths.anyMatch(path -> path.getFileName() != null
                    && path.getFileName().toString().endsWith(".lock"))) {
                return;
            }
        } catch (IOException ignored) {
            return;
        }
        try (Stream<Path> paths = Files.walk(directory)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        } catch (IOException ignored) {
            // A private temporary directory can be reclaimed by the operating system later.
        }
    }
}
