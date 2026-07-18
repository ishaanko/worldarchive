package dev.ishaankot.worldarchive.ui.model;

import dev.ishaankot.worldarchive.model.BackupId;
import dev.ishaankot.worldarchive.model.BackupRecord;
import dev.ishaankot.worldarchive.model.BackupStatus;
import dev.ishaankot.worldarchive.model.BackupTrigger;
import dev.ishaankot.worldarchive.model.DestinationResult;
import dev.ishaankot.worldarchive.model.DestinationType;
import dev.ishaankot.worldarchive.model.SensitiveDataRedactor;
import dev.ishaankot.worldarchive.model.SyncStatus;
import dev.ishaankot.worldarchive.model.VerificationStatus;
import dev.ishaankot.worldarchive.model.WorldId;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Deterministic row data for the backup browser. */
public record BackupRow(
        BackupId backupId,
        WorldId worldId,
        String worldName,
        Instant createdAt,
        Optional<String> label,
        BackupTrigger trigger,
        BackupStatus status,
        BackupDestinationView git,
        BackupDestinationView zip,
        long logicalSizeBytes,
        long changedFileCount) {
    public BackupRow {
        Objects.requireNonNull(backupId, "backupId");
        Objects.requireNonNull(worldId, "worldId");
        worldName = SensitiveDataRedactor.redact(Objects.requireNonNull(worldName, "worldName"));
        Objects.requireNonNull(createdAt, "createdAt");
        label = Objects.requireNonNull(label, "label").map(SensitiveDataRedactor::redact);
        Objects.requireNonNull(trigger, "trigger");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(git, "git");
        Objects.requireNonNull(zip, "zip");
        if (git.destination() != DestinationType.GIT || zip.destination() != DestinationType.ZIP) {
            throw new IllegalArgumentException("Destination views must use their matching destination types");
        }
        if (logicalSizeBytes < 0 || changedFileCount < 0) {
            throw new IllegalArgumentException("Backup counts must not be negative");
        }
    }

    public static BackupRow from(BackupRecord record) {
        Objects.requireNonNull(record, "record");
        List<DestinationResult> destinations = record.result().destinations();
        return new BackupRow(
                record.manifest().backupId(),
                record.manifest().worldId(),
                record.manifest().worldName(),
                record.manifest().createdAt(),
                record.manifest().label(),
                record.manifest().trigger(),
                record.result().status(),
                destination(destinations, DestinationType.GIT),
                destination(destinations, DestinationType.ZIP),
                record.manifest().sourceByteCount(),
                record.manifest().changedFileCount());
    }

    public boolean hasDurableCopy() {
        return git.durable() || zip.durable();
    }

    public SyncStatus remoteSyncStatus() {
        return git.syncStatus();
    }

    public VerificationStatus verificationStatus() {
        List<BackupDestinationView> durable = List.of(git, zip).stream()
                .filter(BackupDestinationView::durable)
                .toList();
        if (durable.isEmpty()) {
            return VerificationStatus.UNAVAILABLE;
        }
        if (durable.stream().anyMatch(view -> view.verificationStatus() == VerificationStatus.FAILED)) {
            return VerificationStatus.FAILED;
        }
        if (durable.stream().allMatch(view -> view.verificationStatus() == VerificationStatus.VERIFIED)) {
            return VerificationStatus.VERIFIED;
        }
        return VerificationStatus.NOT_VERIFIED;
    }

    private static BackupDestinationView destination(
            List<DestinationResult> destinations,
            DestinationType type) {
        Optional<DestinationResult> result = destinations.stream()
                .filter(candidate -> candidate.destination() == type)
                .findFirst();
        return BackupDestinationView.from(type, result);
    }
}
