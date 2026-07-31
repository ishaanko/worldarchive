package dev.ishaankot.worldarchive.core;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class FileSystemSafetyTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void classifiesOrdinaryDirectoriesAndFiles() throws IOException {
        Path directory = Files.createDirectory(temporaryDirectory.resolve("directory"));
        Path file = Files.writeString(temporaryDirectory.resolve("file.dat"), "data");
        BasicFileAttributes directoryAttributes = attributes(directory);
        BasicFileAttributes fileAttributes = attributes(file);

        assertTrue(FileSystemSafety.isOrdinaryDirectory(directory, directoryAttributes));
        assertFalse(FileSystemSafety.isOrdinaryRegularFile(directory, directoryAttributes));
        assertTrue(FileSystemSafety.isOrdinaryRegularFile(file, fileAttributes));
        assertFalse(FileSystemSafety.isOrdinaryDirectory(file, fileAttributes));
    }

    @Test
    void rejectsSymbolicLinks() throws IOException {
        Path target = Files.writeString(temporaryDirectory.resolve("target.dat"), "data");
        Path link = temporaryDirectory.resolve("link.dat");
        try {
            Files.createSymbolicLink(link, target.getFileName());
        } catch (IOException | UnsupportedOperationException | SecurityException exception) {
            Assumptions.assumeTrue(
                    false,
                    "Symbolic links are unavailable: " + exception.getMessage());
        }

        BasicFileAttributes attributes = attributes(link);
        assertFalse(FileSystemSafety.isOrdinaryDirectory(link, attributes));
        assertFalse(FileSystemSafety.isOrdinaryRegularFile(link, attributes));
    }

    private static BasicFileAttributes attributes(Path path) throws IOException {
        return Files.readAttributes(
                path,
                BasicFileAttributes.class,
                LinkOption.NOFOLLOW_LINKS);
    }
}
