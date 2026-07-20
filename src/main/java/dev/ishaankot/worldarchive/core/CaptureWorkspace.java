package dev.ishaankot.worldarchive.core;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
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
import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

/** Owns allocation, recovery, sealing, and deletion of private capture directories. */
final class CaptureWorkspace {
    private static final String CAPTURE_PREFIX = ".capture-";

    private static final String OWNERSHIP_SUFFIX = ".worldarchive-owner";

    private static final String OWNERSHIP_HEADER = "worldarchive-private-capture-v1:";

    private final Path directory;

    CaptureWorkspace(Path directory) {
        this.directory = Objects.requireNonNull(directory, "directory")
                .toAbsolutePath()
                .normalize();
        cleanupAtStartup();
    }

    Path directory() {
        return directory;
    }

    Lease open(Path source) throws IOException {
        Path normalizedSource = source.toAbsolutePath().normalize();
        if (normalizedSource.startsWith(directory) || directory.startsWith(normalizedSource)) {
            throw new IOException("Private captures must be stored outside the live world");
        }
        requireSafeDirectory(source, "World source is not a safe directory");
        Files.createDirectories(directory);
        requireSafeDirectory(directory, "Capture directory is not a safe directory");
        cleanupAbandonedCaptures();
        Path realSource = source.toRealPath(LinkOption.NOFOLLOW_LINKS);
        Path realCaptures = directory.toRealPath(LinkOption.NOFOLLOW_LINKS);
        if (realSource.startsWith(realCaptures) || realCaptures.startsWith(realSource)) {
            throw new IOException("Private captures must be stored outside the live world");
        }
        return Lease.create(directory);
    }

    private void cleanupAtStartup() {
        try {
            requireSafeDirectory(directory, "Capture directory is not a safe directory");
            cleanupAbandonedCaptures();
        } catch (NoSuchFileException ignored) {
            // The capture root is created lazily on the first backup.
        } catch (IOException ignored) {
            // Unsafe or unavailable roots are rejected by open rather than mutated at startup.
        }
    }

    private void cleanupAbandonedCaptures() {
        List<Path> candidates;
        try (Stream<Path> entries = Files.list(directory)) {
            candidates = entries.sorted().toList();
        } catch (IOException exception) {
            return;
        }
        for (Path candidate : candidates) {
            cleanupAbandonedCapture(candidate);
        }
    }

    private static void cleanupAbandonedCapture(Path candidate) {
        Optional<UUID> captureId = parseCaptureId(candidate);
        if (captureId.isEmpty()) {
            return;
        }
        Path marker = ownershipMarker(candidate);
        byte[] expectedMarker = ownershipMarkerContents(captureId.orElseThrow());
        boolean deleted = false;
        try {
            requireSafeDirectory(candidate, "Private capture is not a safe directory");
            requireSafeOwnershipMarker(marker, expectedMarker);
            try (FileChannel channel = openOwnershipMarker(marker)) {
                FileLock lock;
                try {
                    lock = channel.tryLock();
                } catch (OverlappingFileLockException exception) {
                    return;
                }
                if (lock == null) {
                    return;
                }
                try (lock) {
                    requireSafeOwnershipMarkerPath(marker, expectedMarker.length);
                    if (!hasExactContents(channel, expectedMarker)) {
                        return;
                    }
                    requireSafeCaptureTree(candidate);
                    deleteTree(candidate);
                    deleted = isAbsent(candidate);
                }
            }
        } catch (IOException | RuntimeException exception) {
            return;
        }
        if (deleted) {
            deleteOwnershipMarkerIfSafe(marker, expectedMarker);
        }
    }

    private static Optional<UUID> parseCaptureId(Path candidate) {
        Path namePath = candidate.getFileName();
        if (namePath == null) {
            return Optional.empty();
        }
        String name = namePath.toString();
        if (!name.startsWith(CAPTURE_PREFIX)) {
            return Optional.empty();
        }
        String encodedId = name.substring(CAPTURE_PREFIX.length());
        try {
            UUID captureId = UUID.fromString(encodedId);
            return captureId.toString().equals(encodedId)
                    ? Optional.of(captureId)
                    : Optional.empty();
        } catch (IllegalArgumentException exception) {
            return Optional.empty();
        }
    }

    private static Path ownershipMarker(Path captureRoot) {
        return captureRoot.resolveSibling(captureRoot.getFileName() + OWNERSHIP_SUFFIX);
    }

    private static byte[] ownershipMarkerContents(UUID captureId) {
        return (OWNERSHIP_HEADER + captureId + '\n').getBytes(StandardCharsets.UTF_8);
    }

    private static FileChannel openOwnershipMarker(Path marker) throws IOException {
        return FileChannel.open(
                marker,
                StandardOpenOption.READ,
                StandardOpenOption.WRITE,
                LinkOption.NOFOLLOW_LINKS);
    }

    private static void requireSafeOwnershipMarker(Path marker, byte[] expected) throws IOException {
        requireSafeOwnershipMarkerPath(marker, expected.length);
        try (FileChannel channel = FileChannel.open(
                marker,
                StandardOpenOption.READ,
                LinkOption.NOFOLLOW_LINKS)) {
            if (!hasExactContents(channel, expected)) {
                throw new IOException("Capture ownership marker is unsafe");
            }
        }
    }

    private static void requireSafeOwnershipMarkerPath(Path marker, int expectedSize)
            throws IOException {
        BasicFileAttributes attributes = Files.readAttributes(
                marker,
                BasicFileAttributes.class,
                LinkOption.NOFOLLOW_LINKS);
        if (!FileSystemSafety.isOrdinaryRegularFile(marker, attributes)
                || attributes.size() != expectedSize) {
            throw new IOException("Capture ownership marker is unsafe");
        }
    }

    private static boolean hasExactContents(FileChannel channel, byte[] expected) throws IOException {
        if (channel.size() != expected.length) {
            return false;
        }
        channel.position(0);
        ByteBuffer contents = ByteBuffer.allocate(expected.length);
        while (contents.hasRemaining()) {
            if (channel.read(contents) < 0) {
                return false;
            }
        }
        return Arrays.equals(contents.array(), expected);
    }

    private static void deleteOwnershipMarkerIfSafe(Path marker, byte[] expected) {
        boolean interrupted = Thread.interrupted();
        try {
            requireSafeOwnershipMarker(marker, expected);
            Files.deleteIfExists(marker);
        } catch (IOException ignored) {
            // A changed or unavailable marker is preserved instead of deleting an uncertain path.
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static void requireSafeDirectory(Path path, String message) throws IOException {
        BasicFileAttributes attributes = Files.readAttributes(
                path,
                BasicFileAttributes.class,
                LinkOption.NOFOLLOW_LINKS);
        if (!FileSystemSafety.isOrdinaryDirectory(path, attributes)) {
            throw new IOException(message);
        }
    }

    private static void deleteTree(Path root) throws IOException {
        if (isAbsent(root)) {
            return;
        }
        requireSafeCaptureTree(root);
        IOException failure = null;
        try (Stream<Path> paths = Files.walk(root)) {
            List<Path> capturedPaths = paths.toList();
            for (Path path : capturedPaths) {
                try {
                    makeWritable(path);
                } catch (IOException exception) {
                    failure = accumulate(failure, exception);
                }
            }
            for (Path path : capturedPaths.stream().sorted(Comparator.reverseOrder()).toList()) {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException exception) {
                    failure = accumulate(failure, exception);
                }
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    private static IOException accumulate(IOException failure, IOException addition) {
        if (failure == null) {
            return addition;
        }
        failure.addSuppressed(addition);
        return failure;
    }

    private static boolean isAbsent(Path path) throws IOException {
        try {
            Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            return false;
        } catch (NoSuchFileException exception) {
            return true;
        }
    }

    private static void requireSafeCaptureTree(Path root) throws IOException {
        requireSafeDirectory(root, "Private capture is not a safe directory");
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes)
                    throws IOException {
                requireSafeDirectory(directory, "Private capture contains an unsafe directory");
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes)
                    throws IOException {
                if (!FileSystemSafety.isOrdinaryRegularFile(file, attributes)) {
                    throw new IOException("Private capture contains an unsafe file");
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exception)
                    throws IOException {
                throw new IOException("Private capture could not be inspected safely", exception);
            }

            @Override
            public FileVisitResult postVisitDirectory(Path directory, IOException exception)
                    throws IOException {
                if (exception != null) {
                    throw new IOException("Private capture could not be inspected safely", exception);
                }
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static void makeReadOnly(Path root) throws IOException {
        try (Stream<Path> paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                PosixFileAttributeView posix = Files.getFileAttributeView(
                        path,
                        PosixFileAttributeView.class,
                        LinkOption.NOFOLLOW_LINKS);
                if (posix != null) {
                    posix.setPermissions(Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)
                            ? EnumSet.of(
                                    PosixFilePermission.OWNER_READ,
                                    PosixFilePermission.OWNER_EXECUTE)
                            : EnumSet.of(PosixFilePermission.OWNER_READ));
                } else {
                    DosFileAttributeView dos = Files.getFileAttributeView(
                            path,
                            DosFileAttributeView.class,
                            LinkOption.NOFOLLOW_LINKS);
                    if (dos != null) {
                        dos.setReadOnly(true);
                    }
                }
            }
        }
    }

    private static void makeWritable(Path path) throws IOException {
        PosixFileAttributeView posix = Files.getFileAttributeView(
                path,
                PosixFileAttributeView.class,
                LinkOption.NOFOLLOW_LINKS);
        if (posix != null) {
            posix.setPermissions(Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)
                    ? EnumSet.of(
                            PosixFilePermission.OWNER_READ,
                            PosixFilePermission.OWNER_WRITE,
                            PosixFilePermission.OWNER_EXECUTE)
                    : EnumSet.of(
                            PosixFilePermission.OWNER_READ,
                            PosixFilePermission.OWNER_WRITE));
        } else {
            DosFileAttributeView dos = Files.getFileAttributeView(
                    path,
                    DosFileAttributeView.class,
                    LinkOption.NOFOLLOW_LINKS);
            if (dos != null) {
                dos.setReadOnly(false);
            }
        }
    }

    static final class Lease implements AutoCloseable {
        private static final int MAXIMUM_CREATE_ATTEMPTS = 16;

        private final Path captureRoot;

        private final Path marker;

        private final byte[] markerContents;

        private final FileChannel markerChannel;

        private final FileLock markerLock;

        private boolean closed;

        private Lease(
                Path captureRoot,
                Path marker,
                byte[] markerContents,
                FileChannel markerChannel,
                FileLock markerLock) {
            this.captureRoot = captureRoot;
            this.marker = marker;
            this.markerContents = markerContents;
            this.markerChannel = markerChannel;
            this.markerLock = markerLock;
        }

        private static Lease create(Path captureDirectory) throws IOException {
            for (int attempt = 0; attempt < MAXIMUM_CREATE_ATTEMPTS; attempt++) {
                UUID captureId = UUID.randomUUID();
                Path captureRoot = captureDirectory.resolve(CAPTURE_PREFIX + captureId);
                Path marker = ownershipMarker(captureRoot);
                byte[] markerContents = ownershipMarkerContents(captureId);
                try {
                    Files.createDirectory(captureRoot);
                } catch (FileAlreadyExistsException exception) {
                    continue;
                }
                FileChannel channel;
                try {
                    channel = FileChannel.open(
                            marker,
                            StandardOpenOption.CREATE_NEW,
                            StandardOpenOption.READ,
                            StandardOpenOption.WRITE,
                            LinkOption.NOFOLLOW_LINKS);
                } catch (FileAlreadyExistsException exception) {
                    deleteFreshCapture(captureRoot, exception);
                    if (exception.getSuppressed().length > 0) {
                        throw exception;
                    }
                    continue;
                } catch (IOException | RuntimeException | Error failure) {
                    deleteFreshCapture(captureRoot, failure);
                    throw failure;
                }
                FileLock lock = null;
                try {
                    lock = channel.lock();
                    writeMarker(channel, markerContents);
                    return new Lease(captureRoot, marker, markerContents, channel, lock);
                } catch (IOException | RuntimeException | Error failure) {
                    closeControlFile(lock, channel, failure);
                    deleteOwnershipMarkerIfSafe(marker, markerContents);
                    deleteFreshCapture(captureRoot, failure);
                    throw failure;
                }
            }
            throw new IOException("Could not allocate a unique private capture directory");
        }

        private static void deleteFreshCapture(Path captureRoot, Throwable failure) {
            try {
                Files.deleteIfExists(captureRoot);
            } catch (IOException cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
        }

        private static void writeMarker(FileChannel channel, byte[] contents) throws IOException {
            channel.position(0);
            ByteBuffer buffer = ByteBuffer.wrap(contents);
            while (buffer.hasRemaining()) {
                channel.write(buffer);
            }
            channel.force(true);
        }

        Path path() {
            return captureRoot;
        }

        void seal() throws IOException {
            makeReadOnly(captureRoot);
        }

        @Override
        public synchronized void close() throws IOException {
            if (closed) {
                return;
            }
            closed = true;
            IOException failure = null;
            boolean deleted = false;
            try {
                deleteTree(captureRoot);
                deleted = isAbsent(captureRoot);
            } catch (IOException exception) {
                failure = exception;
            }
            failure = closeControlFile(markerLock, markerChannel, failure);
            if (deleted) {
                deleteOwnershipMarkerIfSafe(marker, markerContents);
            }
            if (failure != null) {
                throw failure;
            }
        }

        void closeAfterFailure(Throwable failure) {
            try {
                close();
            } catch (IOException cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
        }

        private static IOException closeControlFile(
                FileLock lock,
                FileChannel channel,
                Throwable priorFailure) {
            IOException failure = priorFailure instanceof IOException exception ? exception : null;
            if (lock != null && lock.isValid()) {
                try {
                    lock.release();
                } catch (IOException exception) {
                    failure = accumulate(failure, exception);
                    if (priorFailure != null && priorFailure != failure) {
                        priorFailure.addSuppressed(exception);
                    }
                }
            }
            if (channel.isOpen()) {
                try {
                    channel.close();
                } catch (IOException exception) {
                    failure = accumulate(failure, exception);
                    if (priorFailure != null && priorFailure != failure) {
                        priorFailure.addSuppressed(exception);
                    }
                }
            }
            return failure;
        }
    }
}
