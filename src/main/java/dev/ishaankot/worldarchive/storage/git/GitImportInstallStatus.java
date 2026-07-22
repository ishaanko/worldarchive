package dev.ishaankot.worldarchive.storage.git;

/** Exact ref publication result for one imported Git snapshot. */
public enum GitImportInstallStatus {
    ADDED,
    UNCHANGED,
    CONFLICT
}
