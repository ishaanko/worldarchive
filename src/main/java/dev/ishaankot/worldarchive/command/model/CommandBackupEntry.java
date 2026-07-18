package dev.ishaankot.worldarchive.command.model;

import dev.ishaankot.worldarchive.model.BackupId;
import dev.ishaankot.worldarchive.model.BackupRecord;
import dev.ishaankot.worldarchive.model.BackupStatus;
import dev.ishaankot.worldarchive.model.BackupTrigger;
import dev.ishaankot.worldarchive.model.SensitiveDataRedactor;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/** One renderer-neutral row for the `/backup list` command. */
public record CommandBackupEntry(
        BackupId backupId,
        String shortId,
        Instant createdAt,
        Optional<String> label,
        BackupTrigger trigger,
        BackupStatus status) {
    public CommandBackupEntry {
        Objects.requireNonNull(backupId, "backupId");
        Objects.requireNonNull(shortId, "shortId");
        Objects.requireNonNull(createdAt, "createdAt");
        label = Objects.requireNonNull(label, "label").map(SensitiveDataRedactor::redact);
        Objects.requireNonNull(trigger, "trigger");
        Objects.requireNonNull(status, "status");
        if (shortId.isEmpty()
                || shortId.chars().anyMatch(Character::isISOControl)
                || !backupId.toString().startsWith(shortId)) {
            throw new IllegalArgumentException("shortId must be a backup ID prefix");
        }
    }

    public static CommandBackupEntry from(BackupRecord record, String shortId) {
        Objects.requireNonNull(record, "record");
        return new CommandBackupEntry(
                record.manifest().backupId(),
                shortId,
                record.manifest().createdAt(),
                record.manifest().label(),
                record.manifest().trigger(),
                record.result().status());
    }
}
