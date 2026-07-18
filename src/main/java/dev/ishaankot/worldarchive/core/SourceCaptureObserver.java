package dev.ishaankot.worldarchive.core;

import java.io.IOException;
import java.nio.file.Path;

/** Optional capture boundary hooks for instrumentation and lifecycle coordination tests. */
public interface SourceCaptureObserver {
    SourceCaptureObserver NONE = new SourceCaptureObserver() {
    };

    default void beforeFileCopy(Path relativePath) throws IOException, InterruptedException {
    }

    default void afterFileCopy(Path relativePath) throws IOException, InterruptedException {
    }
}
