package dev.ishaanko.worldarchive.ui.model;

/** Why a backup-browser action cannot run; {@link #NONE} when it can. */
public enum ActionDisabledReason {
    NONE,
    OPERATION_IN_PROGRESS,
    SOURCE_UNAVAILABLE,
    /** The world folder is there, but the settings let no new backup be made; see {@link BackupBrowserCapabilities#createBlock}. */
    CREATE_BLOCKED,
    NO_SELECTION,
    MULTIPLE_SELECTED,
    NO_DURABLE_COPY,
    REMOTE_NOT_CONFIGURED,
    FOLDER_UNAVAILABLE
}
