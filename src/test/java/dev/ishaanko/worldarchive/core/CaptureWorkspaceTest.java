package dev.ishaanko.worldarchive.core;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class CaptureWorkspaceTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void startupRemovesAMarkerWhoseCaptureDirectoryIsGone() throws IOException {
        Path captures = Files.createDirectory(temporaryDirectory.resolve("captures"));
        UUID captureId = UUID.randomUUID();
        Path orphan = captures.resolve(".capture-" + captureId + ".worldarchive-owner");
        Files.writeString(
                orphan,
                "worldarchive-private-capture-v1:" + captureId + "\n",
                StandardCharsets.UTF_8);
        Path foreign = captures.resolve(".capture-" + UUID.randomUUID() + ".worldarchive-owner");
        Files.writeString(foreign, "not a worldarchive marker\n", StandardCharsets.UTF_8);

        new CaptureWorkspace(captures);

        assertFalse(Files.exists(orphan));
        assertTrue(Files.exists(foreign));
    }
}
