package dev.ishaanko.worldarchive.storage.git;

import java.util.Objects;

/** One ordinary file in a snapshot tree: its blob and its portable path. */
record GitTreeEntry(String objectId, String path) {
    GitTreeEntry {
        Objects.requireNonNull(objectId, "objectId");
        Objects.requireNonNull(path, "path");
        if (!GitRepository.isObjectId(objectId) || path.isEmpty()) {
            throw new IllegalArgumentException("Invalid Git tree entry");
        }
    }
}
