package dev.ishaankot.worldarchive.storage.git;

import java.util.Objects;
import java.util.regex.Pattern;

/** Small dependency-free validation shared by public import value objects. */
final class GitImportValidation {
    private static final Pattern OBJECT_ID = Pattern.compile("[0-9a-f]{40}");

    private GitImportValidation() {
    }

    static String objectId(String value) {
        String objectId = Objects.requireNonNull(value, "commitId");
        if (!OBJECT_ID.matcher(objectId).matches()) {
            throw new IllegalArgumentException("Imported Git commit is not a SHA-1 object ID");
        }
        return objectId;
    }
}
