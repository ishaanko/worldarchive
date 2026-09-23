package dev.ishaanko.worldarchive.storage.git;

/** What installing one imported snapshot did. */
public enum GitImportInstallStatus {
    /** The snapshot passed every check and is now a backup of its world. */
    ADDED,
    /** The world already has this exact snapshot. */
    UNCHANGED,
    /** The world already has a different snapshot with the same backup ID; nothing changed. */
    CONFLICT,
    /** The snapshot or its LFS objects failed a check; nothing was installed for it. */
    FAILED
}
