package dev.ishaanko.worldarchive.storage.git;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Deletes WorldArchive's own temporary trees without following links: a link or a Windows
 * junction inside is removed itself, never its target.
 */
final class GitTemporaryFiles {
    private static final Logger LOGGER = LoggerFactory.getLogger("WorldArchive");

    private GitTemporaryFiles() {
    }

    /** Deletes a tree; a failure is logged and the rest stays for the next sweep. */
    static void deleteTree(Path root) {
        try {
            delete(root);
        } catch (IOException exception) {
            LOGGER.warn("WorldArchive could not remove temporary files in {}: {}", root, exception.toString());
        }
    }

    /** Deletes everything inside a folder and keeps the folder, creating it when missing. */
    static void deleteContents(Path folder) throws IOException {
        Files.createDirectories(folder);
        try (Stream<Path> children = Files.list(folder)) {
            for (Path child : children.toList()) {
                delete(child);
            }
        }
    }

    /** Deletes a file or a tree, bottom up. */
    static void delete(Path root) throws IOException {
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes)
                    throws IOException {
                if (attributes.isOther() || attributes.isSymbolicLink()) {
                    Files.delete(directory);
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path directory, IOException failure) throws IOException {
                if (failure != null) {
                    throw failure;
                }
                Files.delete(directory);
                return FileVisitResult.CONTINUE;
            }
        });
    }
}
