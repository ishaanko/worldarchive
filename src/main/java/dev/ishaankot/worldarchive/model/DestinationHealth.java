package dev.ishaankot.worldarchive.model;

import java.time.Instant;
import java.util.Objects;

/** Credential-safe health snapshot for status screens and commands. */
public record DestinationHealth(
        DestinationType destination,
        DestinationHealthStatus status,
        String message,
        Instant checkedAt) {
    public DestinationHealth {
        Objects.requireNonNull(destination, "destination");
        Objects.requireNonNull(status, "status");
        message = SensitiveDataRedactor.redact(Objects.requireNonNull(message, "message"));
        if (message.length() > 2_048
                || message.chars().anyMatch(character -> Character.isISOControl(character))) {
            throw new IllegalArgumentException("Health message is too long or contains control characters");
        }
        Objects.requireNonNull(checkedAt, "checkedAt");
    }

    public static DestinationHealth notChecked(DestinationType destination) {
        return new DestinationHealth(
                destination,
                DestinationHealthStatus.UNCONFIGURED,
                "Not checked",
                Instant.EPOCH);
    }
}
