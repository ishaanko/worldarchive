package dev.ishaankot.worldarchive.catalog;

import dev.ishaankot.worldarchive.core.AtomicFiles;
import dev.ishaankot.worldarchive.model.BackupId;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.ReentrantLock;

/** Process-safe, atomically published set of backups explicitly deleted by the user. */
public final class FileBackupDeletionRegistry implements BackupDeletionRegistry {
    private static final String HEADER = "worldarchive-deleted-backups-v1";

    private static final ConcurrentMap<Path, ReentrantLock> JVM_LOCKS = new ConcurrentHashMap<>();

    private final Path file;

    private final Path lockFile;

    private final ReentrantLock jvmLock;

    public FileBackupDeletionRegistry(Path file) {
        this.file = Objects.requireNonNull(file, "file").toAbsolutePath().normalize();
        lockFile = this.file.resolveSibling(this.file.getFileName() + ".lock");
        jvmLock = JVM_LOCKS.computeIfAbsent(this.file, ignored -> new ReentrantLock());
    }

    @Override
    public boolean contains(BackupId backupId) throws IOException {
        Objects.requireNonNull(backupId, "backupId");
        return withLock(() -> read().contains(backupId));
    }

    @Override
    public void record(BackupId backupId) throws IOException {
        update(backupId, true);
    }

    @Override
    public void restore(BackupId backupId) throws IOException {
        update(backupId, false);
    }

    private void update(BackupId backupId, boolean deleted) throws IOException {
        Objects.requireNonNull(backupId, "backupId");
        withLock(() -> {
            Set<BackupId> values = read();
            boolean changed = deleted ? values.add(backupId) : values.remove(backupId);
            if (changed) {
                write(values);
            }
            return null;
        });
    }

    private Set<BackupId> read() throws IOException {
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
            return new HashSet<>();
        }
        if (Files.isSymbolicLink(file) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Backup deletion registry is not a safe regular file");
        }
        java.util.List<String> lines = Files.readAllLines(file);
        if (lines.isEmpty() || !HEADER.equals(lines.getFirst())) {
            throw new IOException("Backup deletion registry has an unsupported format");
        }
        Set<BackupId> values = new HashSet<>();
        try {
            for (String line : lines.subList(1, lines.size())) {
                if (!line.isBlank() && !values.add(BackupId.parse(line))) {
                    throw new IOException("Backup deletion registry contains duplicate IDs");
                }
            }
        } catch (IllegalArgumentException exception) {
            throw new IOException("Backup deletion registry contains an invalid ID", exception);
        }
        return values;
    }

    private void write(Set<BackupId> values) throws IOException {
        StringBuilder content = new StringBuilder(HEADER).append(System.lineSeparator());
        values.stream().sorted().forEach(value -> content
                .append(value)
                .append(System.lineSeparator()));
        AtomicFiles.writeUtf8(file, content.toString());
    }

    private <T> T withLock(IoSupplier<T> operation) throws IOException {
        jvmLock.lock();
        try {
            Path parent = file.getParent();
            if (parent == null) {
                throw new IOException("Backup deletion registry has no parent directory");
            }
            Files.createDirectories(parent);
            if (Files.isSymbolicLink(lockFile)) {
                throw new IOException("Backup deletion registry lock must not be symbolic");
            }
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

    @FunctionalInterface
    private interface IoSupplier<T> {
        T get() throws IOException;
    }
}
