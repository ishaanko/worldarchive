package dev.ishaanko.worldarchive.core;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.AccessDeniedException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.DosFileAttributeView;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * The private folders that hold world captures, below one capture root. A workspace keeps its
 * captures in a folder named after a random owner ID and locks {@code <owner>.lock} next to it
 * for as long as the process runs. Another game instance that shares the storage folder can
 * therefore tell a capture in use from one that a crash left behind: {@link #removeAbandoned}
 * deletes only folders whose lock it can take. Everything else under the root is kept.
 */
final class CaptureWorkspace {
    private static final String LOCK_SUFFIX = ".lock";

    private static final Pattern OWNER_ID = Pattern.compile(
            "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");

    /** WorldArchive 0.4 named a capture {@code .capture-<id>} and kept an owner marker file beside it. */
    private static final Pattern LEGACY_CAPTURE = Pattern.compile(
            "\\.capture-" + OWNER_ID.pattern() + "(\\.worldarchive-owner)?");

    /**
     * Owners in this JVM. Their lock files are never opened a second time here, because closing
     * any channel on a file releases this process's POSIX lock on it.
     */
    private static final Set<String> LOCAL_OWNERS = ConcurrentHashMap.newKeySet();

    private final Path root;

    private final String owner = UUID.randomUUID().toString();

    // Guarded by this. Opened with the first capture and never closed: the lock must last as
    // long as the process, and the operating system releases it when the process ends.
    private FileChannel ownerLock;

    CaptureWorkspace(Path root) {
        this.root = Objects.requireNonNull(root, "root").toAbsolutePath().normalize();
    }

    /** Creates an empty private folder for one capture of {@code world}. */
    Lease open(Path world) throws IOException {
        requireOutside(world);
        Path capture = ownFolder().resolve(UUID.randomUUID().toString());
        Files.createDirectory(capture);
        return new Lease(capture);
    }

    /**
     * Deletes the capture folders of game instances that have ended, such as after a crash, and
     * the leftovers of WorldArchive 0.4. Run it once at startup, off the render thread.
     */
    void removeAbandoned() throws IOException {
        List<Path> entries;
        try (Stream<Path> listed = Files.list(root)) {
            entries = listed.toList();
        } catch (NoSuchFileException exception) {
            return;
        }
        IOException failure = null;
        for (Path entry : entries) {
            try {
                removeIfAbandoned(entry);
            } catch (IOException exception) {
                if (failure == null) {
                    failure = exception;
                } else {
                    failure.addSuppressed(exception);
                }
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    private void removeIfAbandoned(Path entry) throws IOException {
        String name = entry.getFileName().toString();
        if (LEGACY_CAPTURE.matcher(name).matches()) {
            deleteTree(entry);
            return;
        }
        String entryOwner = name.endsWith(LOCK_SUFFIX)
                ? name.substring(0, name.length() - LOCK_SUFFIX.length())
                : "";
        if (!OWNER_ID.matcher(entryOwner).matches() || LOCAL_OWNERS.contains(entryOwner)) {
            return;
        }
        FileChannel channel;
        try {
            channel = FileChannel.open(entry, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
        } catch (IOException exception) {
            return;
        }
        try (channel) {
            if (!tryLock(channel)) {
                return;
            }
            deleteTree(root.resolve(entryOwner));
            // Delete the lock file while holding it, so no instance sees a half-removed owner.
            Files.deleteIfExists(entry);
        }
    }

    /** False when another process holds the lock, or when it cannot be taken at all. */
    private static boolean tryLock(FileChannel channel) {
        try {
            return channel.tryLock() != null;
        } catch (IOException | OverlappingFileLockException exception) {
            return false;
        }
    }

    private synchronized Path ownFolder() throws IOException {
        if (ownerLock == null) {
            Files.createDirectories(root);
            FileChannel channel = FileChannel.open(
                    root.resolve(owner + LOCK_SUFFIX),
                    StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE);
            try {
                channel.lock();
            } catch (IOException | RuntimeException exception) {
                channel.close();
                throw exception;
            }
            LOCAL_OWNERS.add(owner);
            ownerLock = channel;
        }
        // Created again if someone deleted it while the game ran.
        return Files.createDirectories(root.resolve(owner));
    }

    private void requireOutside(Path world) throws IOException {
        Path normalized = world.toAbsolutePath().normalize();
        if (normalized.startsWith(root) || root.startsWith(normalized)) {
            throw overlap();
        }
        Files.createDirectories(root);
        Path realWorld = world.toRealPath();
        Path realRoot = root.toRealPath();
        if (realWorld.startsWith(realRoot) || realRoot.startsWith(realWorld)) {
            throw overlap();
        }
    }

    private IOException overlap() {
        return new IOException("The capture folder " + root + " overlaps the world folder, so the world "
                + "cannot be copied safely. Keep the WorldArchive folder outside the saves folder.");
    }

    /**
     * Deletes a folder tree without following links: a symbolic link or junction inside it is
     * removed itself, never its target. A missing tree is already deleted.
     */
    static void deleteTree(Path tree) throws IOException {
        Files.walkFileTree(tree, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes)
                    throws IOException {
                if (attributes.isOther()) {
                    delete(directory);
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException failure) throws IOException {
                if (failure instanceof NoSuchFileException) {
                    return FileVisitResult.CONTINUE;
                }
                throw failure;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path directory, IOException failure) throws IOException {
                if (failure != null) {
                    throw failure;
                }
                delete(directory);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    /** Deletes one entry. A capture left by WorldArchive 0.4 is read-only, so it is made writable first. */
    private static void delete(Path path) throws IOException {
        try {
            Files.deleteIfExists(path);
        } catch (AccessDeniedException exception) {
            makeWritable(path.getParent());
            makeWritable(path);
            Files.deleteIfExists(path);
        }
    }

    private static void makeWritable(Path path) throws IOException {
        PosixFileAttributeView posix = Files.getFileAttributeView(
                path, PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        if (posix != null) {
            posix.setPermissions(EnumSet.of(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.OWNER_EXECUTE));
            return;
        }
        DosFileAttributeView dos = Files.getFileAttributeView(
                path, DosFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        if (dos != null) {
            dos.setReadOnly(false);
        }
    }

    /** One private capture folder; closing the lease deletes the folder, and closing again does nothing. */
    record Lease(Path path) implements AutoCloseable {
        @Override
        public void close() throws IOException {
            deleteTree(path);
        }

        void closeAfterFailure(Throwable failure) {
            try {
                close();
            } catch (IOException cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
        }
    }
}
