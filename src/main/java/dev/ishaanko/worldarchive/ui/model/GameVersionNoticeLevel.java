package dev.ishaanko.worldarchive.ui.model;

/** How the restore screen colors a version notice. */
public enum GameVersionNoticeLevel {
    /** The backup's or the running game's version is unknown, so the two cannot be compared. */
    UNKNOWN,

    MATCHED,

    /** Minecraft upgrades the restored copy when it opens. */
    UPGRADE,

    /** The backup is newer than the running game; the copy may not open. */
    DOWNGRADE
}
