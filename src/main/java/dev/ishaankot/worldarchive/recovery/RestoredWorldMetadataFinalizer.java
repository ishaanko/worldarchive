package dev.ishaankot.worldarchive.recovery;

import java.io.IOException;
import java.nio.file.Path;

/** Applies game-specific display metadata before a restored world is published. */
@FunctionalInterface
public interface RestoredWorldMetadataFinalizer {
    RestoredWorldMetadataFinalizer NO_OP = (worldDirectory, displayName) -> {
    };

    void finalizeDisplayName(Path worldDirectory, String displayName) throws IOException;
}
