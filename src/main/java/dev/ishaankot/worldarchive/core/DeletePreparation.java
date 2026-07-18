package dev.ishaankot.worldarchive.core;

import dev.ishaankot.worldarchive.model.BackupId;
import dev.ishaankot.worldarchive.model.SensitiveDataRedactor;
import java.time.Instant;
import java.util.Objects;

/** Short-lived confirmation issued before an explicit destructive delete. */
public record DeletePreparation(
        BackupId backupId,
        OperationId confirmationToken,
        String description,
        Instant expiresAt) {
    public DeletePreparation {
        Objects.requireNonNull(backupId, "backupId");
        Objects.requireNonNull(confirmationToken, "confirmationToken");
        description = SensitiveDataRedactor.redact(Objects.requireNonNull(description, "description"));
        if (description.isBlank()
                || description.length() > 512
                || description.chars().anyMatch(character -> Character.isISOControl(character))) {
            throw new IllegalArgumentException("Delete description is blank, too long, or unsafe");
        }
        Objects.requireNonNull(expiresAt, "expiresAt");
    }
}
