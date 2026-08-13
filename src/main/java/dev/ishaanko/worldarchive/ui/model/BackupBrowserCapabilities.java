package dev.ishaanko.worldarchive.ui.model;

import java.util.Objects;
import java.util.Optional;

/** External capabilities that cannot be inferred from catalog rows alone. */
public record BackupBrowserCapabilities(
        boolean operationInProgress,
        boolean sourceAvailable,
        boolean createDestinationConfigured,
        boolean gitRemoteConfigured,
        boolean managedFolderAvailable,
        Optional<String> warning) {
    public BackupBrowserCapabilities {
        warning = Objects.requireNonNull(warning, "warning");
    }

    public BackupBrowserCapabilities(
            boolean operationInProgress,
            boolean sourceAvailable,
            boolean createDestinationConfigured,
            boolean gitRemoteConfigured,
            boolean managedFolderAvailable) {
        this(
                operationInProgress,
                sourceAvailable,
                createDestinationConfigured,
                gitRemoteConfigured,
                managedFolderAvailable,
                Optional.empty());
    }
}
