package dev.ishaankot.worldarchive.core;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Objects;
import java.util.UUID;

/** Crash-safe UTF-8 publication helpers for small metadata files. */
public final class AtomicFiles {
    private AtomicFiles() {
    }

    /**
     * Publishes complete UTF-8 content with a same-directory atomic move.
     *
     * @throws AtomicMoveNotSupportedException when the filesystem cannot provide atomic publication
     */
    public static void writeUtf8(Path target, String content) throws IOException {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(content, "content");
        Path absoluteTarget = target.toAbsolutePath().normalize();
        Path parent = absoluteTarget.getParent();
        if (parent == null) {
            throw new IOException("Metadata target has no parent directory: " + absoluteTarget);
        }
        Files.createDirectories(parent);
        Path temporary = parent.resolve("." + absoluteTarget.getFileName() + "." + UUID.randomUUID() + ".tmp");
        byte[] encoded = content.getBytes(StandardCharsets.UTF_8);
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
            Files.move(
                    temporary,
                    absoluteTarget,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}
