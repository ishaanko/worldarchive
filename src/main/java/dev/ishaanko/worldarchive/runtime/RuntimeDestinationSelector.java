package dev.ishaanko.worldarchive.runtime;

import dev.ishaanko.worldarchive.core.BackupBackend;
import dev.ishaanko.worldarchive.core.BackupDestinationSelector;
import dev.ishaanko.worldarchive.core.ConfiguredBackupDestinationSelector;
import dev.ishaanko.worldarchive.core.CreateBackupRequest;
import dev.ishaanko.worldarchive.model.DestinationType;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Picks the destinations of a backup: the ones the settings turn on, without Git until the check
 * of Git and Git LFS that runs when the settings load has passed. So ZIP backups keep working on a
 * computer without Git, as the README promises.
 */
public final class RuntimeDestinationSelector implements BackupDestinationSelector {
    private final ConfiguredBackupDestinationSelector configured;

    private final AtomicReference<GitTools> gitTools = new AtomicReference<>(GitTools.UNCHECKED);

    RuntimeDestinationSelector(ConfiguredBackupDestinationSelector configured) {
        this.configured = Objects.requireNonNull(configured, "configured");
    }

    /** What the last check found out about Git and Git LFS. */
    enum GitTools {
        UNCHECKED,
        AVAILABLE,
        MISSING,
        CHECK_FAILED,
        TURNED_OFF
    }

    /**
     * @throws IllegalArgumentException when the settings bind the request's world identity to
     *         another folder
     */
    @Override
    public List<BackupBackend> select(CreateBackupRequest request) {
        return configured.select(request).stream()
                .filter(backend -> backend.destinationType() != DestinationType.GIT
                        || gitTools.get() == GitTools.AVAILABLE)
                .toList();
    }

    /** True when the settings turn on a destination for the request, whether or not Git works. */
    boolean hasConfiguredDestination(CreateBackupRequest request) {
        return !configured.select(Objects.requireNonNull(request, "request")).isEmpty();
    }

    void gitTools(GitTools checked) {
        gitTools.set(Objects.requireNonNull(checked, "checked"));
    }

    GitTools gitTools() {
        return gitTools.get();
    }

    /** Why Git backups are off although the settings turn them on; empty when they are not off. */
    public Optional<String> warning() {
        return switch (gitTools.get()) {
            case MISSING -> Optional.of(
                    "Git backups are off because Git or Git LFS is missing. Install both, then restart the game.");
            case CHECK_FAILED -> Optional.of(
                    "Git backups are off because WorldArchive could not check Git. Restart the game to try again.");
            case UNCHECKED, AVAILABLE, TURNED_OFF -> Optional.empty();
        };
    }
}
