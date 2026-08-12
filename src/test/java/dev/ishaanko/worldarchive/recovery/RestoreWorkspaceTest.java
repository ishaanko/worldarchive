package dev.ishaanko.worldarchive.recovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class RestoreWorkspaceTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void creationFailureRemovesTheNewStagingDirectory() throws Exception {
        Path worlds = Files.createDirectory(temporaryDirectory.resolve("worlds"));
        RestoreWorkspace workspace = RestoreWorkspace.open(worlds, Files::move);

        assertThrows(
                IOException.class,
                () -> workspace.createStaging(staging -> {
                    Files.writeString(staging.resolve("partial.txt"), "partial");
                    throw new IOException("simulated staging initialization failure");
                }));

        try (var children = Files.list(worlds)) {
            assertEquals(0, children.count());
        }
    }
}
