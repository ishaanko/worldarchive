package dev.ishaanko.worldarchive.storage.git;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Serializes WorldArchive's work on one repository. A fair lock per repository path covers every
 * store in this game, including an old store that still finishes work after a settings change;
 * a file lock beside the repository covers another game started on the same folder. The folder
 * that holds the repository must exist; the lock never creates it.
 */
final class GitRepositoryLock {
    private static final ConcurrentMap<Path, ReentrantLock> LOCKS = new ConcurrentHashMap<>();

    private static final long RETRY_MILLIS = 25;

    private GitRepositoryLock() {
    }

    /**
     * Runs the work while holding both locks; work that already holds them simply runs. Another
     * program gets at most the wait limit to release the file lock, so a second game or a stale
     * lock on a network share fails plainly instead of blocking a backup forever.
     */
    static <T> T withLock(Path repository, Duration crossProcessWait, GitInterruptibleOperation<T> work)
            throws IOException, InterruptedException, GitStorageException {
        Path key = repository.toAbsolutePath().normalize();
        ReentrantLock lock = LOCKS.computeIfAbsent(key, ignored -> new ReentrantLock(true));
        if (lock.isHeldByCurrentThread()) {
            return work.run();
        }
        lock.lockInterruptibly();
        try {
            Path lockFile = key.resolveSibling(key.getFileName() + ".worldarchive.lock");
            try (FileChannel channel = FileChannel.open(
                            lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
                    FileLock ignored = acquire(channel, crossProcessWait)) {
                return work.run();
            }
        } finally {
            lock.unlock();
        }
    }

    private static FileLock acquire(FileChannel channel, Duration wait)
            throws IOException, InterruptedException, GitStorageException {
        long deadline = System.nanoTime() + wait.toNanos();
        while (true) {
            try {
                FileLock lock = channel.tryLock();
                if (lock != null) {
                    return lock;
                }
            } catch (OverlappingFileLockException exception) {
                // The same file reached through another path; this game holds it elsewhere.
            }
            if (System.nanoTime() - deadline >= 0) {
                throw new GitStorageException(
                        "Another program is using this backup repository. Close it and try again.");
            }
            Thread.sleep(RETRY_MILLIS);
        }
    }
}
