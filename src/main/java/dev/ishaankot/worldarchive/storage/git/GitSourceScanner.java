package dev.ishaankot.worldarchive.storage.git;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Map;

/** Rejects linked or special live-world entries before Git can traverse the source. */
final class GitSourceScanner {
    private static final int WINDOWS_REPARSE_POINT = 0x400;

    private GitSourceScanner() {
    }

    static void requireSafeTree(Path worldDirectory) throws IOException, GitStorageException {
        GitRepositoryPathGuard.requireDirectory(worldDirectory);
        BasicFileAttributes root = read(worldDirectory);
        try {
            requireDirectory(worldDirectory, root);
            Files.walkFileTree(worldDirectory, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes)
                        throws IOException {
                    requireDirectory(directory, attributes);
                    Path relative = worldDirectory.relativize(directory);
                    if (isExcludedTree(relative)) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                    requireOrdinaryFile(file, attributes);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exception) throws IOException {
                    throw new UnsafeSourceException("A live-world source entry could not be read", exception);
                }

                @Override
                public FileVisitResult postVisitDirectory(Path directory, IOException exception) throws IOException {
                    if (exception != null) {
                        throw new UnsafeSourceException("The live-world source could not be traversed", exception);
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (UnsafeSourceException exception) {
            throw new GitStorageException(exception.getMessage(), exception);
        }
    }

    static void requireDirectory(Path path, BasicFileAttributes attributes)
            throws IOException {
        if (!attributes.isDirectory()
                || attributes.isSymbolicLink()
                || attributes.isOther()
                || isWindowsReparsePoint(path)) {
            throw new UnsafeSourceException("Live-world source contains a linked or special directory");
        }
    }

    static void requireOrdinaryFile(Path path, BasicFileAttributes attributes)
            throws IOException {
        if (!attributes.isRegularFile()
                || attributes.isSymbolicLink()
                || attributes.isOther()
                || isWindowsReparsePoint(path)) {
            throw new UnsafeSourceException("Live-world source contains a link or special entry");
        }
    }

    private static BasicFileAttributes read(Path path) throws IOException {
        return Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
    }

    private static boolean isExcludedTree(Path relative) {
        return relative.getNameCount() > 0
                && relative.getName(0).toString().equalsIgnoreCase(".worldarchive");
    }

    static boolean isWindowsReparsePoint(Path path) throws IOException {
        try {
            Map<String, Object> attributes = Files.readAttributes(
                    path,
                    "dos:attributes",
                    LinkOption.NOFOLLOW_LINKS);
            Object raw = attributes.get("attributes");
            return raw instanceof Integer value && (value & WINDOWS_REPARSE_POINT) != 0;
        } catch (UnsupportedOperationException | IllegalArgumentException exception) {
            return false;
        }
    }

    private static final class UnsafeSourceException extends IOException {
        private UnsafeSourceException(String message) {
            super(message);
        }

        private UnsafeSourceException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
