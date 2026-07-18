package dev.ishaankot.worldarchive.storage.git;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.ishaankot.worldarchive.core.DirectoryIdentityMarker;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.stream.Stream;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GitRestorePublicationTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void failedRestoreRollsBackPrivateStagingWithoutPublishing() throws Exception {
        Path target = temporaryDirectory.resolve("failed-restore");

        try (GitRestorePublication publication = GitRestorePublication.create(target)) {
            Files.writeString(publication.staging().resolve("partial.dat"), "partial");
        }

        assertFalse(Files.exists(target));
        assertFalse(hasPrivateRestoreDirectory());
    }

    @Test
    void failedRestorePreservesAnOriginallyEmptyTarget() throws Exception {
        Path target = temporaryDirectory.resolve("existing-empty");
        Files.createDirectory(target);
        BasicFileAttributes attributes = Files.readAttributes(
                target, BasicFileAttributes.class);
        if (attributes.fileKey() == null) {
            Assumptions.assumeTrue(DirectoryIdentityMarker.create(target).isPresent());
        }

        try (GitRestorePublication publication = GitRestorePublication.create(target)) {
            Files.writeString(publication.staging().resolve("partial.dat"), "partial");
        }

        assertTrue(Files.isDirectory(target));
        try (Stream<Path> children = Files.list(target)) {
            assertTrue(children.findAny().isEmpty());
        }
        assertFalse(hasPrivateRestoreDirectory());
    }

    @Test
    void publishesCompletePrivateTreeInOneMove() throws Exception {
        Path target = temporaryDirectory.resolve("published");

        try (GitRestorePublication publication = GitRestorePublication.create(target)) {
            Files.createDirectories(publication.staging().resolve("region"));
            Files.writeString(publication.staging().resolve("region/r.0.0.mca"), "complete");
            publication.publish();
        }

        assertEquals("complete", Files.readString(target.resolve("region/r.0.0.mca")));
        assertFalse(hasPrivateRestoreDirectory());
    }

    @Test
    void targetLinkInsertedDuringRestoreCannotRedirectPublication() throws Exception {
        Path target = temporaryDirectory.resolve("racing-target");
        Path outside = temporaryDirectory.resolve("outside");
        Files.createDirectory(outside);

        try (GitRestorePublication publication = GitRestorePublication.create(target)) {
            Files.writeString(publication.staging().resolve("level.dat"), "private");
            try {
                Files.createSymbolicLink(target, outside);
            } catch (IOException | UnsupportedOperationException exception) {
                Assumptions.abort("Symbolic links are unavailable: " + exception.getMessage());
            }
            assertThrows(GitStorageException.class, publication::publish);
        }

        try (Stream<Path> children = Files.list(outside)) {
            assertTrue(children.findAny().isEmpty());
        }
    }

    @Test
    void nullFileKeyUsesDirectoryMarkerIdentity() throws Exception {
        Path directory = Files.createDirectory(temporaryDirectory.resolve("marked-identity"));
        FileTime creationTime = FileTime.fromMillis(1_234L);
        GitRestorePublication.DirectoryIdentity identity =
                GitRestorePublication.DirectoryIdentity.capture(
                        directory,
                        directoryAttributes(null, creationTime),
                        false,
                        true,
                        "identity unavailable");
        Assumptions.assumeTrue(identity.marker().isPresent());

        assertDoesNotThrow(() -> identity.requireMatches(
                directory,
                directoryAttributes(null, creationTime),
                "identity changed"));
        Files.delete(directory);
        Files.createDirectory(directory);
        assertThrows(GitStorageException.class, () -> identity.requireMatches(
                directory,
                directoryAttributes(null, creationTime),
                "identity changed"));
        assertThrows(GitStorageException.class, () ->
                GitRestorePublication.DirectoryIdentity.capture(
                        directory,
                        directoryAttributes(null, creationTime),
                        false,
                        false,
                        "identity unavailable"));
        assertThrows(GitStorageException.class, () ->
                GitRestorePublication.DirectoryIdentity.capture(
                        directory,
                        directoryAttributes(null, creationTime),
                        true,
                        true,
                        "identity unavailable"));
    }

    private boolean hasPrivateRestoreDirectory() throws IOException {
        try (Stream<Path> children = Files.list(temporaryDirectory)) {
            return children.anyMatch(path -> path.getFileName().toString()
                    .startsWith(".worldarchive-restore-"));
        }
    }

    private static BasicFileAttributes directoryAttributes(Object fileKey, FileTime creationTime) {
        return new BasicFileAttributes() {
            @Override
            public FileTime lastModifiedTime() {
                return creationTime;
            }

            @Override
            public FileTime lastAccessTime() {
                return creationTime;
            }

            @Override
            public FileTime creationTime() {
                return creationTime;
            }

            @Override
            public boolean isRegularFile() {
                return false;
            }

            @Override
            public boolean isDirectory() {
                return true;
            }

            @Override
            public boolean isSymbolicLink() {
                return false;
            }

            @Override
            public boolean isOther() {
                return false;
            }

            @Override
            public long size() {
                return 0L;
            }

            @Override
            public Object fileKey() {
                return fileKey;
            }
        };
    }
}
