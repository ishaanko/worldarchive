package dev.ishaankot.worldarchive.importing;

/** Persistence and hydration mode for one external import source. */
public enum ImportSourceMode {
    /**
     * Legacy zip link-in-place import. No longer created; kept only so a
     * pre-upgrade import source registry still decodes instead of failing to load.
     */
    ZIP_LINK,
    /**
     * Legacy remote-backed Git import. No longer created; kept only so a
     * pre-upgrade import source registry still decodes and its already-imported
     * artifacts remain readable, instead of failing to load.
     */
    GIT_REMOTE_BACKED,
    GIT_FULL_DOWNLOAD
}
