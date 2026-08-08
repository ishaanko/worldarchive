package dev.ishaankot.worldarchive.runtime;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.ishaankot.worldarchive.model.DestinationResult;
import dev.ishaankot.worldarchive.model.DestinationType;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class RuntimeDestinationPathGuardTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void rejectsGitRootChangeForSuccessOrPendingSyncArtifacts() {
        RuntimeStoragePaths current = paths("old.git", "archives");
        RuntimeStoragePaths replacement = paths("new.git", "archives");

        assertThrows(
                IllegalArgumentException.class,
                () -> RuntimeDestinationPathGuard.requireAllowed(
                        current,
                        replacement,
                        List.of(DestinationResult.success(DestinationType.GIT, "git-ref"))));
        assertThrows(
                IllegalArgumentException.class,
                () -> RuntimeDestinationPathGuard.requireAllowed(
                        current,
                        replacement,
                        List.of(DestinationResult.pendingSync(
                                DestinationType.GIT,
                                "git-ref",
                                "Remote synchronization is pending"))));
    }

    @Test
    void rejectsZipRootChangeOnlyWhenDurableZipArtifactsDependOnIt() {
        RuntimeStoragePaths current = paths("repo.git", "old-archives");
        RuntimeStoragePaths replacement = paths("repo.git", "new-archives");

        assertThrows(
                IllegalArgumentException.class,
                () -> RuntimeDestinationPathGuard.requireAllowed(
                        current,
                        replacement,
                        List.of(DestinationResult.success(DestinationType.ZIP, "world/archive.zip"))));
        assertDoesNotThrow(() -> RuntimeDestinationPathGuard.requireAllowed(
                current,
                replacement,
                List.of(
                        DestinationResult.success(DestinationType.GIT, "git-ref"),
                        DestinationResult.failed(DestinationType.ZIP, "Archive creation failed"))));
    }

    private RuntimeStoragePaths paths(String git, String zip) {
        return new RuntimeStoragePaths(
                temporaryDirectory.resolve(git),
                temporaryDirectory.resolve(zip));
    }
}
