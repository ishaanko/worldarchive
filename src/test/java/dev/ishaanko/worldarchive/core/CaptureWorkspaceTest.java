package dev.ishaanko.worldarchive.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Two game instances can share one storage folder. Each test runs the other instance in a child
 * JVM, because file locks belong to a process and one JVM cannot model two.
 */
final class CaptureWorkspaceTest {
    @TempDir
    Path temporaryDirectory;

    private Path captures;

    private Path world;

    private Process otherInstance;

    @BeforeEach
    void createWorld() throws IOException {
        captures = temporaryDirectory.resolve("capture-temp");
        world = Files.createDirectory(temporaryDirectory.resolve("world"));
        Files.writeString(world.resolve("level.dat"), "level", StandardCharsets.UTF_8);
        Files.write(Files.createDirectory(world.resolve("region")).resolve("r.0.0.mca"), new byte[4_096]);
    }

    @AfterEach
    void stopOtherInstance() throws InterruptedException {
        if (otherInstance != null) {
            otherInstance.destroyForcibly().waitFor();
        }
    }

    @Test
    void anActiveCaptureOfAnotherInstanceIsKeptAndRemovedAfterThatInstanceCrashes() throws Exception {
        Path held = startOtherInstance();

        removeAbandonedCaptures();

        assertEquals("level", Files.readString(held.resolve("level.dat")));
        otherInstance.destroyForcibly().waitFor();
        removeAbandonedCaptures();
        assertFalse(Files.exists(held));
    }

    @Test
    void foldersThatWorldArchiveDidNotCreateAreKept() throws Exception {
        Path foreign = Files.createDirectories(captures.resolve("stale-capture"));
        Files.writeString(foreign.resolve("level.dat"), "keep", StandardCharsets.UTF_8);
        Path note = Files.writeString(captures.resolve("notes.txt"), "keep", StandardCharsets.UTF_8);

        removeAbandonedCaptures();

        assertEquals("keep", Files.readString(foreign.resolve("level.dat")));
        assertTrue(Files.exists(note));
    }

    @Test
    void readOnlyCapturesLeftByWorldArchive04AreRemoved() throws Exception {
        String id = "10000000-0000-0000-0000-000000000001";
        Path legacy = Files.createDirectories(captures.resolve(".capture-" + id).resolve("region"));
        Path copied = Files.write(legacy.resolve("r.0.0.mca"), new byte[16]);
        Path marker = Files.writeString(captures.resolve(".capture-" + id + ".worldarchive-owner"), "owner");
        assertTrue(copied.toFile().setReadOnly());
        assertTrue(legacy.toFile().setReadOnly());

        removeAbandonedCaptures();

        assertFalse(Files.exists(captures.resolve(".capture-" + id)));
        assertFalse(Files.exists(marker));
    }

    /** Starts the other instance and returns the capture folder it holds open. */
    private Path startOtherInstance() throws IOException {
        Path java = Path.of(System.getProperty("java.home"), "bin", "java");
        otherInstance = new ProcessBuilder(
                java.toString(),
                "-cp",
                System.getProperty("java.class.path"),
                CaptureHolder.class.getName(),
                captures.toString(),
                world.toString())
                .redirectError(ProcessBuilder.Redirect.INHERIT)
                .start();
        BufferedReader output = new BufferedReader(
                new InputStreamReader(otherInstance.getInputStream(), StandardCharsets.UTF_8));
        String line = output.readLine();
        assertNotNull(line, "the other instance exited before it held a capture");
        Path held = Path.of(line);
        assertTrue(Files.isDirectory(held));
        assertTrue(otherInstance.isAlive());
        return held;
    }

    private void removeAbandonedCaptures() throws IOException {
        new FileSystemBackupCaptureFactory(captures, Optional.empty(), SourceCaptureObserver.NONE)
                .removeAbandonedCaptures();
    }
}
