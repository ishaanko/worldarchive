package dev.ishaankot.worldarchive.storage.git;

import com.sun.nio.file.ExtendedOpenOption;
import dev.ishaankot.worldarchive.model.BackupManifest;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Immutable private capture that native Git may traverse without touching the live world. */
final class GitSourceCapture implements AutoCloseable {
    private static final int COPY_BUFFER_BYTES = 64 * 1_024;

    private final Path root;

    private final GitInventory inventory;

    private GitSourceCapture(Path root, GitInventory inventory) {
        this.root = root;
        this.inventory = inventory;
    }

    static GitSourceCapture create(Path source, BackupManifest manifest)
            throws IOException, GitStorageException {
        return create(source, manifest, CaptureHook.NONE);
    }

    static GitSourceCapture create(Path source, BackupManifest manifest, CaptureHook hook)
            throws IOException, GitStorageException {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(manifest, "manifest");
        Objects.requireNonNull(hook, "hook");
        Path privateRoot = Files.createTempDirectory("worldarchive-git-capture-");
        try {
            CaptureVisitor visitor = new CaptureVisitor(source, privateRoot, hook);
            GitSourceScanner.requireSafeTree(source);
            Files.walkFileTree(source, visitor);
            visitor.requireDirectoriesUnchanged();
            GitSourceScanner.requireSafeTree(source);
            GitInventory inventory = GitInventory.create(visitor.files);
            inventory.requireMatches(manifest);
            return new GitSourceCapture(privateRoot, inventory);
        } catch (IOException | GitStorageException | RuntimeException exception) {
            GitTemporaryFiles.deleteTree(privateRoot);
            throw exception;
        }
    }

    Path root() {
        return root;
    }

    GitInventory inventory() {
        return inventory;
    }

    @Override
    public void close() {
        GitTemporaryFiles.deleteTree(root);
    }

    interface CaptureHook {
        CaptureHook NONE = new CaptureHook() {
        };

        default void beforeFileCopy(Path relative) throws IOException {
        }

        default void afterFileCopy(Path relative) throws IOException {
        }
    }

    private static final class CaptureVisitor extends SimpleFileVisitor<Path> {
        private final Path source;

        private final Path destination;

        private final CaptureHook hook;

        private final List<GitInventoryEntry> files = new ArrayList<>();

        private final List<DirectoryFingerprint> directories = new ArrayList<>();

        private final Map<String, PathKind> collisionKinds = new HashMap<>();

        private int entries;

        private CaptureVisitor(Path source, Path destination, CaptureHook hook) {
            this.source = source;
            this.destination = destination;
            this.hook = hook;
        }

        @Override
        public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes)
                throws IOException {
            countEntry();
            GitSourceScanner.requireDirectory(directory, attributes);
            Path relative = source.relativize(directory);
            if (directory.equals(source)) {
                directories.add(DirectoryFingerprint.create(directory, attributes));
                return FileVisitResult.CONTINUE;
            }
            if (relative.getNameCount() == 1
                    && relative.getFileName().toString().equalsIgnoreCase(".worldarchive")) {
                return FileVisitResult.SKIP_SUBTREE;
            }
            String portable = portable(relative);
            register(portable, PathKind.DIRECTORY);
            try {
                Files.createDirectory(GitPortablePath.resolveInside(destination, portable));
            } catch (GitStorageException exception) {
                throw new UnsafeCaptureException(exception.getMessage(), exception);
            }
            directories.add(DirectoryFingerprint.create(directory, attributes));
            return FileVisitResult.CONTINUE;
        }

        @Override
        public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
            countEntry();
            GitSourceScanner.requireOrdinaryFile(file, attributes);
            Path relative = source.relativize(file);
            String portable = portable(relative);
            if (relative.getNameCount() == 1 && portable.equalsIgnoreCase("session.lock")) {
                return FileVisitResult.CONTINUE;
            }
            register(portable, PathKind.FILE);
            Path target;
            try {
                target = GitPortablePath.resolveInside(destination, portable);
            } catch (GitStorageException exception) {
                throw new UnsafeCaptureException(exception.getMessage(), exception);
            }
            FileFingerprint before = FileFingerprint.create(attributes);
            String sha256;
            long size;
            hook.beforeFileCopy(relative);
            try {
                CopyResult copied = copyFile(file, target);
                sha256 = copied.sha256();
                size = copied.size();
            } finally {
                hook.afterFileCopy(relative);
            }
            BasicFileAttributes after = Files.readAttributes(
                    file,
                    BasicFileAttributes.class,
                    LinkOption.NOFOLLOW_LINKS);
            GitSourceScanner.requireOrdinaryFile(file, after);
            if (!before.equals(FileFingerprint.create(after)) || size != before.size()) {
                throw new UnsafeCaptureException("A live-world file changed during Git capture");
            }
            files.add(new GitInventoryEntry(portable, size, sha256));
            return FileVisitResult.CONTINUE;
        }

        @Override
        public FileVisitResult visitFileFailed(Path file, IOException exception) throws IOException {
            throw new UnsafeCaptureException("A live-world source entry could not be read", exception);
        }

        @Override
        public FileVisitResult postVisitDirectory(Path directory, IOException exception) throws IOException {
            if (exception != null) {
                throw new UnsafeCaptureException("The live-world source could not be captured", exception);
            }
            return FileVisitResult.CONTINUE;
        }

        private void requireDirectoriesUnchanged() throws IOException {
            for (DirectoryFingerprint directory : directories) {
                BasicFileAttributes attributes = Files.readAttributes(
                        directory.path(),
                        BasicFileAttributes.class,
                        LinkOption.NOFOLLOW_LINKS);
                GitSourceScanner.requireDirectory(directory.path(), attributes);
                if (!directory.matches(attributes)) {
                    throw new UnsafeCaptureException("A live-world directory changed during Git capture");
                }
            }
        }

        private String portable(Path relative) throws UnsafeCaptureException {
            try {
                String result = GitPortablePath.fromRelativePath(relative);
                GitPortablePath.requireNotInternalRoot(result);
                return result;
            } catch (IllegalArgumentException exception) {
                throw new UnsafeCaptureException("Live-world source contains a non-portable path", exception);
            }
        }

        private void register(String portable, PathKind kind) throws UnsafeCaptureException {
            String[] segments = portable.split("/");
            StringBuilder prefix = new StringBuilder();
            for (int index = 0; index < segments.length; index++) {
                if (!prefix.isEmpty()) {
                    prefix.append('/');
                }
                prefix.append(segments[index]);
                String key = GitPortablePath.collisionKey(prefix.toString());
                PathKind expected = index == segments.length - 1 ? kind : PathKind.DIRECTORY;
                PathKind previous = collisionKinds.putIfAbsent(key, expected);
                if (previous != null && (previous != expected || index == segments.length - 1)) {
                    throw new UnsafeCaptureException(
                            "Live-world source contains paths that collide on another platform");
                }
            }
        }

        private void countEntry() throws UnsafeCaptureException {
            if (++entries > GitInventory.MAXIMUM_FILES * 2) {
                throw new UnsafeCaptureException("Live-world source contains too many entries");
            }
        }
    }

    private static CopyResult copyFile(Path source, Path target) throws IOException {
        MessageDigest digest = GitInventory.sha256();
        long size = 0;
        ByteBuffer buffer = ByteBuffer.allocate(COPY_BUFFER_BYTES);
        try (SeekableByteChannel input = Files.newByteChannel(source, readOptions());
                FileChannel output = FileChannel.open(
                        target,
                        StandardOpenOption.CREATE_NEW,
                        StandardOpenOption.WRITE,
                        LinkOption.NOFOLLOW_LINKS)) {
            while (input.read(buffer) >= 0) {
                if (buffer.position() == 0) {
                    continue;
                }
                size = Math.addExact(size, buffer.position());
                if (size > GitInventory.MAXIMUM_BYTES) {
                    throw new IOException("A live-world file exceeds the Git capture limit");
                }
                digest.update(buffer.array(), 0, buffer.position());
                buffer.flip();
                while (buffer.hasRemaining()) {
                    output.write(buffer);
                }
                buffer.clear();
            }
            output.force(true);
        } catch (ArithmeticException exception) {
            throw new IOException("A live-world file size overflowed Git accounting", exception);
        }
        return new CopyResult(size, HexFormat.of().formatHex(digest.digest()));
    }

    private static Set<OpenOption> readOptions() {
        Set<OpenOption> options = new HashSet<>();
        options.add(StandardOpenOption.READ);
        options.add(LinkOption.NOFOLLOW_LINKS);
        if (isWindows()) {
            options.add(ExtendedOpenOption.NOSHARE_DELETE);
        }
        return Set.copyOf(options);
    }

    private static boolean isWindows() {
        return System.getProperty("os.name").startsWith("Windows");
    }

    private enum PathKind {
        DIRECTORY,
        FILE
    }

    private record CopyResult(long size, String sha256) {
    }

    /** Directory mtimes are deferred on Windows; identity and membership remain authoritative. */
    private record DirectoryFingerprint(
            Path path,
            Object fileKey,
            FileTime creationTime) {
        private DirectoryFingerprint {
            Objects.requireNonNull(path, "path");
            Objects.requireNonNull(creationTime, "creationTime");
        }

        private static DirectoryFingerprint create(Path path, BasicFileAttributes attributes) {
            return new DirectoryFingerprint(
                    path,
                    attributes.fileKey(),
                    attributes.creationTime());
        }

        private boolean matches(BasicFileAttributes attributes) {
            return Objects.equals(fileKey, attributes.fileKey())
                    && creationTime.equals(attributes.creationTime());
        }
    }

    private record FileFingerprint(
            Object fileKey,
            long size,
            FileTime lastModifiedTime,
            FileTime creationTime) {
        private static FileFingerprint create(BasicFileAttributes attributes) {
            return new FileFingerprint(
                    attributes.fileKey(),
                    attributes.size(),
                    attributes.lastModifiedTime(),
                    attributes.creationTime());
        }
    }

    private static final class UnsafeCaptureException extends IOException {
        private UnsafeCaptureException(String message) {
            super(message);
        }

        private UnsafeCaptureException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
