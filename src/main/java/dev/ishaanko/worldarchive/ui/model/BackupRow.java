package dev.ishaanko.worldarchive.ui.model;

import dev.ishaanko.worldarchive.model.BackupId;
import dev.ishaanko.worldarchive.model.BackupManifest;
import dev.ishaanko.worldarchive.model.BackupRecord;
import dev.ishaanko.worldarchive.model.BackupTrigger;
import dev.ishaanko.worldarchive.model.DestinationResult;
import dev.ishaanko.worldarchive.model.DestinationType;
import dev.ishaanko.worldarchive.model.GameVersionStamp;
import dev.ishaanko.worldarchive.model.SyncStatus;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * One backup as the browser shows it. The browser builds its rows once per catalog load, off the
 * render thread, and every browser rule reads rows instead of catalog records.
 */
public record BackupRow(
        BackupId backupId,
        Instant createdAt,
        Optional<String> label,
        BackupTrigger trigger,
        Copy git,
        Copy zip,
        long logicalSizeBytes,
        long changedFileCount,
        Optional<GameVersionStamp> gameVersion) {
    public BackupRow {
        Objects.requireNonNull(backupId, "backupId");
        Objects.requireNonNull(createdAt, "createdAt");
        label = Objects.requireNonNull(label, "label");
        Objects.requireNonNull(trigger, "trigger");
        Objects.requireNonNull(git, "git");
        Objects.requireNonNull(zip, "zip");
        if (logicalSizeBytes < 0 || changedFileCount < 0) {
            throw new IllegalArgumentException("Backup counts must not be negative");
        }
        gameVersion = Objects.requireNonNull(gameVersion, "gameVersion");
    }

    public static BackupRow from(BackupRecord record) {
        BackupManifest manifest = record.manifest();
        List<DestinationResult> destinations = record.result().destinations();
        return new BackupRow(
                manifest.backupId(),
                manifest.createdAt(),
                manifest.label(),
                manifest.trigger(),
                Copy.of(destinations, DestinationType.GIT),
                Copy.of(destinations, DestinationType.ZIP),
                manifest.sourceByteCount(),
                manifest.changedFileCount(),
                manifest.gameVersion());
    }

    /** True when Git or ZIP holds a copy that can be restored. */
    public boolean hasDurableCopy() {
        return git.durable() || zip.durable();
    }

    /** True when the Git copy is also on the world's remote, so a delete removes it there too. */
    public boolean onRemote() {
        return git.durable() && git.syncStatus() == SyncStatus.SYNCED;
    }

    /** The copies the browser shows for this backup; a delete request names them, so it deletes only these. */
    public List<DestinationResult> copies() {
        return Stream.of(git, zip).flatMap(copy -> copy.result().stream()).toList();
    }

    /** One destination's copy as the catalog records it; empty when that destination wrote none. */
    public record Copy(Optional<DestinationResult> result) {
        public Copy {
            Objects.requireNonNull(result, "result");
        }

        private static Copy of(List<DestinationResult> destinations, DestinationType type) {
            return new Copy(destinations.stream()
                    .filter(destination -> destination.destination() == type)
                    .findFirst());
        }

        /** True when this destination holds the backup. */
        public boolean durable() {
            return result.filter(DestinationResult::isDurable).isPresent();
        }

        /** The state of the remote copy; NOT_CONFIGURED when this destination wrote nothing. */
        public SyncStatus syncStatus() {
            return result.map(DestinationResult::syncStatus).orElse(SyncStatus.NOT_CONFIGURED);
        }
    }
}
