package dev.ishaankot.worldarchive.ui.model;

import dev.ishaankot.worldarchive.model.DestinationStatus;
import dev.ishaankot.worldarchive.model.DestinationType;
import dev.ishaankot.worldarchive.model.SensitiveDataRedactor;
import java.util.Objects;
import java.util.Optional;

/** Safe concise outcome line for one destination. */
public record DestinationOutcomeView(
        DestinationType destination,
        DestinationStatus status,
        Optional<String> detail) {
    private static final int MAXIMUM_DETAIL_LENGTH = 160;

    public DestinationOutcomeView {
        Objects.requireNonNull(destination, "destination");
        Objects.requireNonNull(status, "status");
        detail = Objects.requireNonNull(detail, "detail")
                .map(DestinationOutcomeView::concise);
    }

    private static String concise(String value) {
        String safe = SensitiveDataRedactor.redact(value).strip();
        if (safe.length() <= MAXIMUM_DETAIL_LENGTH) {
            return safe;
        }
        return safe.substring(0, MAXIMUM_DETAIL_LENGTH - 1) + "…";
    }
}
