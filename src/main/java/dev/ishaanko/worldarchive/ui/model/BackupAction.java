package dev.ishaanko.worldarchive.ui.model;

/** The buttons of the backup browser; {@link BackupActionPolicy} decides which can run. */
public enum BackupAction {
    CREATE,
    RESTORE,
    DELETE,
    SYNC,
    VERIFY,
    OPEN_FOLDER,
    STORAGE,
    SETTINGS
}
