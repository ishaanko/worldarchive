package dev.ishaankot.worldarchive.core;

import dev.ishaankot.worldarchive.model.BackupId;
import dev.ishaankot.worldarchive.model.BackupManifest;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
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
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

/** Copies a stable world view once into private outside-world staging. */
public final class FileSystemBackupCaptureFactory implements BackupCaptureFactory {
    private static final int COPY_BUFFER_BYTES = 64 * 1_024;

    private static final int WINDOWS_REPARSE_POINT = 0x400;

    private static final String CAPTURE_PREFIX = ".capture-";

    private static final String OWNERSHIP_SUFFIX = ".worldarchive-owner";

    private static final String OWNERSHIP_HEADER = "worldarchive-private-capture-v1:";

    private final Path captureDirectory;

    private final SourceCaptureObserver observer;

    public FileSystemBackupCaptureFactory(Path captureDirectory) {
        this(captureDirectory, SourceCaptureObserver.NONE);
    }

    public FileSystemBackupCaptureFactory(
            Path captureDirectory,
            SourceCaptureObserver observer) {
        this.captureDirectory = Objects.requireNonNull(captureDirectory, "captureDirectory")
                .toAbsolutePath()
                .normalize();
        this.observer = Objects.requireNonNull(observer, "observer");
        cleanupAtStartup();
    }

    public Path captureDirectory() {
        return captureDirectory;
    }

    @Override
    public CapturedBackup capture(
            CreateBackupRequest request,
            BackupId backupId,
            Instant createdAt,
            Optional<WorldInventory> previousInventory,
            CaptureProgressListener progressListener)
            throws IOException, InterruptedException {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(backupId, "backupId");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(previousInventory, "previousInventory");
        Objects.requireNonNull(progressListener, "progressListener");
        requireNotInterrupted();

        Path source = request.worldDirectory();
        prepareCaptureDirectory(source);
        CaptureLease lease = CaptureLease.create(captureDirectory);
        Path staging = lease.captureRoot();
        try {
            TreeSnapshot before = TreeSnapshot.scan(source);
            createDirectories(staging, before.directories());
            List<WorldInventory.Entry> inventoryEntries = copyFiles(
                    staging,
                    before,
                    progressListener);
            TreeSnapshot after = TreeSnapshot.scan(source);
            if (!before.equals(after)) {
                throw new IOException("World tree changed while its private capture was being created");
            }
            WorldInventory inventory = WorldInventory.create(inventoryEntries);
            long changedFiles = previousInventory
                    .map(inventory::changedFilesSince)
                    .orElse(inventory.fileCount());
            BackupManifest manifest = BackupManifest.create(
                    backupId,
                    request.worldId(),
                    request.worldName(),
                    request.label(),
                    createdAt,
                    request.trigger(),
                    inventory.fileCount(),
                    inventory.byteCount(),
                    changedFiles,
                    inventory.contentSha256(),
                    inventory.inventorySha256());
            makeReadOnly(staging);
            return new CapturedBackup(
                    new BackupCapture(staging, manifest),
                    inventory,
                    lease::close);
        } catch (IOException | InterruptedException | RuntimeException | Error failure) {
            lease.closeAfterFailure(failure);
            throw failure;
        }
    }

    private void prepareCaptureDirectory(Path source) throws IOException {
        Path normalizedSource = source.toAbsolutePath().normalize();
        if (normalizedSource.startsWith(captureDirectory)
                || captureDirectory.startsWith(normalizedSource)) {
            throw new IOException("Private captures must be stored outside the live world");
        }
        requireSafeDirectory(source, "World source is not a safe directory");
        Files.createDirectories(captureDirectory);
        requireSafeDirectory(captureDirectory, "Capture directory is not a safe directory");
        cleanupAbandonedCaptures();
        Path realSource = source.toRealPath(LinkOption.NOFOLLOW_LINKS);
        Path realCaptures = captureDirectory.toRealPath(LinkOption.NOFOLLOW_LINKS);
        if (realSource.startsWith(realCaptures) || realCaptures.startsWith(realSource)) {
            throw new IOException("Private captures must be stored outside the live world");
        }
    }

    private void cleanupAtStartup() {
        try {
            requireSafeDirectory(captureDirectory, "Capture directory is not a safe directory");
            cleanupAbandonedCaptures();
        } catch (NoSuchFileException ignored) {
            // The capture root is created lazily on the first backup.
        } catch (IOException ignored) {
            // Unsafe or unavailable roots are rejected by capture rather than mutated at startup.
        }
    }

    private void cleanupAbandonedCaptures() {
        List<Path> candidates;
        try (Stream<Path> entries = Files.list(captureDirectory)) {
            candidates = entries.sorted().toList();
        } catch (IOException exception) {
            return;
        }
        for (Path candidate : candidates) {
            cleanupAbandonedCapture(candidate);
        }
    }

    private void cleanupAbandonedCapture(Path candidate) {
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
        if (!attributes.isRegularFile()
                || attributes.isSymbolicLink()
                || attributes.isOther()
                || attributes.size() != expectedSize
                || isWindowsReparsePoint(marker)) {
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

    private List<WorldInventory.Entry> copyFiles(
            Path staging,
            TreeSnapshot snapshot,
            CaptureProgressListener progressListener)
            throws IOException, InterruptedException {
        List<WorldInventory.Entry> inventory = new ArrayList<>(snapshot.files().size());
        long completed = 0;
        safeProgress(progressListener, 0, snapshot.byteCount());
        for (SourceFile file : snapshot.files()) {
            requireNotInterrupted();
            observer.beforeFileCopy(Path.of(file.portablePath()));
            file.requireUnchanged();
            Path target = PortableWorldPath.resolveInside(staging, file.portablePath());
            CopyResult copied = copyFile(file.path(), target);
            observer.afterFileCopy(Path.of(file.portablePath()));
            file.requireUnchanged();
            if (copied.size() != file.fingerprint().size()) {
                throw new IOException("World file changed size while it was being captured");
            }
            inventory.add(new WorldInventory.Entry(
                    file.portablePath(),
                    copied.size(),
                    copied.sha256()));
            completed = Math.addExact(completed, copied.size());
            safeProgress(progressListener, completed, snapshot.byteCount());
        }
        return inventory;
    }

    private static void createDirectories(Path staging, List<SourceDirectory> directories)
            throws IOException {
        for (SourceDirectory directory : directories) {
            if (!directory.portablePath().isEmpty()) {
                Files.createDirectory(PortableWorldPath.resolveInside(
                        staging,
                        directory.portablePath()));
            }
        }
    }

    private static CopyResult copyFile(Path source, Path target) throws IOException {
        MessageDigest digest = sha256();
        byte[] buffer = new byte[COPY_BUFFER_BYTES];
        long size = 0;
        try (InputStream input = Files.newInputStream(source, LinkOption.NOFOLLOW_LINKS);
                OutputStream output = Files.newOutputStream(
                        target,
                        StandardOpenOption.CREATE_NEW,
                        StandardOpenOption.WRITE)) {
            int read;
            while ((read = input.read(buffer)) >= 0) {
                requireNotInterrupted();
                if (read == 0) {
                    continue;
                }
                output.write(buffer, 0, read);
                digest.update(buffer, 0, read);
                try {
                    size = Math.addExact(size, read);
                } catch (ArithmeticException exception) {
                    throw new IOException("World file size overflowed capture accounting", exception);
                }
                if (size > WorldInventory.MAXIMUM_BYTES) {
                    throw new IOException("World file exceeds the capture size limit");
                }
            }
        }
        return new CopyResult(size, HexFormat.of().formatHex(digest.digest()));
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("The Java runtime does not provide SHA-256", exception);
        }
    }

    private static void safeProgress(
            CaptureProgressListener listener,
            long completed,
            long total) {
        try {
            listener.onProgress(completed, total);
        } catch (RuntimeException ignored) {
            // Observers cannot invalidate a correctly captured source tree.
        }
    }

    private static void requireNotInterrupted() throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedIOException("World capture was interrupted");
        }
    }

    private static void requireSafeDirectory(Path path, String message) throws IOException {
        BasicFileAttributes attributes = Files.readAttributes(
                path,
                BasicFileAttributes.class,
                LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isDirectory()
                || attributes.isSymbolicLink()
                || attributes.isOther()
                || isWindowsReparsePoint(path)) {
            throw new IOException(message);
        }
    }

    private static void requireSafeFile(Path path, BasicFileAttributes attributes) throws IOException {
        if (!attributes.isRegularFile()
                || attributes.isSymbolicLink()
                || attributes.isOther()
                || isWindowsReparsePoint(path)) {
            throw new IOException("World source contains a link or special file");
        }
    }

    private static boolean isWindowsReparsePoint(Path path) throws IOException {
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
                    if (failure == null) {
                        failure = exception;
                    } else {
                        failure.addSuppressed(exception);
                    }
                }
            }
            for (Path path : capturedPaths.stream().sorted(Comparator.reverseOrder()).toList()) {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException exception) {
                    if (failure == null) {
                        failure = exception;
                    } else {
                        failure.addSuppressed(exception);
                    }
                }
            }
        }
        if (failure != null) {
            throw failure;
        }
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
                if (!attributes.isRegularFile()
                        || attributes.isSymbolicLink()
                        || attributes.isOther()
                        || isWindowsReparsePoint(file)) {
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
                            ? EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_EXECUTE)
                            : EnumSet.of(PosixFilePermission.OWNER_READ));
                }
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
                    : EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
        }
        DosFileAttributeView dos = Files.getFileAttributeView(
                path,
                DosFileAttributeView.class,
                LinkOption.NOFOLLOW_LINKS);
        if (dos != null) {
            dos.setReadOnly(false);
        }
    }

    private static final class CaptureLease implements AutoCloseable {
        private static final int MAXIMUM_CREATE_ATTEMPTS = 16;

        private final Path captureRoot;

        private final Path marker;

        private final byte[] markerContents;

        private final FileChannel markerChannel;

        private final FileLock markerLock;

        private boolean closed;

        private CaptureLease(
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

        private static CaptureLease create(Path captureDirectory) throws IOException {
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
                    return new CaptureLease(
                            captureRoot,
                            marker,
                            markerContents,
                            channel,
                            lock);
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

        private Path captureRoot() {
            return captureRoot;
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

        private void closeAfterFailure(Throwable failure) {
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
                    if (failure == null) {
                        failure = exception;
                    } else {
                        failure.addSuppressed(exception);
                    }
                    if (priorFailure != null && priorFailure != failure) {
                        priorFailure.addSuppressed(exception);
                    }
                }
            }
            if (channel.isOpen()) {
                try {
                    channel.close();
                } catch (IOException exception) {
                    if (failure == null) {
                        failure = exception;
                    } else {
                        failure.addSuppressed(exception);
                    }
                    if (priorFailure != null && priorFailure != failure) {
                        priorFailure.addSuppressed(exception);
                    }
                }
            }
            return failure;
        }
    }

    private record TreeSnapshot(
            List<SourceDirectory> directories,
            List<SourceFile> files,
            long byteCount) {
        private TreeSnapshot {
            directories = List.copyOf(directories);
            files = List.copyOf(files);
            if (byteCount < 0) {
                throw new IllegalArgumentException("Source snapshot byte count is negative");
            }
        }

        private static TreeSnapshot scan(Path source) throws IOException {
            requireNotInterrupted();
            ScanVisitor visitor = new ScanVisitor(source);
            Files.walkFileTree(source, visitor);
            visitor.directories.sort(Comparator
                    .comparingInt((SourceDirectory value) -> depth(value.portablePath()))
                    .thenComparing(SourceDirectory::portablePath));
            visitor.files.sort(Comparator.comparing(SourceFile::portablePath));
            return new TreeSnapshot(visitor.directories, visitor.files, visitor.byteCount);
        }

        private static int depth(String portablePath) {
            if (portablePath.isEmpty()) {
                return 0;
            }
            return (int) portablePath.chars().filter(character -> character == '/').count() + 1;
        }
    }

    private static final class ScanVisitor extends SimpleFileVisitor<Path> {
        private final Path source;

        private final List<SourceDirectory> directories = new ArrayList<>();

        private final List<SourceFile> files = new ArrayList<>();

        private final Map<String, EntryKind> collisionKinds = new HashMap<>();

        private final Set<String> observedPortablePaths = new HashSet<>();

        private long byteCount;

        private ScanVisitor(Path source) {
            this.source = source;
        }

        @Override
        public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes)
                throws IOException {
            requireNotInterrupted();
            requireSafeDirectory(directory, "World source contains a link or special directory");
            Path relative = source.relativize(directory);
            if (!relative.toString().isEmpty() && isExcluded(relative)) {
                return FileVisitResult.SKIP_SUBTREE;
            }
            String portable = relative.toString().isEmpty() ? "" : portable(relative);
            if (!portable.isEmpty()) {
                register(portable, EntryKind.DIRECTORY);
            }
            directories.add(new SourceDirectory(
                    directory,
                    portable,
                    DirectoryFingerprint.create(attributes)));
            requireCapacity();
            return FileVisitResult.CONTINUE;
        }

        @Override
        public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
            requireNotInterrupted();
            Path relative = source.relativize(file);
            if (isExcluded(relative) || isSessionLock(relative)) {
                return FileVisitResult.CONTINUE;
            }
            requireSafeFile(file, attributes);
            String portable = portable(relative);
            register(portable, EntryKind.FILE);
            files.add(new SourceFile(file, portable, FileFingerprint.create(attributes)));
            try {
                byteCount = Math.addExact(byteCount, attributes.size());
            } catch (ArithmeticException exception) {
                throw new IOException("World size overflowed capture accounting", exception);
            }
            if (byteCount > WorldInventory.MAXIMUM_BYTES) {
                throw new IOException("World is too large to capture");
            }
            requireCapacity();
            return FileVisitResult.CONTINUE;
        }

        @Override
        public FileVisitResult visitFileFailed(Path file, IOException exception) throws IOException {
            throw new IOException("A world source entry could not be read", exception);
        }

        @Override
        public FileVisitResult postVisitDirectory(Path directory, IOException exception) throws IOException {
            if (exception != null) {
                throw new IOException("The world source could not be traversed", exception);
            }
            return FileVisitResult.CONTINUE;
        }

        private void register(String portable, EntryKind kind) throws IOException {
            if (!observedPortablePaths.add(portable)) {
                throw new IOException("World contains a duplicate portable path");
            }
            String[] segments = portable.split("/");
            StringBuilder prefix = new StringBuilder();
            for (int index = 0; index < segments.length; index++) {
                if (!prefix.isEmpty()) {
                    prefix.append('/');
                }
                prefix.append(segments[index]);
                String key = PortableWorldPath.collisionKey(prefix.toString());
                EntryKind expected = index == segments.length - 1 ? kind : EntryKind.DIRECTORY;
                EntryKind previous = collisionKinds.putIfAbsent(key, expected);
                if (previous != null && (previous != expected || index == segments.length - 1)) {
                    throw new IOException("World contains paths that collide on another platform");
                }
            }
        }

        private void requireCapacity() throws IOException {
            if (files.size() > WorldInventory.MAXIMUM_FILES
                    || directories.size() > WorldInventory.MAXIMUM_FILES) {
                throw new IOException("World contains too many entries");
            }
        }

        private static String portable(Path relative) throws IOException {
            try {
                return PortableWorldPath.fromRelativePath(relative);
            } catch (IllegalArgumentException exception) {
                throw new IOException("World contains a path that cannot be restored safely", exception);
            }
        }

        private static boolean isExcluded(Path relative) {
            return relative.getNameCount() > 0
                    && relative.getName(0).toString().equalsIgnoreCase(".worldarchive");
        }

        private static boolean isSessionLock(Path relative) {
            return relative.getNameCount() == 1
                    && relative.getFileName().toString().equalsIgnoreCase("session.lock");
        }
    }

    private record SourceDirectory(
            Path path,
            String portablePath,
            DirectoryFingerprint fingerprint) {
        private SourceDirectory {
            Objects.requireNonNull(path, "path");
            Objects.requireNonNull(portablePath, "portablePath");
            Objects.requireNonNull(fingerprint, "fingerprint");
        }
    }

    private record SourceFile(
            Path path,
            String portablePath,
            FileFingerprint fingerprint) {
        private SourceFile {
            Objects.requireNonNull(path, "path");
            Objects.requireNonNull(portablePath, "portablePath");
            Objects.requireNonNull(fingerprint, "fingerprint");
        }

        private void requireUnchanged() throws IOException {
            BasicFileAttributes current = Files.readAttributes(
                    path,
                    BasicFileAttributes.class,
                    LinkOption.NOFOLLOW_LINKS);
            requireSafeFile(path, current);
            if (!fingerprint.equals(FileFingerprint.create(current))) {
                throw new IOException("World file changed while its private capture was being created");
            }
        }
    }

    private record FileFingerprint(
            Object fileKey,
            long size,
            FileTime lastModifiedTime,
            FileTime creationTime) {
        private FileFingerprint {
            if (size < 0) {
                throw new IllegalArgumentException("Source fingerprint size is negative");
            }
            Objects.requireNonNull(lastModifiedTime, "lastModifiedTime");
            Objects.requireNonNull(creationTime, "creationTime");
        }

        private static FileFingerprint create(BasicFileAttributes attributes) {
            return new FileFingerprint(
                    attributes.fileKey(),
                    attributes.size(),
                    attributes.lastModifiedTime(),
                    attributes.creationTime());
        }
    }

    /** Directory mtimes are deferred on Windows; identity and membership remain authoritative. */
    private record DirectoryFingerprint(
            Object fileKey,
            FileTime creationTime) {
        private DirectoryFingerprint {
            Objects.requireNonNull(creationTime, "creationTime");
        }

        private static DirectoryFingerprint create(BasicFileAttributes attributes) {
            return new DirectoryFingerprint(
                    attributes.fileKey(),
                    attributes.creationTime());
        }
    }

    private record CopyResult(long size, String sha256) {
    }

    private enum EntryKind {
        DIRECTORY,
        FILE
    }
}
