package dev.ishaankot.worldarchive.importing;

/** How imported Git LFS content is retained locally. */
public enum GitHydrationMode {
    FULL_DOWNLOAD,
    REMOTE_BACKED
}
