package dev.ishaankot.worldarchive.storage.git;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.stream.Stream;

/** Bounded, no-follow cleanup for WorldArchive-owned temporary trees. */
final class GitTemporaryFiles {
    private static final int MAXIMUM_ENTRIES = GitInventory.MAXIMUM_FILES * 2;

    private GitTemporaryFiles() {
    }

    static void deleteTree(Path root) {
        deleteTree(root, false);
    }

    /**
     * Best-effort cleanup for private Git workspaces that bails entirely, leaving the tree
     * untouched, when any {@code .lock} file is present anywhere beneath {@code root}. That
     * short-circuit protects in-flight Git operations racing the repository {@code FileLock}.
     */
    static void deleteUnlessLocked(Path root) {
        deleteTree(root, true);
    }

    private static void deleteTree(Path root, boolean preserveWhenLocked) {
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        if (preserveWhenLocked && containsLockFile(root)) {
            return;
        }
        try {
            SafetyScan scan = new SafetyScan();
            Files.walkFileTree(root, scan);
            Files.walkFileTree(root, new DeletingVisitor());
        } catch (IOException | RuntimeException ignored) {
            // Private temporary data remains for operating-system or user recovery.
        }
    }

    private static boolean containsLockFile(Path root) {
        try (Stream<Path> paths = Files.walk(root)) {
            return paths.anyMatch(path -> path.getFileName() != null
                    && path.getFileName().toString().endsWith(".lock"));
        } catch (IOException exception) {
            // The tree cannot be safely inspected; treat it as locked and leave it alone.
            return true;
        }
    }

    private static final class SafetyScan extends SimpleFileVisitor<Path> {
        private int entries;

        @Override
        public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes)
                throws IOException {
            count();
            requireSafe(attributes);
            return FileVisitResult.CONTINUE;
        }

        @Override
        public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
            count();
            if (!attributes.isRegularFile() || attributes.isSymbolicLink() || attributes.isOther()) {
                throw new IOException("Temporary Git tree contains an unsafe entry");
            }
            return FileVisitResult.CONTINUE;
        }

        private void count() throws IOException {
            if (++entries > MAXIMUM_ENTRIES) {
                throw new IOException("Temporary Git tree exceeds its cleanup limit");
            }
        }

        private static void requireSafe(BasicFileAttributes attributes) throws IOException {
            if (!attributes.isDirectory() || attributes.isSymbolicLink() || attributes.isOther()) {
                throw new IOException("Temporary Git tree contains an unsafe directory");
            }
        }
    }

    private static final class DeletingVisitor extends SimpleFileVisitor<Path> {
        private int entries;

        @Override
        public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes)
                throws IOException {
            count();
            SafetyScan.requireSafe(attributes);
            return FileVisitResult.CONTINUE;
        }

        @Override
        public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
            count();
            if (!attributes.isRegularFile() || attributes.isSymbolicLink() || attributes.isOther()) {
                throw new IOException("Temporary Git tree changed during cleanup");
            }
            Files.delete(file);
            return FileVisitResult.CONTINUE;
        }

        @Override
        public FileVisitResult postVisitDirectory(Path directory, IOException exception) throws IOException {
            if (exception != null) {
                throw exception;
            }
            Files.delete(directory);
            return FileVisitResult.CONTINUE;
        }

        private void count() throws IOException {
            if (++entries > MAXIMUM_ENTRIES) {
                throw new IOException("Temporary Git tree exceeds its cleanup limit");
            }
        }
    }
}
