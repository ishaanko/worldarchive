package dev.ishaanko.worldarchive.storage.git;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Path;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Serializes access to a Git repository across both in-process callers (a fair
 * {@link ReentrantLock}) and other OS processes (a {@link FileLock} on a sidecar lock file).
 */
final class GitRepositoryLock {
    private final GitBackendSettings settings;

    private final ReentrantLock processLock = new ReentrantLock(true);

    GitRepositoryLock(GitBackendSettings settings) {
        this.settings = settings;
    }

    <T> T withLock(GitInterruptibleOperation<T> operation)
            throws IOException, InterruptedException, GitStorageException {
        processLock.lockInterruptibly();
        try {
            Path parent = settings.repository().getParent();
            if (parent == null) {
                throw new GitStorageException("Git repository path must have a parent directory");
            }
            GitRepositoryPathGuard.createDirectories(parent);
            Path lockPath = parent.resolve(settings.repository().getFileName() + ".worldarchive.lock");
            try (FileChannel channel = GitRepositoryPathGuard.openLockFile(lockPath);
                    FileLock ignored = acquireFileLock(channel)) {
                return operation.run();
            }
        } finally {
            processLock.unlock();
        }
    }

    private static FileLock acquireFileLock(FileChannel channel)
            throws IOException, InterruptedException, GitStorageException {
        while (true) {
            try {
                FileLock lock = channel.tryLock();
                if (lock != null) {
                    return lock;
                }
            } catch (OverlappingFileLockException exception) {
                // Another backend instance in this process owns the same repository lock.
            }
            if (Thread.interrupted()) {
                throw new InterruptedException("Interrupted while waiting for the Git repository lock");
            }
            try {
                Thread.sleep(25L);
            } catch (InterruptedException exception) {
                throw exception;
            }
        }
    }
}
