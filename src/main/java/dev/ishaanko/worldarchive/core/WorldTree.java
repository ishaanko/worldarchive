package dev.ishaanko.worldarchive.core;

import dev.ishaanko.worldarchive.support.FileSystemSafety;
import dev.ishaanko.worldarchive.support.PortablePath;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * One walk of a world folder: the folders and plain files that a backup keeps, and the newest
 * file modification time. The walk skips WorldArchive's {@code .worldarchive} folder and the
 * game's {@code session.lock} at the top of the world, and every entry named {@code .git} at any
 * depth, because Git cannot store one. It refuses links, special files, and names that another
 * operating system cannot restore, and every such error names the path.
 */
record WorldTree(List<Folder> folders, List<SourceFile> files, long byteCount, FileTime newestModified) {
    WorldTree {
        folders = List.copyOf(folders);
        files = List.copyOf(files);
        Objects.requireNonNull(newestModified, "newestModified");
    }

    /** Walks {@code world}; a file that disappears during the walk is a {@link CaptureChangedException}. */
    static WorldTree scan(Path world) throws IOException {
        Scanner scanner = new Scanner(world);
        Files.walkFileTree(world, scanner);
        scanner.folders.sort(Comparator
                .comparingInt((Folder folder) -> depth(folder.portablePath()))
                .thenComparing(Folder::portablePath));
        scanner.files.sort(Comparator.comparing(SourceFile::portablePath));
        return new WorldTree(scanner.folders, scanner.files, scanner.byteCount, scanner.newestModified);
    }

    /** Requires {@code later} to list the same folders and the same files, with the same identity and size. */
    void requireUnchanged(WorldTree later) throws CaptureChangedException {
        if (!folders.equals(later.folders) || files.size() != later.files.size()) {
            throw changed("files or folders were added or removed");
        }
        for (int index = 0; index < files.size(); index++) {
            SourceFile expected = files.get(index);
            SourceFile observed = later.files.get(index);
            if (!expected.portablePath().equals(observed.portablePath())
                    || !expected.state().sameFile(observed.state())) {
                throw changed("\"" + observed.portablePath() + "\" was replaced or resized");
            }
        }
    }

    static CaptureChangedException changed(String detail) {
        return new CaptureChangedException(
                "The world changed while its private capture was being made (" + detail + ")."
                        + " Try the backup again.");
    }

    private static int depth(String portablePath) {
        return portablePath.isEmpty()
                ? 0
                : (int) portablePath.chars().filter(character -> character == '/').count() + 1;
    }

    /** A folder of the world; the empty path is the world folder itself. */
    record Folder(String portablePath, Object fileKey, FileTime created) {
    }

    /** A plain file of the world with the state the walk saw. */
    record SourceFile(Path path, String portablePath, FileState state) {
    }

    /**
     * The identity, size and times of a file, read without following links. On macOS the
     * creation time is left out, because setting an older modification time can move it.
     */
    record FileState(Object fileKey, long size, FileTime modified, FileTime created) {
        private static final boolean IGNORE_CREATION_TIME = System.getProperty("os.name").startsWith("Mac");

        static FileState of(BasicFileAttributes attributes) {
            return new FileState(
                    attributes.fileKey(),
                    attributes.size(),
                    attributes.lastModifiedTime(),
                    creationTime(attributes));
        }

        /** Reads the file itself, not a directory listing, which Windows updates late for open files. */
        static FileState read(SourceFile file) throws IOException {
            BasicFileAttributes attributes;
            try {
                attributes = Files.readAttributes(
                        file.path(), BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            } catch (NoSuchFileException exception) {
                throw changed("\"" + file.portablePath() + "\" disappeared");
            }
            if (!FileSystemSafety.isOrdinaryRegularFile(attributes)) {
                throw changed("\"" + file.portablePath() + "\" was replaced");
            }
            return of(attributes);
        }

        /** Same file and size; not the modification time, which a directory walk can report late. */
        boolean sameFile(FileState other) {
            return Objects.equals(fileKey, other.fileKey)
                    && size == other.size
                    && created.equals(other.created);
        }

        private static FileTime creationTime(BasicFileAttributes attributes) {
            return IGNORE_CREATION_TIME ? FileTime.fromMillis(0) : attributes.creationTime();
        }
    }

    private static final class Scanner extends SimpleFileVisitor<Path> {
        private final Path world;

        private final List<Folder> folders = new ArrayList<>();

        private final List<SourceFile> files = new ArrayList<>();

        /** Collision key of every path seen so far, and whether it names a file. */
        private final Map<String, Boolean> seenAsFile = new HashMap<>();

        private long byteCount;

        private FileTime newestModified = FileTime.fromMillis(0);

        private Scanner(Path world) {
            this.world = world;
        }

        @Override
        public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes)
                throws IOException {
            requireNotInterrupted();
            Path relative = world.relativize(directory);
            boolean isWorld = relative.toString().isEmpty();
            if (!isWorld && isSkipped(relative, true)) {
                return FileVisitResult.SKIP_SUBTREE;
            }
            if (!FileSystemSafety.isOrdinaryDirectory(attributes)) {
                throw unsupported("\"" + relative + "\" is a link or a special folder."
                        + " Move it out of the world folder, then try again.");
            }
            String portable = isWorld ? "" : portable(relative);
            if (!isWorld) {
                register(portable, false);
            }
            folders.add(new Folder(portable, attributes.fileKey(), FileState.of(attributes).created()));
            requireCapacity();
            return FileVisitResult.CONTINUE;
        }

        @Override
        public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
            requireNotInterrupted();
            Path relative = world.relativize(file);
            if (isSkipped(relative, false)) {
                return FileVisitResult.CONTINUE;
            }
            if (!FileSystemSafety.isOrdinaryRegularFile(attributes)) {
                throw unsupported("\"" + relative + "\" is a link or a special file."
                        + " Move it out of the world folder, then try again.");
            }
            String portable = portable(relative);
            register(portable, true);
            FileState state = FileState.of(attributes);
            if (state.size() > WorldInventory.MAXIMUM_BYTES - byteCount) {
                throw unsupported("It is larger than " + (WorldInventory.MAXIMUM_BYTES >> 40) + " TiB.");
            }
            files.add(new SourceFile(file, portable, state));
            byteCount += state.size();
            if (state.modified().compareTo(newestModified) > 0) {
                newestModified = state.modified();
            }
            requireCapacity();
            return FileVisitResult.CONTINUE;
        }

        @Override
        public FileVisitResult visitFileFailed(Path file, IOException failure) throws IOException {
            throw unreadable(file, failure);
        }

        @Override
        public FileVisitResult postVisitDirectory(Path directory, IOException failure) throws IOException {
            if (failure != null) {
                throw unreadable(directory, failure);
            }
            return FileVisitResult.CONTINUE;
        }

        private IOException unreadable(Path entry, IOException failure) {
            Path relative = world.relativize(entry);
            if (failure instanceof NoSuchFileException) {
                return changed("\"" + relative + "\" disappeared");
            }
            return new IOException(
                    "World entry \"" + relative + "\" could not be read: " + failure.getMessage(), failure);
        }

        /**
         * Refuses a second path that Windows or macOS would treat as the same one, because they
         * ignore letter case and Unicode normalization, and a file where another path needs a folder.
         */
        private void register(String portable, boolean isFile) throws IOException {
            String[] segments = portable.split("/");
            StringBuilder prefix = new StringBuilder();
            for (int index = 0; index < segments.length; index++) {
                if (!prefix.isEmpty()) {
                    prefix.append('/');
                }
                prefix.append(segments[index]);
                boolean last = index == segments.length - 1;
                Boolean previous = seenAsFile.putIfAbsent(
                        PortablePath.collisionKey(prefix.toString()), last && isFile);
                if (previous != null && (last || previous)) {
                    throw unsupported("\"" + portable + "\" has the same name as another file or folder"
                            + " on Windows and macOS. Rename one of them, then try again.");
                }
            }
        }

        private void requireCapacity() throws IOException {
            if (files.size() > WorldInventory.MAXIMUM_FILES || folders.size() > WorldInventory.MAXIMUM_FILES) {
                throw unsupported("It has more than " + WorldInventory.MAXIMUM_FILES + " files or folders.");
            }
        }

        private static String portable(Path relative) throws IOException {
            try {
                return PortablePath.fromRelativePath(relative);
            } catch (IllegalArgumentException exception) {
                throw unsupported(exception.getMessage() + ". Rename or remove it, then try again.");
            }
        }

        /** {@code .git} at any depth; {@code .worldarchive} and the {@code session.lock} file at the top. */
        private static boolean isSkipped(Path relative, boolean isFolder) {
            String name = relative.getFileName().toString();
            if (name.equalsIgnoreCase(".git")) {
                return true;
            }
            return relative.getNameCount() == 1
                    && (name.equalsIgnoreCase(".worldarchive")
                            || !isFolder && name.equalsIgnoreCase("session.lock"));
        }

        private static IOException unsupported(String reason) {
            return new IOException("This world cannot be backed up. " + reason);
        }

        private static void requireNotInterrupted() throws InterruptedIOException {
            if (Thread.currentThread().isInterrupted()) {
                throw new InterruptedIOException("World capture was interrupted");
            }
        }
    }
}
