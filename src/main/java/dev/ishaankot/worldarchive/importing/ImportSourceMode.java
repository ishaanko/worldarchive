package dev.ishaankot.worldarchive.importing;

/** Persistence and hydration mode for one external import source. */
public enum ImportSourceMode {
    ZIP_LINK,
    GIT_REMOTE_BACKED,
    GIT_FULL_DOWNLOAD
}
