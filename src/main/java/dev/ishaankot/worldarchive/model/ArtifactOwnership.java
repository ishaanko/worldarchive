package dev.ishaankot.worldarchive.model;

/** Whether WorldArchive owns an artifact or only references an external source. */
public enum ArtifactOwnership {
    MANAGED,
    IMPORTED_MANAGED,
    EXTERNAL
}
