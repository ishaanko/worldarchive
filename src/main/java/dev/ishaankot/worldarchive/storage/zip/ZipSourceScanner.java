package dev.ishaankot.worldarchive.storage.zip;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Finds a stable, portable set of regular world files without following links. */
final class ZipSourceScanner {
    private static final int WINDOWS_REPARSE_POINT = 0x400;

    private ZipSourceScanner() {
    }

    static SourceSnapshot snapshot(Path worldDirectory) throws IOException {
        requireNotInterrupted();
        BasicFileAttributes rootAttributes = readAttributes(worldDirectory);
        requireDirectory(
                worldDirectory,
                rootAttributes,
                "World source is not a regular directory");
        List<SourceEntry> entries = new ArrayList<>();
        Set<String> collisionKeys = new HashSet<>();
        Files.walkFileTree(worldDirectory, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes)
                    throws IOException {
                requireNotInterrupted();
                Path relative = worldDirectory.relativize(directory);
                if (!relative.toString().isEmpty() && isExcludedTree(relative)) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                requireDirectory(
                        directory,
                        attributes,
                        "World source contains a link or special directory");
                if (!relative.toString().isEmpty()) {
                    String portable = portable(relative);
                    addCollisionKey(collisionKeys, portable, true);
                    requireEntryCapacity(entries);
                    entries.add(SourceEntry.directory(directory, portable, attributes));
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                requireNotInterrupted();
                Path relative = worldDirectory.relativize(file);
                if (isExcludedTree(relative) || isRootSessionLock(relative)) {
                    return FileVisitResult.CONTINUE;
                }
                if (attributes.isSymbolicLink()
                        || attributes.isOther()
                        || !attributes.isRegularFile()
                        || isWindowsReparsePoint(file)) {
                    throw new ZipBackupException("World source contains a link or special file");
                }
                String portable = portable(relative);
                addCollisionKey(collisionKeys, portable, false);
                requireEntryCapacity(entries);
                entries.add(SourceEntry.file(file, portable, attributes));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exception) throws IOException {
                throw new ZipBackupException("A world source entry could not be read", exception);
            }

            @Override
            public FileVisitResult postVisitDirectory(Path directory, IOException exception) throws IOException {
                if (exception != null) {
                    throw new ZipBackupException("The world source could not be traversed", exception);
                }
                return FileVisitResult.CONTINUE;
            }
        });
        entries.sort(Comparator.comparing(SourceEntry::relativePath));
        return new SourceSnapshot(
                worldDirectory,
                DirectoryFingerprint.from(rootAttributes),
                List.copyOf(entries));
    }

    static void requireUnchanged(SourceEntry source) throws IOException {
        BasicFileAttributes current = readAttributes(source.path());
        if (!current.isRegularFile()
                || current.isSymbolicLink()
                || current.isOther()
                || isWindowsReparsePoint(source.path())
                || current.size() != source.size()
                || !current.lastModifiedTime().equals(source.lastModifiedTime())
                || !current.creationTime().equals(source.creationTime())
                || !Objects.equals(current.fileKey(), source.fileKey())) {
            throw new ZipBackupException("A world file changed while the ZIP backup was being created");
        }
    }

    private static BasicFileAttributes readAttributes(Path path) throws IOException {
        return Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
    }

    private static void requireNotInterrupted() throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedIOException("ZIP source scan was interrupted");
        }
    }

    private static void requireDirectory(
            Path path,
            BasicFileAttributes attributes,
            String message)
            throws ZipBackupException {
        try {
            if (!attributes.isDirectory()
                    || attributes.isSymbolicLink()
                    || attributes.isOther()
                    || isWindowsReparsePoint(path)) {
                throw new ZipBackupException(message);
            }
        } catch (IOException exception) {
            throw new ZipBackupException(message, exception);
        }
    }

    private static boolean isWindowsReparsePoint(Path path) throws IOException {
        try {
            Map<String, Object> attributes = Files.readAttributes(
                    path,
                    "dos:attributes",
                    LinkOption.NOFOLLOW_LINKS);
            Object raw = attributes.get("attributes");
            return raw instanceof Integer value && (value & WINDOWS_REPARSE_POINT) != 0;
        } catch (UnsupportedOperationException | IllegalArgumentException exception) {
            return false;
        }
    }

    private static void requireSameSnapshot(SourceSnapshot expected) throws IOException {
        SourceSnapshot current = snapshot(expected.worldDirectory());
        if (!expected.rootFingerprint().equals(current.rootFingerprint())
                || !sameEntries(expected.entries(), current.entries())) {
            throw new ZipBackupException(
                    "The world tree changed while the ZIP backup was being created");
        }
    }

    private static boolean sameEntries(
            List<SourceEntry> expected,
            List<SourceEntry> current) {
        if (expected.size() != current.size()) {
            return false;
        }
        for (int index = 0; index < expected.size(); index++) {
            if (!expected.get(index).sameSource(current.get(index))) {
                return false;
            }
        }
        return true;
    }

    private static boolean isExcludedTree(Path relative) {
        return relative.getNameCount() > 0
                && relative.getName(0).toString().equalsIgnoreCase(".worldarchive");
    }

    private static boolean isRootSessionLock(Path relative) {
        return relative.getNameCount() == 1
                && relative.getFileName().toString().equalsIgnoreCase("session.lock");
    }

    private static String portable(Path relative) throws ZipBackupException {
        try {
            return PortableZipPath.fromRelativePath(relative);
        } catch (IllegalArgumentException exception) {
            throw new ZipBackupException("World source contains a path that cannot be restored safely", exception);
        }
    }

    private static void addCollisionKey(Set<String> keys, String path, boolean directory)
            throws ZipBackupException {
        String key = PortableZipPath.collisionKey(path + (directory ? "/" : ""), directory);
        if (!keys.add(key)) {
            throw new ZipBackupException("World source contains paths that collide on another platform");
        }
    }

    private static void requireEntryCapacity(List<SourceEntry> entries) throws ZipBackupException {
        if (entries.size() >= ZipLimits.MAXIMUM_ARCHIVE_ENTRIES - 3) {
            throw new ZipBackupException("World source exceeds the ZIP entry limit");
        }
    }

    record SourceSnapshot(
            Path worldDirectory,
            DirectoryFingerprint rootFingerprint,
            List<SourceEntry> entries) {
        SourceSnapshot {
            Objects.requireNonNull(worldDirectory, "worldDirectory");
            Objects.requireNonNull(rootFingerprint, "rootFingerprint");
            entries = List.copyOf(entries);
        }

        void requireUnchanged() throws IOException {
            requireSameSnapshot(this);
        }
    }

    /** Directory mtimes are deferred on Windows; identity and membership remain authoritative. */
    private record DirectoryFingerprint(
            Object fileKey,
            FileTime creationTime) {
        private DirectoryFingerprint {
            Objects.requireNonNull(creationTime, "creationTime");
        }

        static DirectoryFingerprint from(BasicFileAttributes attributes) {
            return new DirectoryFingerprint(
                    attributes.fileKey(),
                    attributes.creationTime());
        }
    }

    record SourceEntry(
            Path path,
            String relativePath,
            boolean directory,
            long size,
            FileTime lastModifiedTime,
            FileTime creationTime,
            Object fileKey) {
        SourceEntry {
            Objects.requireNonNull(path, "path");
            Objects.requireNonNull(relativePath, "relativePath");
            Objects.requireNonNull(lastModifiedTime, "lastModifiedTime");
            Objects.requireNonNull(creationTime, "creationTime");
            if (size < 0) {
                throw new IllegalArgumentException("Source size must not be negative");
            }
        }

        static SourceEntry directory(Path path, String relativePath, BasicFileAttributes attributes) {
            return new SourceEntry(
                    path,
                    relativePath,
                    true,
                    0,
                    attributes.lastModifiedTime(),
                    attributes.creationTime(),
                    attributes.fileKey());
        }

        static SourceEntry file(Path path, String relativePath, BasicFileAttributes attributes) {
            return new SourceEntry(
                    path,
                    relativePath,
                    false,
                    attributes.size(),
                    attributes.lastModifiedTime(),
                    attributes.creationTime(),
                    attributes.fileKey());
        }

        boolean sameSource(SourceEntry other) {
            if (!path.equals(other.path)
                    || !relativePath.equals(other.relativePath)
                    || directory != other.directory
                    || !creationTime.equals(other.creationTime)
                    || !Objects.equals(fileKey, other.fileKey)) {
                return false;
            }
            return directory
                    || size == other.size && lastModifiedTime.equals(other.lastModifiedTime);
        }
    }
}
