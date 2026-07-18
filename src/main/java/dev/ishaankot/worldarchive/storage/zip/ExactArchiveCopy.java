package dev.ishaankot.worldarchive.storage.zip;

import dev.ishaankot.worldarchive.storage.zip.ZipArchiveInspector.Inspection;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.util.Set;

/** One exact-open snapshot copied into an owner-private, locally trusted file. */
final class ExactArchiveCopy implements AutoCloseable {
    private static final Set<PosixFilePermission> PRIVATE_DIRECTORY_PERMISSIONS = Set.of(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.OWNER_EXECUTE);

    private final Path directory;

    private final Path archive;

    private final String sha256;

    private final long size;

    private final SourceFingerprint sourceFingerprint;

    private ExactArchiveCopy(
            Path directory,
            Path archive,
            String sha256,
            long size,
            SourceFingerprint sourceFingerprint) {
        this.directory = directory;
        this.archive = archive;
        this.sha256 = sha256;
        this.size = size;
        this.sourceFingerprint = sourceFingerprint;
    }

    static ExactArchiveCopy capture(ManagedDirectoryAccess sourceDirectory, String sourceName)
            throws IOException {
        sourceDirectory.requireRegularFile(
                sourceName, "Managed ZIP source is not a safe regular file");
        SourceFingerprint sourceBefore = SourceFingerprint.from(
                sourceDirectory.attributes(sourceName));
        Path privateDirectory = createPrivateDirectory();
        Path privateArchive = privateDirectory.resolve("artifact.zip");
        try {
            CopyResult copied;
            try (SeekableByteChannel source = sourceDirectory.openRead(sourceName);
                    FileChannel target = FileChannel.open(
                            privateArchive,
                            StandardOpenOption.CREATE_NEW,
                            StandardOpenOption.WRITE,
                            LinkOption.NOFOLLOW_LINKS)) {
                copied = copy(source, target);
                target.force(true);
            }
            SourceFingerprint sourceAfter = SourceFingerprint.from(
                    sourceDirectory.attributes(sourceName));
            if (!sourceBefore.equals(sourceAfter)) {
                throw new ZipBackupException("Managed ZIP source changed while being copied");
            }
            return new ExactArchiveCopy(
                    privateDirectory,
                    privateArchive,
                    copied.sha256(),
                    copied.size(),
                    sourceBefore);
        } catch (IOException | RuntimeException exception) {
            cleanupWithSuppression(privateArchive, privateDirectory, exception);
            throw exception;
        }
    }

    Path path() {
        return archive;
    }

    String sha256() {
        return sha256;
    }

    long size() {
        return size;
    }

    boolean matches(ExactArchiveCopy other) {
        return sourceFingerprint.equals(other.sourceFingerprint)
                && sameContents(other);
    }

    boolean sameContents(ExactArchiveCopy other) {
        return size == other.size && sha256.equals(other.sha256);
    }

    boolean sourceStillMatches(ManagedDirectoryAccess sourceDirectory, String sourceName)
            throws IOException {
        sourceDirectory.requireRegularFile(
                sourceName, "Managed ZIP source is not a safe regular file");
        return sourceFingerprint.equals(SourceFingerprint.from(
                sourceDirectory.attributes(sourceName)));
    }

    Inspection inspect() throws IOException {
        try (SeekableByteChannel channel = openRead()) {
            return ZipArchiveInspector.inspect(channel);
        }
    }

    SeekableByteChannel openRead() throws IOException {
        return FileChannel.open(
                archive,
                Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS));
    }

    void copyTo(ManagedDirectoryAccess targetDirectory, String targetName) throws IOException {
        boolean created = false;
        try (SeekableByteChannel source = openRead();
                SeekableByteChannel target = targetDirectory.createNew(targetName)) {
            created = true;
            CopyResult copied = copy(source, target);
            if (!copied.sha256().equals(sha256) || copied.size() != size) {
                throw new ZipBackupException("Trusted ZIP copy changed during publication");
            }
            if (target instanceof FileChannel fileChannel) {
                fileChannel.force(true);
            }
        } catch (IOException | RuntimeException exception) {
            if (created) {
                try {
                    targetDirectory.deleteIfExists(targetName);
                } catch (IOException cleanupFailure) {
                    exception.addSuppressed(cleanupFailure);
                }
            }
            throw exception;
        }
    }

    @Override
    public void close() throws IOException {
        IOException failure = null;
        try {
            Files.deleteIfExists(archive);
        } catch (IOException exception) {
            failure = exception;
        }
        try {
            Files.deleteIfExists(directory);
        } catch (IOException exception) {
            if (failure == null) {
                failure = exception;
            } else {
                failure.addSuppressed(exception);
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    private static Path createPrivateDirectory() throws IOException {
        Path value = Files.createTempDirectory("worldarchive-zip-");
        try {
            Files.setPosixFilePermissions(value, PRIVATE_DIRECTORY_PERMISSIONS);
        } catch (UnsupportedOperationException exception) {
            // Windows temp directories inherit the current user's private ACL.
        }
        return value;
    }

    private static CopyResult copy(SeekableByteChannel source, SeekableByteChannel target)
            throws IOException {
        MessageDigest digest = ZipDigests.sha256();
        ByteBuffer buffer = ByteBuffer.allocate(ZipDigests.COPY_BUFFER_BYTES);
        long total = 0;
        try {
            while (true) {
                if (Thread.currentThread().isInterrupted()) {
                    throw new InterruptedIOException("ZIP operation was interrupted");
                }
                buffer.clear();
                int read = source.read(buffer);
                if (read < 0) {
                    break;
                }
                if (read == 0) {
                    continue;
                }
                total = Math.addExact(total, read);
                if (total > ZipLimits.MAXIMUM_ARCHIVE_BYTES) {
                    throw new ZipBackupException("ZIP archive exceeds its size limit");
                }
                digest.update(buffer.array(), 0, read);
                buffer.flip();
                while (buffer.hasRemaining()) {
                    target.write(buffer);
                }
            }
        } catch (ArithmeticException exception) {
            throw new ZipBackupException("ZIP archive size overflow", exception);
        }
        return new CopyResult(total, ZipDigests.hex(digest.digest()));
    }

    private static void cleanupWithSuppression(
            Path archive,
            Path directory,
            Throwable failure) {
        try {
            Files.deleteIfExists(archive);
        } catch (IOException cleanupFailure) {
            failure.addSuppressed(cleanupFailure);
        }
        try {
            Files.deleteIfExists(directory);
        } catch (IOException cleanupFailure) {
            failure.addSuppressed(cleanupFailure);
        }
    }

    private record CopyResult(long size, String sha256) {
    }

    private record SourceFingerprint(
            Object fileKey,
            long size,
            FileTime lastModifiedTime,
            FileTime creationTime) {
        static SourceFingerprint from(BasicFileAttributes attributes) {
            return new SourceFingerprint(
                    attributes.fileKey(),
                    attributes.size(),
                    attributes.lastModifiedTime(),
                    attributes.creationTime());
        }
    }
}
