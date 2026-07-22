package dev.ishaankot.worldarchive.storage.git;

import java.util.Objects;

/** Sanitized problem found while inspecting a fetched backup history. */
public record GitImportIssue(String location, String message) {
    public GitImportIssue {
        location = Objects.requireNonNull(location, "location");
        message = Objects.requireNonNull(message, "message");
    }
}
