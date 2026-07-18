package dev.ishaankot.worldarchive.storage.zip;

import java.io.IOException;
import java.nio.file.Path;

/** Package-private deterministic fault and mutation points used by adversarial storage tests. */
interface ZipStoreHooks {
    default void archiveCompleted(Path partialArchive) throws IOException {
    }

    default void archiveInspected(Path partialArchive) throws IOException {
    }

    default void archivePublished(Path archive) throws IOException {
    }

    default void checksumPublished(Path archive, Path checksum) throws IOException {
    }

    default void restoreCopyCompleted(Path archive, Path privateCopy) throws IOException {
    }

    default void restoreEntryExtracted(Path target) throws IOException {
    }

    default void beforeArchiveDelete(Path archive) throws IOException {
    }

    default void beforeChecksumDelete(Path checksum) throws IOException {
    }
}
