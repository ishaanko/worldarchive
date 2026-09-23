package dev.ishaanko.worldarchive.storage.zip;

import dev.ishaanko.worldarchive.support.AtomicFiles;
import dev.ishaanko.worldarchive.support.LockedFile;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The folder of one world's archives. Every change in it (create, import and delete) runs under
 * {@link #locked}, so a sweep never removes a file that another operation is still writing, in
 * this game or in a second game that shares the folder. On Windows, renames and deletes retry
 * for up to a second, because Windows refuses them while a virus scanner or a sync client holds
 * a new file.
 */
final class ZipFolder {
    private static final Logger LOGGER = LoggerFactory.getLogger("WorldArchive");

    /** The lock file is {@code .worldarchive.lock}, the name every WorldArchive version has used. */
    private static final String LOCK_NAME = ".worldarchive";

    /** 0.3.9 held this file open on Windows to pin the folder; it is never needed now. */
    private static final String OLD_GUARD_NAME = ".worldarchive.guard";

    /** Partial files of a create (and of an import since this version) and of an import in 0.3.9. */
    private static final List<String> PARTIAL_SUFFIXES = List.of(ManagedZipArchive.PARTIAL_SUFFIX, ".importing");

    private static final Pattern CHECKSUM_LINE = Pattern.compile("([0-9a-f]{64})  [^\\r\\n]+(?:\\r?\\n)?");

    private static final int MAXIMUM_CHECKSUM_BYTES = 4_096;

    private static final int ATTEMPTS = 5;

    private static final long RETRY_MILLIS = 200;

    private final Path path;

    private final LockedFile lock;

    ZipFolder(Path path) {
        this.path = path;
        this.lock = new LockedFile(path.resolve(LOCK_NAME));
    }

    /** Runs the operation while no other thread or game changes this folder; creates the folder. */
    <T> T locked(LockedFile.IoSupplier<T> operation) throws IOException {
        return lock.withLock(operation);
    }

    /**
     * Deletes what an interrupted create or import left here: partial archives and checksum
     * files. None of them is a backup. A checksum file whose archive is missing stays, because a
     * sync tool may deliver the archive later, or the player moved it away for a moment. A file
     * that cannot be deleted stays for the next sweep. Call it under the lock.
     */
    void sweep() {
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(path)) {
            for (Path entry : entries) {
                if (isLeftover(entry.getFileName().toString()) && Files.isRegularFile(entry, LinkOption.NOFOLLOW_LINKS)) {
                    deleteLeftover(entry);
                }
            }
        } catch (IOException exception) {
            LOGGER.warn("Could not look for leftover files in {}: {}", path, exception.toString());
        }
    }

    /**
     * Turns a checked partial archive into a backup: writes and renames its checksum file first,
     * then renames the archive, which is the moment the backup exists, and flushes the folder so
     * both names survive a power loss. On failure the checksum file is removed again and the
     * caller removes the partial archive; a backup that already has this name is never replaced.
     */
    static void publish(ManagedZipArchive archive, String sha256) throws IOException {
        if (Files.exists(archive.archive(), LinkOption.NOFOLLOW_LINKS)) {
            throw new FileAlreadyExistsException(archive.archive().toString());
        }
        try {
            writeChecksum(archive.checksumPartial(), sha256, archive.name());
            move(archive.checksumPartial(), archive.checksum());
            move(archive.partial(), archive.archive());
        } catch (IOException | RuntimeException failure) {
            deleteAfterFailure(failure, archive.checksumPartial());
            deleteAfterFailure(failure, archive.checksum());
            throw failure;
        }
        AtomicFiles.flushDirectory(archive.folder());
    }

    /** The digest in a checksum file; empty when the file is missing or holds no checksum line. */
    static Optional<String> readChecksum(Path checksum) throws IOException {
        byte[] bytes;
        try (InputStream input = Files.newInputStream(checksum, LinkOption.NOFOLLOW_LINKS)) {
            bytes = input.readNBytes(MAXIMUM_CHECKSUM_BYTES + 1);
        } catch (NoSuchFileException missing) {
            return Optional.empty();
        }
        Matcher line = CHECKSUM_LINE.matcher(new String(bytes, StandardCharsets.UTF_8));
        return bytes.length <= MAXIMUM_CHECKSUM_BYTES && line.matches()
                ? Optional.of(line.group(1))
                : Optional.empty();
    }

    /** Deletes a file, retrying while it is in use; false when there was nothing to delete. */
    static boolean deleteIfExists(Path file) throws IOException {
        return retrying(file, () -> Files.deleteIfExists(file));
    }

    /** Deletes a file after a failure, adding its own failure to the one being reported. */
    static void deleteAfterFailure(Throwable failure, Path file) {
        try {
            deleteIfExists(file);
        } catch (IOException cleanupFailure) {
            failure.addSuppressed(cleanupFailure);
        }
    }

    private static void writeChecksum(Path file, String sha256, String archiveName) throws IOException {
        ByteBuffer line = ByteBuffer.wrap((sha256 + "  " + archiveName + "\n").getBytes(StandardCharsets.UTF_8));
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            while (line.hasRemaining()) {
                channel.write(line);
            }
            channel.force(true);
        }
    }

    private static void move(Path source, Path target) throws IOException {
        retrying(source, () -> Files.move(source, target, StandardCopyOption.ATOMIC_MOVE));
    }

    /**
     * Runs a rename or a delete of the file, retrying while Windows reports it in use, as
     * access denied or as a plain file system error. Other systems never refuse a file for
     * being open, so there every failure is final at once.
     */
    private static <T> T retrying(Path file, LockedFile.IoSupplier<T> action) throws IOException {
        boolean windows = "\\".equals(file.getFileSystem().getSeparator());
        for (int attempt = 1; ; attempt++) {
            try {
                return action.get();
            } catch (FileSystemException exception) {
                boolean inUse = exception instanceof AccessDeniedException
                        || exception.getClass() == FileSystemException.class;
                if (!windows || !inUse || attempt == ATTEMPTS) {
                    throw exception;
                }
                try {
                    Thread.sleep(RETRY_MILLIS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw exception;
                }
            }
        }
    }

    private static boolean isLeftover(String name) {
        if (name.equals(OLD_GUARD_NAME)) {
            return true;
        }
        for (String suffix : PARTIAL_SUFFIXES) {
            if (name.endsWith(suffix)) {
                String stem = name.substring(0, name.length() - suffix.length());
                return isArchiveName(stem) || isChecksumName(stem);
            }
        }
        return false;
    }

    private static boolean isChecksumName(String name) {
        return name.endsWith(ManagedZipArchive.CHECKSUM_SUFFIX) && isArchiveName(archiveOf(name));
    }

    private static String archiveOf(String checksumName) {
        return checksumName.substring(0, checksumName.length() - ManagedZipArchive.CHECKSUM_SUFFIX.length());
    }

    private static boolean isArchiveName(String name) {
        return ManagedZipArchive.backupId(name).isPresent();
    }

    private static void deleteLeftover(Path file) {
        try {
            deleteIfExists(file);
        } catch (IOException exception) {
            LOGGER.warn("Could not delete the leftover file {}: {}", file, exception.toString());
        }
    }
}
