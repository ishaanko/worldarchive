package dev.ishaankot.worldarchive.runtime;

import dev.ishaankot.worldarchive.core.BackupBackend;
import dev.ishaankot.worldarchive.core.BackupDestinationSelector;
import dev.ishaankot.worldarchive.core.ConfiguredBackupDestinationSelector;
import dev.ishaankot.worldarchive.core.CreateBackupRequest;
import dev.ishaankot.worldarchive.model.DestinationType;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Filters configured Git work until both Git and Git LFS have passed their runtime probe. */
final class RuntimeDestinationSelector implements BackupDestinationSelector {
    private final ConfiguredBackupDestinationSelector configured;

    private final AtomicBoolean gitToolsAvailable = new AtomicBoolean();

    private final AtomicReference<Optional<String>> warning =
            new AtomicReference<>(Optional.empty());

    RuntimeDestinationSelector(ConfiguredBackupDestinationSelector configured) {
        this.configured = Objects.requireNonNull(configured, "configured");
    }

    @Override
    public List<BackupBackend> select(CreateBackupRequest request) {
        return configured.select(request).stream()
                .filter(backend -> backend.destinationType() != DestinationType.GIT
                        || gitToolsAvailable.get())
                .toList();
    }

    void gitToolsAvailable(boolean available) {
        gitToolsAvailable.set(available);
        warning.set(available
                ? Optional.empty()
                : Optional.of("Git backups are unavailable because Git or Git LFS is missing"));
    }

    void gitToolProbeFailed() {
        gitToolsAvailable.set(false);
        warning.set(Optional.of(
                "Git backups are unavailable because the Git tool check failed"));
    }

    void gitDisabled() {
        gitToolsAvailable.set(false);
        warning.set(Optional.empty());
    }

    Optional<String> warning() {
        return warning.get();
    }
}
