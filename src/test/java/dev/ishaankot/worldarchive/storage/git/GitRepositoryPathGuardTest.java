package dev.ishaankot.worldarchive.storage.git;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GitRepositoryPathGuardTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void refusesToFollowASymlinkedLockFile() throws Exception {
        Path outside = temporaryDirectory.resolve("outside.txt");
        Path lock = temporaryDirectory.resolve("repository.worldarchive.lock");
        Files.writeString(outside, "unchanged");
        try {
            Files.createSymbolicLink(lock, outside);
        } catch (IOException | UnsupportedOperationException exception) {
            Assumptions.abort("Symbolic links are unavailable: " + exception.getMessage());
        }

        assertThrows(GitStorageException.class, () -> GitRepositoryPathGuard.openLockFile(lock));
        assertEquals("unchanged", Files.readString(outside));
    }
}
