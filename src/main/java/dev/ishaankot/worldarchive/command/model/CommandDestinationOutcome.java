package dev.ishaankot.worldarchive.command.model;

import dev.ishaankot.worldarchive.model.DestinationStatus;
import dev.ishaankot.worldarchive.model.DestinationType;
import dev.ishaankot.worldarchive.model.SensitiveDataRedactor;
import java.util.Objects;
import java.util.Optional;

/** Safe destination outcome for command feedback. */
public record CommandDestinationOutcome(
        DestinationType destination,
        DestinationStatus status,
        Optional<String> detail) {
    private static final int MAXIMUM_DETAIL_LENGTH = 160;

    public CommandDestinationOutcome {
        Objects.requireNonNull(destination, "destination");
        Objects.requireNonNull(status, "status");
        detail = Objects.requireNonNull(detail, "detail").map(CommandDestinationOutcome::safeDetail);
    }

    private static String safeDetail(String value) {
        String safe = SensitiveDataRedactor.redact(value).strip();
        if (safe.length() <= MAXIMUM_DETAIL_LENGTH) {
            return safe;
        }
        return safe.substring(0, MAXIMUM_DETAIL_LENGTH - 1) + "…";
    }
}
