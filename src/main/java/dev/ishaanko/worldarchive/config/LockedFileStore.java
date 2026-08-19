package dev.ishaanko.worldarchive.config;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;

/**
 * Process-safe atomic access to a single file, guarded by a sibling {@code .lock} file.
 * Rejects symbolic links on both paths and re-verifies the lock file's identity between
 * opening it and acquiring the OS-level lock, closing the TOCTOU window where it could be
 * swapped out from under a waiting process. Callers supply an exception factory so failures
 * surface as their own domain exception rather than a generic {@link IOException}.
 */
final class LockedFileStore {
    private static final ConcurrentMap<Path, ReentrantLock> JVM_LOCKS = new ConcurrentHashMap<>();

    private final Path file;

    private final Path lockFile;

    private final ReentrantLock jvmLock;

    private final Function<String, ? extends IOException> exceptionFactory;

    LockedFileStore(Path file, Function<String, ? extends IOException> exceptionFactory) {
        this.file = file;
        this.lockFile = file.resolveSibling(file.getFileName() + ".lock");
        this.jvmLock = JVM_LOCKS.computeIfAbsent(this.file, ignored -> new ReentrantLock());
        this.exceptionFactory = exceptionFactory;
    }

    Path file() {
        return file;
    }

    <T> T withLock(IoSupplier<T> operation) throws IOException {
        rejectSymlink(file);
        rejectSymlink(lockFile);
        jvmLock.lock();
        try {
            rejectSymlink(file);
            rejectSymlink(lockFile);
            LockIdentity beforeOpen = ensureLockFile(lockFile);
            try (FileChannel channel = FileChannel.open(
                            lockFile,
                            StandardOpenOption.WRITE,
                            LinkOption.NOFOLLOW_LINKS)) {
                LockIdentity afterOpen = readLockIdentity(lockFile);
                requireSameLock(beforeOpen, afterOpen);
                try (FileLock ignored = channel.lock()) {
                    requireSameLock(afterOpen, readLockIdentity(lockFile));
                    rejectSymlink(file);
                    return operation.get();
                }
            }
        } finally {
            jvmLock.unlock();
        }
    }

    private void rejectSymlink(Path path) throws IOException {
        if (Files.isSymbolicLink(path)) {
            throw exceptionFactory.apply(path + " must not be a symbolic link");
        }
    }

    private LockIdentity ensureLockFile(Path lockFile) throws IOException {
        try {
            Files.createFile(lockFile);
        } catch (FileAlreadyExistsException exception) {
            // The persistent lock already exists; its identity is verified below.
        }
        return readLockIdentity(lockFile);
    }

    private LockIdentity readLockIdentity(Path lockFile) throws IOException {
        BasicFileAttributes attributes = Files.readAttributes(
                lockFile,
                BasicFileAttributes.class,
                LinkOption.NOFOLLOW_LINKS);
        if (attributes.isSymbolicLink() || !attributes.isRegularFile()) {
            throw exceptionFactory.apply("Lock file must be a regular non-symbolic file: " + lockFile);
        }
        return new LockIdentity(attributes.fileKey(), attributes.creationTime());
    }

    private void requireSameLock(LockIdentity expected, LockIdentity actual) throws IOException {
        boolean same = expected.fileKey() != null || actual.fileKey() != null
                ? expected.fileKey() != null && expected.fileKey().equals(actual.fileKey())
                : expected.creationTime().equals(actual.creationTime());
        if (!same) {
            throw exceptionFactory.apply("Lock file changed while it was being acquired: " + lockFile);
        }
    }

    @FunctionalInterface
    interface IoSupplier<T> {
        T get() throws IOException;
    }

    private record LockIdentity(Object fileKey, FileTime creationTime) {
    }
}
