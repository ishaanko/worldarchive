package dev.ishaankot.worldarchive.command.model;

import dev.ishaankot.worldarchive.model.DestinationHealth;
import dev.ishaankot.worldarchive.model.DestinationHealthStatus;
import dev.ishaankot.worldarchive.model.DestinationType;
import dev.ishaankot.worldarchive.model.SensitiveDataRedactor;
import java.util.Objects;

/** Credential-safe, concise destination health line. */
public record CommandDestinationHealth(
        DestinationType destination,
        DestinationHealthStatus status,
        String detail) {
    private static final int MAXIMUM_DETAIL_LENGTH = 160;

    public CommandDestinationHealth {
        Objects.requireNonNull(destination, "destination");
        Objects.requireNonNull(status, "status");
        detail = concise(Objects.requireNonNull(detail, "detail"));
    }

    public static CommandDestinationHealth from(DestinationHealth health) {
        Objects.requireNonNull(health, "health");
        return new CommandDestinationHealth(health.destination(), health.status(), health.message());
    }

    private static String concise(String value) {
        String safe = SensitiveDataRedactor.redact(value).strip();
        if (safe.length() <= MAXIMUM_DETAIL_LENGTH) {
            return safe;
        }
        return safe.substring(0, MAXIMUM_DETAIL_LENGTH - 1) + "…";
    }
}
