package dev.ishaankot.worldarchive.importing;

/** Whether a Git source also becomes the future remote for an existing live world. */
public enum GitConnectionMode {
    CONNECT,
    RECOVERY_ONLY
}
