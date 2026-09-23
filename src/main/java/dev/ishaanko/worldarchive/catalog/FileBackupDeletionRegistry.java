package dev.ishaanko.worldarchive.catalog;

import dev.ishaanko.worldarchive.model.BackupId;
import dev.ishaanko.worldarchive.support.AtomicFiles;
import dev.ishaanko.worldarchive.support.LockedFile;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

/**
 * The backups the player deleted, one ID per line in {@code deleted-backups.txt}. The start-up
 * rebuild never lists a marked backup again, even when a crash left one of its files behind; an
 * explicit import unmarks what it imports. A delete marks its backups before it touches their
 * files and unmarks the ones that stay listed. Reads take no lock, like the catalog's, and a
 * damaged file is moved aside the same way.
 */
public final class FileBackupDeletionRegistry {
    private static final String HEADER_PREFIX = "worldarchive-deleted-backups-v";

    private static final String HEADER = HEADER_PREFIX + "1";

    private static final int MAXIMUM_FILE_BYTES = 64 * 1_024 * 1_024;

    private final LockedFile lock;

    private final Path file;

    public FileBackupDeletionRegistry(Path file) {
        this.lock = new LockedFile(file);
        this.file = lock.file();
    }

    /** The marked backups. */
    public Set<BackupId> marked() throws IOException {
        try {
            return decode(DamagedFiles.read(file, MAXIMUM_FILE_BYTES));
        } catch (DamagedFileException damaged) {
            return lock.withLock(this::readForChange);
        }
    }

    public void mark(Collection<BackupId> backupIds) throws IOException {
        List<BackupId> marks = List.copyOf(backupIds);
        if (!marks.isEmpty()) {
            change(values -> values.addAll(marks));
        }
    }

    public void unmark(Collection<BackupId> backupIds) throws IOException {
        List<BackupId> marks = List.copyOf(backupIds);
        if (!marks.isEmpty()) {
            change(values -> marks.forEach(values::remove));
        }
    }

    /** Unmarks every backup outside {@code stored}, the backups that a complete scan found files of. */
    public void unmarkAllExcept(Set<BackupId> stored) throws IOException {
        Set<BackupId> kept = Set.copyOf(stored);
        if (!kept.containsAll(marked())) {
            change(values -> values.retainAll(kept));
        }
    }

    private void change(Consumer<Set<BackupId>> edit) throws IOException {
        lock.withLock(() -> {
            Set<BackupId> before = readForChange();
            Set<BackupId> after = new HashSet<>(before);
            edit.accept(after);
            if (!after.equals(before)) {
                write(after);
            }
            return null;
        });
    }

    private Set<BackupId> readForChange() throws IOException {
        try {
            return decode(DamagedFiles.read(file, MAXIMUM_FILE_BYTES));
        } catch (DamagedFileException damaged) {
            DamagedFiles.moveAside(file, damaged);
            return new HashSet<>();
        }
    }

    private Set<BackupId> decode(Optional<String> text) throws IOException {
        Set<BackupId> values = new HashSet<>();
        if (text.isEmpty()) {
            return values;
        }
        List<String> lines = text.get().lines().toList();
        String header = lines.isEmpty() ? "" : lines.getFirst();
        if (!header.equals(HEADER)) {
            if (header.startsWith(HEADER_PREFIX)) {
                throw new IOException("The deleted-backup list " + file + " was written by a newer version of"
                        + " WorldArchive. Update WorldArchive to use it.");
            }
            throw new DamagedFileException("The deleted-backup list does not start with its header", null);
        }
        try {
            for (String line : lines.subList(1, lines.size())) {
                if (!line.isBlank()) {
                    values.add(BackupId.parse(line));
                }
            }
        } catch (IllegalArgumentException invalid) {
            throw new DamagedFileException("The deleted-backup list holds an invalid backup ID", invalid);
        }
        return values;
    }

    private void write(Set<BackupId> values) throws IOException {
        StringBuilder content = new StringBuilder(HEADER).append('\n');
        values.stream().sorted().forEach(value -> content.append(value).append('\n'));
        AtomicFiles.writeUtf8(file, content.toString(), MAXIMUM_FILE_BYTES);
    }
}
