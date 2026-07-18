package dev.ishaankot.worldarchive.storage.git;

import java.util.Objects;
import java.util.regex.Pattern;

/** One validated ordinary blob in a snapshot tree. */
record GitTreeEntry(String mode, String objectId, String path) {
    private static final Pattern OBJECT_ID = Pattern.compile("[0-9a-f]{40}");

    GitTreeEntry {
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(objectId, "objectId");
        Objects.requireNonNull(path, "path");
        if ((!mode.equals("100644") && !mode.equals("100755"))
                || !OBJECT_ID.matcher(objectId).matches()
                || path.isEmpty()) {
            throw new IllegalArgumentException("Invalid ordinary Git tree entry");
        }
    }
}
