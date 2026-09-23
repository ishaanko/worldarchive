package dev.ishaanko.worldarchive.support;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Objects;
import java.util.UUID;

/** Atomic UTF-8 publication helpers for small metadata files. */
public final class AtomicFiles {
    private static final int MAXIMUM_METADATA_BYTES = 64 * 1_024 * 1_024;

    /** Windows refuses a replace while an antivirus or sync client holds the target open. */
    private static final int REPLACE_ATTEMPTS = 5;

    private static final long REPLACE_RETRY_MILLIS = 50;

    private AtomicFiles() {
    }

    /** Reads one regular UTF-8 metadata file with a fixed memory limit. */
    public static String readUtf8(Path source) throws IOException {
        return readUtf8(source, MAXIMUM_METADATA_BYTES);
    }

    /** Reads one regular UTF-8 metadata file with the specified byte limit. */
    public static String readUtf8(Path source, int maximumBytes) throws IOException {
        Objects.requireNonNull(source, "source");
        requireMaximumBytes(maximumBytes);
        Path absoluteSource = source.toAbsolutePath().normalize();
        BasicFileAttributes attributes = Files.readAttributes(
                absoluteSource,
                BasicFileAttributes.class,
                LinkOption.NOFOLLOW_LINKS);
        if (!FileSystemSafety.isOrdinaryRegularFile(attributes)) {
            throw new IOException("Metadata source is not a safe regular file: " + absoluteSource);
        }
        if (attributes.size() > maximumBytes) {
            throw new IOException("Metadata file exceeds the safety limit: " + absoluteSource);
        }
        try (InputStream input = Files.newInputStream(
                absoluteSource,
                StandardOpenOption.READ,
                LinkOption.NOFOLLOW_LINKS)) {
            byte[] encoded = input.readNBytes(maximumBytes + 1);
            if (encoded.length > maximumBytes) {
                throw new IOException("Metadata file exceeds the safety limit: " + absoluteSource);
            }
            try {
                return StandardCharsets.UTF_8.newDecoder()
                        .decode(ByteBuffer.wrap(encoded))
                        .toString();
            } catch (CharacterCodingException exception) {
                throw new IOException("Metadata file is not valid UTF-8: " + absoluteSource, exception);
            }
        }
    }

    /**
     * Publishes complete UTF-8 content with a same-directory atomic move. The temporary file
     * is flushed before the move and the directory entry is flushed after it where the
     * platform allows, so the new name survives a system crash. A failed write or replace
     * leaves the previous file as it was and removes the temporary file.
     *
     * @throws AtomicMoveNotSupportedException when the filesystem cannot provide atomic publication
     */
    public static void writeUtf8(Path target, String content) throws IOException {
        writeUtf8(target, content, MAXIMUM_METADATA_BYTES);
    }

    /** Publishes UTF-8 content only when it fits within the specified byte limit. */
    public static void writeUtf8(
            Path target,
            String content,
            int maximumBytes) throws IOException {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(content, "content");
        requireMaximumBytes(maximumBytes);
        Path absoluteTarget = target.toAbsolutePath().normalize();
        Path parent = absoluteTarget.getParent();
        if (parent == null) {
            throw new IOException("Metadata target has no parent directory: " + absoluteTarget);
        }
        byte[] encoded = content.getBytes(StandardCharsets.UTF_8);
        if (encoded.length > maximumBytes) {
            throw new IOException("Metadata content exceeds the safety limit: " + absoluteTarget);
        }
        Files.createDirectories(parent);
        Path temporary = parent.resolve(
                "." + absoluteTarget.getFileName() + "." + UUID.randomUUID() + ".tmp");
        try {
            try (FileChannel channel = FileChannel.open(
                    temporary,
                    StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE)) {
                ByteBuffer buffer = ByteBuffer.wrap(encoded);
                while (buffer.hasRemaining()) {
                    channel.write(buffer);
                }
                channel.force(true);
            }
            replace(temporary, absoluteTarget);
        } catch (IOException | RuntimeException exception) {
            try {
                Files.deleteIfExists(temporary);
            } catch (IOException | RuntimeException cleanupFailure) {
                exception.addSuppressed(cleanupFailure);
            }
            throw exception;
        }
        flushDirectory(parent);
    }

    /**
     * Moves the temporary file over the target, retrying briefly while another program holds
     * the target open. Windows reports that as access denied or as a plain file system error;
     * every other failure, such as a missing atomic move, is final at once.
     */
    private static void replace(Path temporary, Path target) throws IOException {
        for (int attempt = 1; ; attempt++) {
            try {
                Files.move(
                        temporary,
                        target,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
                return;
            } catch (FileSystemException exception) {
                boolean transientLock = exception instanceof AccessDeniedException
                        || exception.getClass() == FileSystemException.class;
                if (!transientLock || attempt == REPLACE_ATTEMPTS) {
                    throw exception;
                }
                try {
                    Thread.sleep(REPLACE_RETRY_MILLIS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw exception;
                }
            }
        }
    }

    /**
     * Flushes a directory so a rename inside it reaches the disk. Windows cannot open a
     * directory as a channel and some filesystems refuse the sync; both are ignored because
     * the rename itself already succeeded.
     */
    public static void flushDirectory(Path directory) {
        try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)) {
            channel.force(true);
        } catch (IOException | UnsupportedOperationException ignored) {
            // Best effort only; see the method comment.
        }
    }

    private static void requireMaximumBytes(int maximumBytes) {
        if (maximumBytes < 0 || maximumBytes == Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Metadata byte limit is out of range");
        }
    }
}
