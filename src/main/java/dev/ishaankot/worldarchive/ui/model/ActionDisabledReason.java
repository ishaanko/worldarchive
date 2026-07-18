package dev.ishaankot.worldarchive.ui.model;

/** Stable reason a browser action cannot currently run. */
public enum ActionDisabledReason {
    NONE,
    OPERATION_IN_PROGRESS,
    NO_DESTINATION_CONFIGURED,
    NO_SELECTION,
    NO_DURABLE_COPY,
    REMOTE_NOT_CONFIGURED,
    FOLDER_UNAVAILABLE
}
