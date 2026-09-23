package dev.ishaanko.worldarchive.support;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Serializes read-modify-write access to one file across threads and processes.
 *
 * <p>Threads of this JVM wait on a lock shared by every instance for the same path, because
 * an OS file lock is held per process. Other processes, such as a second game instance on the
 * same storage folder, wait on an OS lock of the sibling {@code <name>.lock} file. The lock file
 * is created on first use and never deleted. Callers still publish the file itself through
 * {@link AtomicFiles}, so a reader that skips the lock never sees a half-written file.</p>
 */
public final class LockedFile {
    private static final ConcurrentMap<Path, ReentrantLock> JVM_LOCKS = new ConcurrentHashMap<>();

    private final Path file;

    private final Path lockFile;

    private final ReentrantLock jvmLock;

    public LockedFile(Path file) {
        this.file = Objects.requireNonNull(file, "file").toAbsolutePath().normalize();
        Path name = this.file.getFileName();
        if (name == null) {
            throw new IllegalArgumentException("A locked file needs a file name: " + this.file);
        }
        this.lockFile = this.file.resolveSibling(name + ".lock");
        this.jvmLock = JVM_LOCKS.computeIfAbsent(this.file, ignored -> new ReentrantLock());
    }

    public Path file() {
        return file;
    }

    /**
     * Runs the operation while no other thread or process holds this file's lock. Creates the
     * parent folder when it is missing. The lock file is opened without following links, so
     * the lock never reaches through a link to another file.
     *
     * <p>An OS file lock fails on an interrupted thread, so call this only from a thread whose
     * interrupt is not in use for cancellation.</p>
     */
    public <T> T withLock(IoSupplier<T> operation) throws IOException {
        Objects.requireNonNull(operation, "operation");
        jvmLock.lock();
        try {
            Files.createDirectories(lockFile.getParent());
            try (FileChannel channel = FileChannel.open(
                            lockFile,
                            StandardOpenOption.CREATE,
                            StandardOpenOption.WRITE,
                            LinkOption.NOFOLLOW_LINKS);
                    FileLock ignored = channel.lock()) {
                return operation.get();
            }
        } finally {
            jvmLock.unlock();
        }
    }

    /** File work that runs under the lock. */
    @FunctionalInterface
    public interface IoSupplier<T> {
        T get() throws IOException;
    }
}
