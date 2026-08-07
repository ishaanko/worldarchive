package dev.ishaankot.worldarchive.core;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.function.Supplier;

/**
 * Shared segment-by-segment, no-follow-symlink directory validation and creation used by the
 * Git and ZIP storage backends. Callers supply how each failure is turned into their own checked
 * exception type and how a component's attributes are read, so each backend's exact behavior
 * (message text, exception type, and read-failure translation) is preserved.
 */
public final class PathGuards {
    private PathGuards() {
    }

    /** Reads a path component's attributes, translating a raw {@link IOException} if required. */
    @FunctionalInterface
    public interface AttributeReader<E extends Exception> {
        BasicFileAttributes read(Path path) throws IOException, E;
    }

    public static <E extends Exception> void createDirectories(
            Path directory,
            Supplier<E> onMissingRoot,
            AttributeReader<E> reader,
            Supplier<E> onInvalidComponent) throws IOException, E {
        Path absolute = directory.toAbsolutePath().normalize();
        Path current = absolute.getRoot();
        if (current == null) {
            throw onMissingRoot.get();
        }
        requireDirectoryComponent(current, reader, onInvalidComponent);
        for (Path segment : absolute) {
            current = current.resolve(segment);
            if (!Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
                try {
                    Files.createDirectory(current);
                } catch (FileAlreadyExistsException exception) {
                    // A racing creator is accepted only after validation below.
                }
            }
            requireDirectoryComponent(current, reader, onInvalidComponent);
        }
    }

    public static <E extends Exception> void requireDirectory(
            Path directory,
            Supplier<E> onMissingRoot,
            AttributeReader<E> reader,
            Supplier<E> onInvalidComponent) throws IOException, E {
        Path absolute = directory.toAbsolutePath().normalize();
        Path current = absolute.getRoot();
        if (current == null) {
            throw onMissingRoot.get();
        }
        requireDirectoryComponent(current, reader, onInvalidComponent);
        for (Path segment : absolute) {
            current = current.resolve(segment);
            requireDirectoryComponent(current, reader, onInvalidComponent);
        }
    }

    public static <E extends Exception> void requireDirectoryComponent(
            Path component,
            AttributeReader<E> reader,
            Supplier<E> onInvalidComponent) throws IOException, E {
        BasicFileAttributes attributes = reader.read(component);
        if (!FileSystemSafety.isOrdinaryDirectory(component, attributes)) {
            throw onInvalidComponent.get();
        }
    }
}
