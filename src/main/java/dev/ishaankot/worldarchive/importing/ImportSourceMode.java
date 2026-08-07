package dev.ishaankot.worldarchive.importing;

/** Persistence and hydration mode for one external import source. */
public enum ImportSourceMode {
    /**
     * Legacy zip link-in-place import. No longer created; kept only so a
     * pre-upgrade import source registry still decodes instead of failing to load.
     */
    ZIP_LINK,
    GIT_REMOTE_BACKED,
    GIT_FULL_DOWNLOAD
}
