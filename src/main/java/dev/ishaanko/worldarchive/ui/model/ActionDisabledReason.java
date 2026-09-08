package dev.ishaanko.worldarchive.ui.model;

/** Stable reason a browser action cannot currently run. */
public enum ActionDisabledReason {
    NONE,
    OPERATION_IN_PROGRESS,
    SOURCE_UNAVAILABLE,
    NO_DESTINATION_CONFIGURED,
    NO_SELECTION,
    MULTIPLE_SELECTED,
    NO_DURABLE_COPY,
    REMOTE_NOT_CONFIGURED,
    FOLDER_UNAVAILABLE
}
