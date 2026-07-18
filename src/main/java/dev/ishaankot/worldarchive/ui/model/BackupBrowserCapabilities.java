package dev.ishaankot.worldarchive.ui.model;

/** External capabilities that cannot be inferred from catalog rows alone. */
public record BackupBrowserCapabilities(
        boolean operationInProgress,
        boolean createDestinationConfigured,
        boolean gitRemoteConfigured,
        boolean managedFolderAvailable) {
}
