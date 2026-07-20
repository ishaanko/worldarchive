package dev.ishaankot.worldarchive.core;

import dev.ishaankot.worldarchive.model.BackupId;
import dev.ishaankot.worldarchive.model.BackupManifest;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Copies a stable world view once into private outside-world staging. */
public final class FileSystemBackupCaptureFactory implements BackupCaptureFactory {
    private static final int COPY_BUFFER_BYTES = 64 * 1_024;

    private final CaptureWorkspace workspace;

    private final SourceCaptureObserver observer;

    public FileSystemBackupCaptureFactory(Path captureDirectory) {
        this(captureDirectory, SourceCaptureObserver.NONE);
    }

    public FileSystemBackupCaptureFactory(
            Path captureDirectory,
            SourceCaptureObserver observer) {
        this.workspace = new CaptureWorkspace(captureDirectory);
        this.observer = Objects.requireNonNull(observer, "observer");
    }

    public Path captureDirectory() {
        return workspace.directory();
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
        CaptureWorkspace.Lease lease = workspace.open(source);
        Path staging = lease.path();
        try {
            TreeSnapshot before = TreeSnapshot.scan(source);
            Set<String> contentProofRequired = new HashSet<>();
            createDirectories(staging, before.directories());
            List<WorldInventory.Entry> inventoryEntries = copyFiles(
                    staging,
                    before,
                    contentProofRequired,
                    progressListener);
            TreeSnapshot after = TreeSnapshot.scan(source);
            before.requireStableMatch(after, contentProofRequired);
            proveTimestampDriftDidNotChangeContent(
                    before,
                    inventoryEntries,
                    contentProofRequired);
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
            lease.seal();
            return new CapturedBackup(
                    new BackupCapture(staging, manifest),
                    inventory,
                    lease::close);
        } catch (IOException | InterruptedException | RuntimeException | Error failure) {
            lease.closeAfterFailure(failure);
            throw failure;
        }
    }

    private List<WorldInventory.Entry> copyFiles(
            Path staging,
            TreeSnapshot snapshot,
            Set<String> contentProofRequired,
            CaptureProgressListener progressListener)
            throws IOException, InterruptedException {
        List<WorldInventory.Entry> inventory = new ArrayList<>(snapshot.files().size());
        long completed = 0;
        safeProgress(progressListener, 0, snapshot.byteCount());
        for (SourceFile file : snapshot.files()) {
            requireNotInterrupted();
            observer.beforeFileCopy(Path.of(file.portablePath()));
            file.markTimestampDrift(contentProofRequired);
            Path target = PortableWorldPath.resolveInside(staging, file.portablePath());
            CopyResult copied = copyFile(file.path(), target);
            observer.afterFileCopy(Path.of(file.portablePath()));
            file.markTimestampDrift(contentProofRequired);
            if (copied.size() != file.fingerprint().size()) {
                throw new IOException(
                        "World file changed size while it was being captured: "
                                + file.portablePath());
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

    private static void proveTimestampDriftDidNotChangeContent(
            TreeSnapshot snapshot,
            List<WorldInventory.Entry> inventory,
            Set<String> contentProofRequired)
            throws IOException {
        if (contentProofRequired.isEmpty()) {
            return;
        }
        Map<String, WorldInventory.Entry> capturedByPath = new HashMap<>();
        for (WorldInventory.Entry entry : inventory) {
            capturedByPath.put(entry.path(), entry);
        }
        for (SourceFile file : snapshot.files()) {
            boolean requiresProof = contentProofRequired.contains(file.portablePath());
            requiresProof |= file.hasTimestampDrift();
            if (!requiresProof) {
                continue;
            }
            CopyResult current = hashFile(file.path());
            file.requireStableAttributes();
            WorldInventory.Entry captured = capturedByPath.get(file.portablePath());
            if (captured == null
                    || current.size() != captured.size()
                    || !current.sha256().equals(captured.sha256())) {
                throw new IOException(
                        "World file contents changed while its private capture was being created: "
                                + file.portablePath());
            }
        }
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
        try (InputStream input = Files.newInputStream(source, LinkOption.NOFOLLOW_LINKS);
                OutputStream output = Files.newOutputStream(
                        target,
                        StandardOpenOption.CREATE_NEW,
                        StandardOpenOption.WRITE)) {
            return copyAndHash(input, output);
        }
    }

    private static CopyResult hashFile(Path source) throws IOException {
        try (InputStream input = Files.newInputStream(source, LinkOption.NOFOLLOW_LINKS)) {
            return copyAndHash(input, OutputStream.nullOutputStream());
        }
    }

    private static CopyResult copyAndHash(InputStream input, OutputStream output) throws IOException {
        MessageDigest digest = sha256();
        byte[] buffer = new byte[COPY_BUFFER_BYTES];
        long size = 0;
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
        if (!FileSystemSafety.isOrdinaryDirectory(path, attributes)) {
            throw new IOException(message);
        }
    }

    private static void requireSafeFile(Path path, BasicFileAttributes attributes) throws IOException {
        if (!FileSystemSafety.isOrdinaryRegularFile(path, attributes)) {
            throw new IOException("World source contains a link or special file");
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

        private void requireStableMatch(
                TreeSnapshot current,
                Set<String> contentProofRequired)
                throws IOException {
            if (byteCount != current.byteCount
                    || !directories.equals(current.directories)
                    || files.size() != current.files.size()) {
                throw new IOException(
                        "World tree changed while its private capture was being created");
            }
            for (int index = 0; index < files.size(); index++) {
                SourceFile expected = files.get(index);
                SourceFile observed = current.files.get(index);
                if (!expected.hasSameStableFile(observed)) {
                    throw new IOException(
                            "World tree changed while its private capture was being created");
                }
                if (expected.fingerprint().hasTimestampDrift(observed.fingerprint())) {
                    contentProofRequired.add(expected.portablePath());
                }
            }
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

        private void markTimestampDrift(Set<String> contentProofRequired) throws IOException {
            if (hasTimestampDrift()) {
                contentProofRequired.add(portablePath);
            }
        }

        private boolean hasTimestampDrift() throws IOException {
            BasicFileAttributes current = Files.readAttributes(
                    path,
                    BasicFileAttributes.class,
                    LinkOption.NOFOLLOW_LINKS);
            requireSafeFile(path, current);
            FileFingerprint currentFingerprint = FileFingerprint.create(current);
            if (!fingerprint.hasSameStableAttributes(currentFingerprint)) {
                throw changedFileException();
            }
            return fingerprint.hasTimestampDrift(currentFingerprint);
        }

        private void requireStableAttributes() throws IOException {
            hasTimestampDrift();
        }

        private boolean hasSameStableFile(SourceFile other) {
            return path.equals(other.path)
                    && portablePath.equals(other.portablePath)
                    && fingerprint.hasSameStableAttributes(other.fingerprint);
        }

        private IOException changedFileException() {
            return new IOException(
                    "World file changed while its private capture was being created: "
                            + portablePath);
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
                    identityCreationTime(attributes));
        }

        private boolean hasSameStableAttributes(FileFingerprint other) {
            return Objects.equals(fileKey, other.fileKey)
                    && size == other.size
                    && creationTime.equals(other.creationTime);
        }

        private boolean hasTimestampDrift(FileFingerprint other) {
            return !lastModifiedTime.equals(other.lastModifiedTime);
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
                    identityCreationTime(attributes));
        }
    }

    private static FileTime identityCreationTime(BasicFileAttributes attributes) {
        // macOS may move birth time backwards when an older modification time is applied.
        return System.getProperty("os.name").startsWith("Mac")
                ? FileTime.fromMillis(0)
                : attributes.creationTime();
    }

    private record CopyResult(long size, String sha256) {
    }

    private enum EntryKind {
        DIRECTORY,
        FILE
    }
}
