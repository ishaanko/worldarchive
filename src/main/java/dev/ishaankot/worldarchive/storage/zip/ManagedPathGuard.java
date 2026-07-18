package dev.ishaankot.worldarchive.storage.zip;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Map;

/** Rejects links, junctions, and other reparse components in managed storage paths. */
final class ManagedPathGuard {
    private static final int WINDOWS_REPARSE_POINT = 0x400;

    private ManagedPathGuard() {
    }

    static void createDirectories(Path directory, String message) throws IOException {
        Path absolute = requireAbsolute(directory);
        Path current = absolute.getRoot();
        if (current == null) {
            throw new ZipBackupException(message);
        }
        requireDirectoryComponent(current, message);
        for (Path segment : absolute) {
            current = current.resolve(segment);
            if (!Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
                try {
                    Files.createDirectory(current);
                } catch (FileAlreadyExistsException exception) {
                    // A racing creator is accepted only after the same no-link validation.
                }
            }
            requireDirectoryComponent(current, message);
        }
    }

    static void requireDirectory(Path directory, String message) throws IOException {
        Path absolute = requireAbsolute(directory);
        Path current = absolute.getRoot();
        if (current == null) {
            throw new ZipBackupException(message);
        }
        requireDirectoryComponent(current, message);
        for (Path segment : absolute) {
            current = current.resolve(segment);
            requireDirectoryComponent(current, message);
        }
    }

    static void requireExistingAncestors(Path path, String message) throws IOException {
        Path absolute = requireAbsolute(path);
        Path current = absolute.getRoot();
        if (current == null) {
            throw new ZipBackupException(message);
        }
        requireDirectoryComponent(current, message);
        for (Path segment : absolute) {
            current = current.resolve(segment);
            if (!Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
                return;
            }
            requireDirectoryComponent(current, message);
        }
    }

    static BasicFileAttributes requireRegularFile(Path file, String message) throws IOException {
        Path absolute = requireAbsolute(file);
        Path parent = absolute.getParent();
        if (parent == null) {
            throw new ZipBackupException(message);
        }
        requireDirectory(parent, message);
        BasicFileAttributes attributes = read(absolute, message);
        if (!attributes.isRegularFile()
                || attributes.isSymbolicLink()
                || attributes.isOther()
                || isWindowsReparsePoint(absolute, message)) {
            throw new ZipBackupException(message);
        }
        return attributes;
    }

    static boolean isSafeRegularFile(Path file) {
        try {
            requireRegularFile(file, "Managed file is not a safe regular file");
            return true;
        } catch (IOException | RuntimeException exception) {
            return false;
        }
    }

    static void createChildDirectories(Path trustedRoot, Path directory, String message)
            throws IOException {
        Path root = requireAbsolute(trustedRoot);
        Path target = requireAbsolute(directory);
        requireDirectory(root, message);
        if (!target.startsWith(root)) {
            throw new ZipBackupException(message);
        }
        Path current = root;
        for (Path segment : root.relativize(target)) {
            current = current.resolve(segment);
            if (!Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
                try {
                    Files.createDirectory(current);
                } catch (FileAlreadyExistsException exception) {
                    // Validate the racing entry below.
                }
            }
            requireDirectoryComponent(current, message);
        }
    }

    private static Path requireAbsolute(Path path) throws ZipBackupException {
        Path absolute = path.toAbsolutePath().normalize();
        if (!absolute.isAbsolute()) {
            throw new ZipBackupException("Managed path is not absolute");
        }
        return absolute;
    }

    private static void requireDirectoryComponent(Path component, String message) throws IOException {
        BasicFileAttributes attributes = read(component, message);
        if (!attributes.isDirectory()
                || attributes.isSymbolicLink()
                || attributes.isOther()
                || isWindowsReparsePoint(component, message)) {
            throw new ZipBackupException(message);
        }
    }

    private static boolean isWindowsReparsePoint(Path path, String message) throws IOException {
        if (!"\\".equals(path.getFileSystem().getSeparator())) {
            return false;
        }
        try {
            Map<String, Object> attributes = Files.readAttributes(
                    path,
                    "dos:attributes",
                    LinkOption.NOFOLLOW_LINKS);
            Object raw = attributes.get("attributes");
            return raw instanceof Integer value && (value & WINDOWS_REPARSE_POINT) != 0;
        } catch (UnsupportedOperationException | IllegalArgumentException exception) {
            return false;
        } catch (IOException exception) {
            throw new ZipBackupException(message, exception);
        }
    }

    private static BasicFileAttributes read(Path path, String message) throws IOException {
        try {
            return Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        } catch (IOException exception) {
            throw new ZipBackupException(message, exception);
        }
    }
}
