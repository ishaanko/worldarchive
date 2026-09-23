package dev.ishaanko.worldarchive.core;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Hooks around each file copy of a capture, which tests use to change or block a world while it
 * is copied. Several copy workers call them at once, for different files.
 */
public interface SourceCaptureObserver {
    SourceCaptureObserver NONE = new SourceCaptureObserver() {
    };

    default void beforeFileCopy(Path relativePath) throws IOException, InterruptedException {
    }

    default void afterFileCopy(Path relativePath) throws IOException, InterruptedException {
    }
}
