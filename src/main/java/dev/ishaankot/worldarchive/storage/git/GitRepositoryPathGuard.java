package dev.ishaankot.worldarchive.storage.git;

import dev.ishaankot.worldarchive.core.FileSystemSafety;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Set;

/** Creates and opens repository control paths without following links. */
final class GitRepositoryPathGuard {
    private GitRepositoryPathGuard() {
    }

    static void createDirectories(Path directory) throws IOException, GitStorageException {
        Path absolute = directory.toAbsolutePath().normalize();
        Path current = absolute.getRoot();
        if (current == null) {
            throw new GitStorageException("Git repository path has no filesystem root");
        }
        requireDirectory(current);
        for (Path segment : absolute) {
            current = current.resolve(segment);
            if (!Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
                try {
                    Files.createDirectory(current);
                } catch (FileAlreadyExistsException exception) {
                    // A racing creator is accepted only after validation below.
                }
            }
            requireDirectory(current);
        }
    }

    static void requireDirectory(Path directory) throws IOException, GitStorageException {
        Path absolute = directory.toAbsolutePath().normalize();
        Path current = absolute.getRoot();
        if (current == null) {
            throw new GitStorageException("Git repository path has no filesystem root");
        }
        requireDirectoryComponent(current);
        for (Path segment : absolute) {
            current = current.resolve(segment);
            requireDirectoryComponent(current);
        }
    }

    static FileChannel openLockFile(Path lockPath) throws IOException, GitStorageException {
        Path absolute = lockPath.toAbsolutePath().normalize();
        Path parent = absolute.getParent();
        if (parent == null) {
            throw new GitStorageException("Git repository lock has no parent directory");
        }
        createDirectories(parent);
        if (Files.exists(absolute, LinkOption.NOFOLLOW_LINKS)) {
            BasicFileAttributes existing = Files.readAttributes(
                    absolute,
                    BasicFileAttributes.class,
                    LinkOption.NOFOLLOW_LINKS);
            if (!FileSystemSafety.isOrdinaryRegularFile(absolute, existing)) {
                throw new GitStorageException("Git repository lock is not a regular file");
            }
        }
        Set<OpenOption> options = Set.of(
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
                LinkOption.NOFOLLOW_LINKS);
        FileChannel channel = FileChannel.open(absolute, options);
        try {
            BasicFileAttributes attributes = Files.readAttributes(
                    absolute,
                    BasicFileAttributes.class,
                    LinkOption.NOFOLLOW_LINKS);
            if (!FileSystemSafety.isOrdinaryRegularFile(absolute, attributes)) {
                throw new GitStorageException("Git repository lock is not a regular file");
            }
            return channel;
        } catch (IOException | GitStorageException | RuntimeException exception) {
            channel.close();
            throw exception;
        }
    }

    private static void requireDirectoryComponent(Path component)
            throws IOException, GitStorageException {
        BasicFileAttributes attributes = Files.readAttributes(
                component,
                BasicFileAttributes.class,
                LinkOption.NOFOLLOW_LINKS);
        if (!FileSystemSafety.isOrdinaryDirectory(component, attributes)) {
            throw new GitStorageException("Git repository path contains a link or special entry");
        }
    }
}
