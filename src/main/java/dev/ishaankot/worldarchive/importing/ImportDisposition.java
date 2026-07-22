package dev.ishaankot.worldarchive.importing;

/** Non-destructive catalog outcome predicted during an import preview. */
public enum ImportDisposition {
    ADD,
    MERGE,
    UNCHANGED,
    CONFLICT
}
